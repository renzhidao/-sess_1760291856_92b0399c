// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 直连后台监听（稳定版）：以 shell 身份（uid=2000, pkg=com.android.shell）访问 IClipboard，绕过后台读取门槛
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
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086

    // 关键：以 shell 身份对齐系统校验
    private const val SHELL_UID = 2000
    private const val SHELL_PKG = "com.android.shell"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false
    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)
    private val lastLoggedHash = AtomicLong(Long.MIN_VALUE)

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

    private fun startPolling(context: Context) {
        pollJob?.cancel()
        running = true
        pollJob = scope.launch {
            val app = ClipboardApplication.instance
            while (isActive) {
                try {
                    val meta = readClipboardViaShizukuWithMeta(context)
                    val text = meta.text
                    if (!text.isNullOrEmpty()) {
                        val h = text.hashCode().toLong()
                        if (lastLoggedHash.get() != h) {
                            LogUtils.clipboard("Shizuku后台", text)
                            lastLoggedHash.set(h)
                        }
                        if (lastSavedHash.get() != h) {
                            try {
                                val id = app.repository.insertItem(text)
                                lastSavedHash.set(h)
                                LogUtils.d(TAG, "SAVE_OK id=$id")
                            } catch (e: Throwable) {
                                LogUtils.e(TAG, "SAVE_FAIL", e)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    LogUtils.e(TAG, "POLL_ERROR", e)
                }
                delay(500)
            }
        }
    }

    // ——— shell 身份直连 IClipboard，返回 label+text —— //
    private data class ClipMeta(val text: String?, val label: String?)

    private fun readClipboardViaShizukuWithMeta(ctx: Context): ClipMeta {
        val proxy = obtainIClipboard() ?: return ClipMeta(null, null)
        var clip: ClipData? = invokeGetPrimaryClip(ctx, proxy)
        if (clip == null) clip = invokeGetPrimaryClipForUser(ctx, proxy)
        if (clip == null) clip = invokeGetPrimaryClipAsUser(ctx, proxy)
        val label = try { clip?.description?.label?.toString() } catch (_: Throwable) { null }
        val text = if (clip != null) clipDataToText(ctx, clip) else null
        return ClipMeta(text, label)
    }

    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (_: Throwable) { null }
    }

    // 兼容多形态 getPrimaryClip（全部按 shell 身份声明）
    private fun invokeGetPrimaryClip(ctx: Context, proxy: Any): ClipData? {
        val clazz = proxy.javaClass

        // (AttributionSource, Int userId)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == "android.content.AttributionSource" &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val src = buildShellAttributionSource()
            val userId = myUserId() ?: 0
            return try { if (src != null) m.invoke(proxy, src, userId) as? ClipData else null } catch (_: Throwable) { null }
        }

        // (AttributionSource)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildShellAttributionSource()
            return try { if (src != null) m.invoke(proxy, src) as? ClipData else null } catch (_: Throwable) { null }
        }

        // (String pkg, String feature, Int userId)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                (it.parameterTypes[2] == Int::class.javaPrimitiveType || it.parameterTypes[2] == Integer::class.java)
        }?.let { m ->
            val userId = myUserId() ?: 0
            return try { m.invoke(proxy, SHELL_PKG, null, userId) as? ClipData } catch (_: Throwable) { null }
        }

        // (String pkg, Int userId)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val userId = myUserId() ?: 0
            return try { m.invoke(proxy, SHELL_PKG, userId) as? ClipData } catch (_: Throwable) { null }
        }

        // (String pkg, String feature)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }?.let { m ->
            return try { m.invoke(proxy, SHELL_PKG, null) as? ClipData } catch (_: Throwable) { null }
        }

        // (String pkg)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.let { m ->
            return try { m.invoke(proxy, SHELL_PKG) as? ClipData } catch (_: Throwable) { null }
        }

        // ()
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterCount == 0
        }?.let { m ->
            return try { m.invoke(proxy) as? ClipData } catch (_: Throwable) { null }
        }

        return null
    }

    // forUser 变体（极少系统）
    private fun invokeGetPrimaryClipForUser(ctx: Context, proxy: Any): ClipData? {
        val clazz = proxy.javaClass
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClipForUser", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val userId = myUserId() ?: 0
            return try { m.invoke(proxy, SHELL_PKG, userId) as? ClipData } catch (_: Throwable) { null }
        }
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClipForUser", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Integer::class.java)
        }?.let { m ->
            val userId = myUserId() ?: 0
            return try { m.invoke(proxy, userId) as? ClipData } catch (_: Throwable) { null }
        }
        return null
    }

    // asUser 变体（极少系统）
    private fun invokeGetPrimaryClipAsUser(ctx: Context, proxy: Any): ClipData? {
        val clazz = proxy.javaClass
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClipAsUser", true) &&
                it.returnType == ClipData::class.java &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val userId = myUserId() ?: 0
            return try { m.invoke(proxy, SHELL_PKG, userId) as? ClipData } catch (_: Throwable) { null }
        }
        return null
    }

    private fun clipDataToText(ctx: Context, clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val piece = try { item.coerceToText(ctx)?.toString() } catch (_: Throwable) { item.text?.toString() }
            val clean = piece?.trim()
            if (!clean.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(clean)
            }
        }
        return sb.toString().trim().ifEmpty { null }
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
        } catch (_: Throwable) { null }
    }

    private fun myUserId(): Int? {
        return try {
            val uh = Class.forName("android.os.UserHandle")
            val m = uh.getMethod("myUserId")
            m.invoke(null) as? Int
        } catch (_: Throwable) { null }
    }
}