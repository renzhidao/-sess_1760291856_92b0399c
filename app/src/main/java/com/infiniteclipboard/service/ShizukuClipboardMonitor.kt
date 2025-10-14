// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 后台监听（只用 Shizuku）：以 shell 身份直连 IClipboard；剪贴板变更触发突发读取（6x120ms）
// 深度诊断日志：详细打印反射命中、参数、异常、ClipData结构，便于定位“为什么读不到”
package com.infiniteclipboard.service

import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086

    // 以 shell 身份对齐系统校验（Shizuku ADB 模式）
    private const val SHELL_UID = 2000
    private const val SHELL_PKG = "com.android.shell"

    // 我们 app 自己写入剪贴板时用的标签（用于自写入过滤）
    private const val INTERNAL_LABEL = "com.infiniteclipboard"

    // 变更触发的突发读取参数
    private const val BURST_TRIES = 6
    private const val BURST_INTERVAL_MS = 120L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false

    // 去重与降噪
    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)
    private val lastLoggedHash = AtomicLong(Long.MIN_VALUE)

    // 仅定义一次，避免“Conflicting declarations”
    private data class ClipMeta(val text: String?, val label: String?)

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()} sdk=${Build.VERSION.SDK_INT}")
        Shizuku.addBinderReceivedListener { if (hasPermission()) start(context) }
        Shizuku.addBinderDeadListener {
            stop()
            mainHandler.postDelayed({ if (hasPermission()) start(context) }, 600)
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) start(context)
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun isRunning(): Boolean = running

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { onResult(true); return }
        val req = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) mainHandler.post { req() } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    mainHandler.post { req() }
                }
            })
        }
        mainHandler.postDelayed({ onResult(hasPermission()) }, 1000L)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "DIRECT_START: available=$avail permission=$perm running=$running")
        if (!avail || !perm || running) return
        startPolling(context)
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        running = false
        LogUtils.d(TAG, "STOP")
    }

    // 由服务 OnPrimaryClipChanged 回调触发
    fun onPrimaryClipChanged(context: Context) {
        if (!hasPermission() || !isAvailable()) return
        if (!running) start(context)
        scope.launch {
            var i = 0
            while (isActive && i < BURST_TRIES) {
                try {
                    val meta = readClipboardWithDiagnostics(context)
                    handleMeta(meta) // suspend 安全调用
                    if (!meta.text.isNullOrEmpty()) break
                } catch (_: Throwable) { }
                delay(BURST_INTERVAL_MS)
                i++
            }
        }
    }

    private fun startPolling(context: Context) {
        pollJob?.cancel()
        running = true
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val meta = readClipboardWithDiagnostics(context)
                    handleMeta(meta) // suspend 安全调用
                } catch (e: Throwable) {
                    LogUtils.e(TAG, "POLL_ERROR", e)
                }
                delay(500)
            }
        }
    }

    // 标记为 suspend：内含 repository.insertItem（suspend）
    private suspend fun handleMeta(meta: ClipMeta) {
        val text = meta.text ?: return
        if (text.isEmpty()) return
        if (meta.label == INTERNAL_LABEL) return // 自家写入过滤

        val h = text.hashCode().toLong()
        if (lastLoggedHash.get() != h) {
            LogUtils.clipboard("Shizuku后台", text)
            lastLoggedHash.set(h)
        }
        if (lastSavedHash.get() != h) {
            try {
                val id = ClipboardApplication.instance.repository.insertItem(text)
                lastSavedHash.set(h)
                LogUtils.d(TAG, "SAVE_OK id=$id")
            } catch (e: Throwable) {
                LogUtils.e(TAG, "SAVE_FAIL", e)
            }
        }
    }

    // ===== 深度诊断读取 =====

    private fun readClipboardWithDiagnostics(ctx: Context): ClipMeta {
        val binderOk = safePing()
        LogUtils.d(TAG, "DBG binderPing=$binderOk")
        val proxy = obtainIClipboard() ?: run {
            LogUtils.d(TAG, "DBG obtainIClipboard=null")
            return ClipMeta(null, null)
        }
        LogUtils.d(TAG, "DBG proxyClass=${proxy.javaClass.name}")

        // 列出可用 getPrimaryClip 方法签名
        try {
            val sigs = proxy.javaClass.methods.filter { it.name.equals("getPrimaryClip", true) }
                .joinToString("; ") { it.toGenericString() }
            LogUtils.d(TAG, "DBG methods=$sigs")
        } catch (_: Throwable) { }

        val (clip, hit) = getPrimaryClipAllShapes(ctx, proxy)
        LogUtils.d(TAG, "DBG hit=$hit clip=${if (clip==null) "null" else "ok"}")
        if (clip == null) return ClipMeta(null, null)

        val desc = try { clip.description } catch (_: Throwable) { null }
        val label = try { desc?.label?.toString() } catch (_: Throwable) { null }
        val mimeCount = try { desc?.mimeTypeCount } catch (_: Throwable) { null }
        val itemCount = clip.itemCount
        LogUtils.d(TAG, "DBG clipDescLabel=$label mimeCount=$mimeCount itemCount=$itemCount")

        // 打印每个 item 的类型与预览
        try {
            for (i in 0 until itemCount) {
                val it = clip.getItemAt(i)
                val hasText = it.text != null
                val hasUri = it.uri != null
                val hasIntent = it.intent != null
                val coerced = try { it.coerceToText(ctx)?.toString() } catch (_: Throwable) { null }
                val preview = (coerced ?: it.text?.toString() ?: "").take(120).replace("\n", "\\n")
                LogUtils.d(TAG, "DBG item[$i] hasText=$hasText hasUri=$hasUri hasIntent=$hasIntent preview=$preview")
            }
        } catch (_: Throwable) { }

        val text = clipDataToText(ctx, clip)
        return ClipMeta(text, label)
    }

    private fun getPrimaryClipAllShapes(ctx: Context, proxy: Any): Pair<ClipData?, String> {
        val clazz = proxy.javaClass
        val userIds = listOfNotNull(myUserId(), 0).distinct()
        val pkgs = listOf(SHELL_PKG, ctx.packageName)

        // 1) (AttributionSource, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == "android.content.AttributionSource" &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val src = buildShellAttributionSource()
            for (uid in userIds) {
                val tag = "getPrimaryClip(AttributionSource,$uid)"
                val cd = tryInvoke(tag, m, proxy, src, uid)
                if (cd != null) return cd to tag
            }
        }

        // 2) (AttributionSource)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildShellAttributionSource()
            val tag = "getPrimaryClip(AttributionSource)"
            val cd = tryInvoke(tag, m, proxy, src)
            if (cd != null) return cd to tag
        }

        // 3) (String,String,Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                (it.parameterTypes[2] == Int::class.javaPrimitiveType || it.parameterTypes[2] == Integer::class.java)
        }?.let { m ->
            for (pkg in pkgs) for (uid in userIds) {
                val tag = "getPrimaryClip($pkg,null,$uid)"
                val cd = tryInvoke(tag, m, proxy, pkg, null, uid)
                if (cd != null) return cd to tag
            }
        }

        // 4) (String,Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            for (pkg in pkgs) for (uid in userIds) {
                val tag = "getPrimaryClip($pkg,$uid)"
                val cd = tryInvoke(tag, m, proxy, pkg, uid)
                if (cd != null) return cd to tag
            }
        }

        // 5) (String,String)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }?.let { m ->
            for (pkg in pkgs) {
                val tag = "getPrimaryClip($pkg,null)"
                val cd = tryInvoke(tag, m, proxy, pkg, null)
                if (cd != null) return cd to tag
            }
        }

        // 6) (String)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.let { m ->
            for (pkg in pkgs) {
                val tag = "getPrimaryClip($pkg)"
                val cd = tryInvoke(tag, m, proxy, pkg)
                if (cd != null) return cd to tag
            }
        }

        // 7) ()
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterCount == 0
        }?.let { m ->
            val tag = "getPrimaryClip()"
            val cd = tryInvoke(tag, m, proxy)
            if (cd != null) return cd to tag
        }

        return null to "none"
    }

    // 统一的反射调用 + 诊断日志（成功打印 itemCount，失败打印异常）
    // tag 放在 vararg 之前，所有调用方都传入第一个参数，避免“缺少命名参数”编译错误
    private fun tryInvoke(tag: String, m: Method, target: Any, vararg args: Any?): ClipData? {
        return try {
            val res = m.invoke(target, *args) as? ClipData
            if (res == null) {
                LogUtils.d(TAG, "DBG TRY $tag -> null")
            } else {
                val ic = try { res.itemCount } catch (_: Throwable) { -1 }
                val label = try { res.description?.label } catch (_: Throwable) { null }
                LogUtils.d(TAG, "DBG TRY $tag -> OK itemCount=$ic label=$label")
            }
            res
        } catch (t: Throwable) {
            LogUtils.e(TAG, "DBG TRY $tag -> EX", t)
            null
        }
    }

    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (t: Throwable) {
            LogUtils.e(TAG, "DBG obtainIClipboard EX", t)
            null
        }
    }

    private fun clipDataToText(ctx: Context, clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val coerced = try { item.coerceToText(ctx)?.toString() } catch (_: Throwable) { null }
            val piece = when {
                !coerced.isNullOrBlank() -> coerced
                item.text != null -> item.text.toString()
                else -> null
            }
            if (!piece.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(piece)
            }
        }
        val all = sb.toString().trim()
        return if (all.isEmpty()) null else all
    }

    private fun buildShellAttributionSource(): Any? {
        return try {
            val cls = Class.forName("android.content.AttributionSource")
            // (int uid, String pkg, String renounced)
            cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java && p[2] == String::class.java
            }?.newInstance(SHELL_UID, SHELL_PKG, null) ?: run {
                // Builder(int uid, String pkg)
                val bCls = Class.forName("android.content.AttributionSource\$Builder")
                val b = bCls.getConstructor(Int::class.javaPrimitiveType, String::class.java)
                    .newInstance(SHELL_UID, SHELL_PKG)
                val build = bCls.getMethod("build")
                build.invoke(b)
            }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "DBG buildShellAttributionSource EX", t)
            null
        }
    }

    private fun myUserId(): Int? {
        return try {
            val uh = Class.forName("android.os.UserHandle")
            val m = uh.getMethod("myUserId")
            m.invoke(null) as? Int
        } catch (_: Throwable) { null }
    }
}