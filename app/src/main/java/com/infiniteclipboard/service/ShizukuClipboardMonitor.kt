// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 全局剪贴板监听（Binder 版，替代 cmd）：通过 IClipboard 轮询读取，ROM 无需支持 `cmd clipboard`
// 特性：
// - 仅在 Shizuku 已连接且已授权时运行
// - 反射绑定 IClipboard 并调用 getPrimaryClip(…)，兼容多签名 (String,int)/(String)/()
// - 解析 ClipData 为纯文本；内容变更才入库
// - 心跳日志；自动重试与 Binder 重新获取
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
import rikka.shizuku.system.server.SystemServiceHelper
import java.lang.reflect.Method
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
    // 0: getPrimaryClip(), 1: getPrimaryClip(String), 2: getPrimaryClip(String, int)
    @Volatile private var methodSigType: Int = -1

    // 最近一次变更的摘要（用于去重）
    private val lastTextHash = AtomicLong(0L)
    private val lastPollAt = AtomicLong(0L)

    private const val REQ_CODE = 10086

    fun init(context: Context) {
        val binderReady = safePing()
        LogUtils.d(TAG, "init: binderReady=$binderReady sdk=${Build.VERSION.SDK_INT}")
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

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    // 主线程请求权限
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
            // 尝试绑定 IClipboard 并解析方法签名（失败会反复重试）
            ensureIClipboardBound(appCtx)

            // 心跳
            heartbeatJob?.cancel()
            heartbeatJob = launch {
                while (isActive) {
                    delay(2000)
                    val since = System.currentTimeMillis() - lastPollAt.get()
                    LogUtils.d(TAG, "heartbeat: lastPoll=${since}ms iCb=${iClipboard!=null} sig=$methodSigType perm=${hasPermission()} job=${pollJob?.isActive==true}")
                }
            }

            // 轮询读取（轻量，避免占用过多）
            val PKG = appCtx.packageName
            var userIdCache: Int? = null

            while (isActive) {
                lastPollAt.set(System.currentTimeMillis())
                try {
                    var proxy = iClipboard
                    var m = methodGetPrimaryClip
                    if (proxy == null || m == null) {
                        ensureIClipboardBound(appCtx)
                        proxy = iClipboard
                        m = methodGetPrimaryClip
                    }

                    val clipData = if (proxy != null && m != null) {
                        when (methodSigType) {
                            2 -> {
                                val uid = userIdCache ?: (getMyUserIdOrNull() ?: 0).also { userIdCache = it }
                                m.invoke(proxy, PKG, uid) as? ClipData
                            }
                            1 -> m.invoke(proxy, PKG) as? ClipData
                            0 -> m.invoke(proxy) as? ClipData
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
                    // 若出现反射/安全异常，下次循环会重绑
                    LogUtils.e(TAG, "binder 轮询失败", t)
                    // 清空缓存以触发重试
                    iClipboard = null
                    methodGetPrimaryClip = null
                    methodSigType = -1
                    delay(600) // 短暂退避
                }

                delay(350) // 轮询间隔（可按需调整）
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
        methodSigType = -1
    }

    // 绑定 IClipboard 并解析 getPrimaryClip 方法签名
    private fun ensureIClipboardBound(context: Context) {
        if (iClipboard != null && methodGetPrimaryClip != null && methodSigType >= 0) return
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
            // 尝试方法签名：2参(String,int) → 1参(String) → 0参()
            val clazz = proxy.javaClass
            var m: Method? = null
            var sig = -1

            // 2参
            try {
                m = clazz.getMethod("getPrimaryClip", String::class.java, Int::class.javaPrimitiveType)
                sig = 2
            } catch (_: NoSuchMethodException) { }
            // 1参
            if (m == null) {
                try {
                    m = clazz.getMethod("getPrimaryClip", String::class.java)
                    sig = 1
                } catch (_: NoSuchMethodException) { }
            }
            // 0参
            if (m == null) {
                try {
                    m = clazz.getMethod("getPrimaryClip")
                    sig = 0
                } catch (_: NoSuchMethodException) { }
            }

            if (m == null) {
                LogUtils.d(TAG, "未找到 getPrimaryClip 方法，proxy=${clazz.name}")
                iClipboard = null
                return
            }

            methodGetPrimaryClip = m
            methodSigType = sig
            LogUtils.d(TAG, "IClipboard 绑定成功，签名类型=$sig 方法=$m")
        } catch (t: Throwable) {
            LogUtils.e(TAG, "绑定 IClipboard 失败", t)
            iClipboard = null
            methodGetPrimaryClip = null
            methodSigType = -1
        }
    }

    // ClipData → 纯文本
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
        return if (oneLine.length <= max) oneLine else oneLine.substring(0, max) + "…"
    }
}