// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 全局剪贴板监听（Binder 版，替代 cmd）：IClipboard 反射读取，兼容多签名/多命名变体 + 隐藏API豁免
package com.infiniteclipboard.service

import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val SERVICE = "clipboard"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var pollJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null

    // IClipboard 代理 & 反射方法缓存
    @Volatile private var iClipboard: Any? = null
    @Volatile private var methodGetPrimaryClip: Method? = null

    // 调用计划类型
    // 0: getPrimaryClip()
    // 1: getPrimaryClip(String)
    // 2: getPrimaryClip(String, Int)
    // 3: getPrimaryClip(String, String)
    // 4: getPrimaryClip(String, String, Int)
    // 5: getPrimaryClip(AttributionSource)
    // 6: getPrimaryClip(AttributionSource, Int)
    @Volatile private var planType: Int = -1

    // 最近一次变更的摘要（用于去重）
    private val lastTextHash = AtomicLong(0L)
    private val lastPollAt = AtomicLong(0L)

    private const val REQ_CODE = 10086
    private val hiddenReady = AtomicBoolean(false)

    fun init(context: Context) {
        val binderReady = safePing()
        LogUtils.d(TAG, "init: binderReady=$binderReady sdk=${Build.VERSION.SDK_INT}")

        // 再次保证隐藏API豁免（即便 Application 已调用，这里兜底一次）
        ensureHiddenApiExemptions()

        Shizuku.addBinderReceivedListener {
            LogUtils.d(TAG, "Binder received")
            if (hasPermission()) start(context)
        }
        Shizuku.addBinderDeadListener {
            LogUtils.d(TAG, "Binder dead")
            stop()
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "permission result: $granted")
            if (granted) start(context)
        }
    }

    private fun ensureHiddenApiExemptions() {
        if (hiddenReady.get()) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/")
            hiddenReady.set(true)
            LogUtils.d(TAG, "hidden api exemptions applied")
        } catch (t: Throwable) {
            LogUtils.e(TAG, "apply hidden api exemptions failed", t)
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { onResult(true); return }
        val post = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) {
            Handler(Looper.getMainLooper()).post { post() }
        } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    Handler(Looper.getMainLooper()).post { post() }
                }
            })
        }
        Handler(Looper.getMainLooper()).postDelayed({ onResult(hasPermission()) }, 1200L)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "start(): available=$avail permission=$perm jobActive=${pollJob?.isActive==true}")
        if (!avail || !perm) {
            LogUtils.d(TAG, "不可用或未授权，start 跳过")
            return
        }
        if (pollJob != null) {
            LogUtils.d(TAG, "已在运行，跳过")
            return
        }

        val appCtx = context.applicationContext

        pollJob = scope.launch {
            ensureHiddenApiExemptions()
            ensureIClipboardBound(appCtx)

            heartbeatJob?.cancel()
            heartbeatJob = launch {
                while (isActive) {
                    delay(2000)
                    val since = System.currentTimeMillis() - lastPollAt.get()
                    LogUtils.d(TAG, "heartbeat: lastPoll=${since}ms iCb=${iClipboard!=null} plan=$planType perm=${hasPermission()} job=${pollJob?.isActive==true}")
                }
            }

            val PKG = appCtx.packageName
            var userIdCache: Int? = null

            while (isActive) {
                lastPollAt.set(System.currentTimeMillis())
                try {
                    var proxy = iClipboard
                    var m = methodGetPrimaryClip
                    if (proxy == null || m == null || planType < 0) {
                        ensureHiddenApiExemptions()
                        ensureIClipboardBound(appCtx)
                        proxy = iClipboard
                        m = methodGetPrimaryClip
                    }

                    val clipData: ClipData? = if (proxy != null && m != null && planType >= 0) {
                        when (planType) {
                            6 -> { // (AttributionSource, Int)
                                val uid = userIdCache ?: (getMyUserIdOrNull() ?: 0).also { userIdCache = it }
                                val src = buildAttributionSourceOrNull(Process.myUid(), PKG, null)
                                if (src != null) m.invoke(proxy, src, uid) as? ClipData else null
                            }
                            5 -> { // (AttributionSource)
                                val src = buildAttributionSourceOrNull(Process.myUid(), PKG, null)
                                if (src != null) m.invoke(proxy, src) as? ClipData else null
                            }
                            4 -> { // (String, String, Int)
                                val uid = userIdCache ?: (getMyUserIdOrNull() ?: 0).also { userIdCache = it }
                                m.invoke(proxy, PKG, null, uid) as? ClipData
                            }
                            3 -> { // (String, String)
                                m.invoke(proxy, PKG, null) as? ClipData
                            }
                            2 -> { // (String, Int)
                                val uid = userIdCache ?: (getMyUserIdOrNull() ?: 0).also { userIdCache = it }
                                m.invoke(proxy, PKG, uid) as? ClipData
                            }
                            1 -> { // (String)
                                m.invoke(proxy, PKG) as? ClipData
                            }
                            0 -> { // ()
                                m.invoke(proxy) as? ClipData
                            }
                            else -> null
                        }
                    } else null

                    val text = clipDataToText(appCtx, clipData)
                    if (!text.isNullOrEmpty()) {
                        val h = text.hashCode().toLong()
                        if (h != lastTextHash.get()) {
                            lastTextHash.set(h)
                            try {
                                val repo = (appCtx as ClipboardApplication).repository
                                val id = repo.insertItem(text)
                                LogUtils.d(TAG, "Binder入库成功 id=$id, 内容前50: ${snippet(text, 50)}")
                            } catch (e: Throwable) {
                                LogUtils.e(TAG, "Binder入库失败", e)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    LogUtils.e(TAG, "binder 轮询失败", t)
                    iClipboard = null
                    methodGetPrimaryClip = null
                    planType = -1
                    delay(600)
                }

                delay(350)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "stop()")
        pollJob?.cancel()
        pollJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        iClipboard = null
        methodGetPrimaryClip = null
        planType = -1
    }

    // 绑定 IClipboard 并解析 getPrimaryClip 方法签名（全面兼容 + 名称模糊匹配）
    private fun ensureIClipboardBound(context: Context) {
        if (iClipboard != null && methodGetPrimaryClip != null && planType >= 0) return
        try {
            val binder = SystemServiceHelper.getSystemService(SERVICE) as? IBinder
            if (binder == null) {
                LogUtils.d(TAG, "SystemServiceHelper.getSystemService($SERVICE) 返回 null")
                return
            }
            val stubClazz = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stubClazz.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, binder) ?: run {
                LogUtils.d(TAG, "IClipboard.asInterface 返回 null")
                return
            }
            iClipboard = proxy

            // 遍历所有 public 方法，匹配名中包含 "getPrimaryClip" 的 ClipData 返回方法
            val clazz = proxy.javaClass
            val allMethods = clazz.methods.toList()
            val candidates = allMethods.filter { m ->
                val name = m.name.lowercase()
                name.contains("getprimaryclip") && m.returnType == ClipData::class.java
            }

            var picked: Method? = null
            var plan = -1

            // 优先 AttributionSource 变体
            for (m in candidates) {
                val pt = m.parameterTypes
                if (pt.size == 2 && isAttributionSource(pt[0]) && isInt(pt[1])) { picked = m; plan = 6; break }
            }
            if (picked == null) {
                for (m in candidates) {
                    val pt = m.parameterTypes
                    if (pt.size == 1 && isAttributionSource(pt[0])) { picked = m; plan = 5; break }
                }
            }
            // 再尝试 String 族
            if (picked == null) {
                for (m in candidates) {
                    val pt = m.parameterTypes
                    if (pt.size == 3 && isString(pt[0]) && isString(pt[1]) && isInt(pt[2])) { picked = m; plan = 4; break }
                }
            }
            if (picked == null) {
                for (m in candidates) {
                    val pt = m.parameterTypes
                    if (pt.size == 2 && isString(pt[0]) && isInt(pt[1])) { picked = m; plan = 2; break }
                }
            }
            if (picked == null) {
                for (m in candidates) {
                    val pt = m.parameterTypes
                    if (pt.size == 2 && isString(pt[0]) && isString(pt[1])) { picked = m; plan = 3; break }
                }
            }
            if (picked == null) {
                for (m in candidates) {
                    val pt = m.parameterTypes
                    if (pt.size == 1 && isString(pt[0])) { picked = m; plan = 1; break }
                }
            }
            if (picked == null) {
                for (m in candidates) {
                    if (m.parameterCount == 0) { picked = m; plan = 0; break }
                }
            }

            if (picked == null) {
                if (planType != -2) {
                    LogUtils.d(TAG, "未找到 getPrimaryClip 方法，proxy=${clazz.name}")
                }
                planType = -2
                iClipboard = null
                methodGetPrimaryClip = null
                return
            }

            methodGetPrimaryClip = picked
            planType = plan
            LogUtils.d(TAG, "IClipboard 绑定成功，计划=$plan 方法=$picked")
        } catch (t: Throwable) {
            LogUtils.e(TAG, "绑定 IClipboard 失败", t)
            iClipboard = null
            methodGetPrimaryClip = null
            planType = -1
        }
    }

    private fun isString(c: Class<*>) = c == String::class.java
    private fun isInt(c: Class<*>) = (c == Int::class.javaPrimitiveType) || (c == Integer::class.java)
    private fun isAttributionSource(c: Class<*>) = c.name == "android.content.AttributionSource"

    // 反射构造 AttributionSource
    private fun buildAttributionSourceOrNull(uid: Int, pkg: String, tag: String?): Any? {
        return try {
            val cls = Class.forName("android.content.AttributionSource")
            val ctors: Array<Constructor<*>> = cls.constructors as Array<Constructor<*>>
            var ctor: Constructor<*>? = null
            for (c in ctors) {
                val pt = c.parameterTypes
                if (pt.size == 3 && isInt(pt[0]) && isString(pt[1]) && isString(pt[2])) { ctor = c; break }
            }
            if (ctor != null) {
                ctor.newInstance(uid, pkg, tag)
            } else {
                for (c in ctors) {
                    val pt = c.parameterTypes
                    if (pt.size == 2 && isInt(pt[0]) && isString(pt[1])) {
                        return c.newInstance(uid, pkg)
                    }
                }
                val builderCls = try { Class.forName("android.content.AttributionSource\$Builder") } catch (_: Throwable) { null }
                if (builderCls != null) {
                    val bCtor = builderCls.getConstructor(Int::class.javaPrimitiveType, String::class.java)
                    val builder = bCtor.newInstance(uid, pkg)
                    val setTag = builderCls.methods.firstOrNull { it.name == "setAttributionTag" && it.parameterTypes.size == 1 && isString(it.parameterTypes[0]) }
                    if (setTag != null && tag != null) setTag.invoke(builder, tag)
                    val build = builderCls.methods.firstOrNull { it.name == "build" && it.parameterCount == 0 }
                    if (build != null) return build.invoke(builder)
                }
                null
            }
        } catch (_: Throwable) { null }
    }

    private fun clipDataToText(context: Context, clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val coerced = try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
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
        val out = sb.toString().trim()
        return if (out.isEmpty()) null else out
    }

    private fun getMyUserIdOrNull(): Int? = try {
        val uh = Class.forName("android.os.UserHandle")
        val m = uh.getMethod("myUserId")
        (m.invoke(null) as? Int)
    } catch (_: Throwable) { null }

    private fun snippet(s: String?, max: Int = 120): String {
        if (s == null) return "null"
        val oneLine = s.replace("\r", "\\r").replace("\n", "\\n")
        return if (oneLine.length <= max) oneLine 