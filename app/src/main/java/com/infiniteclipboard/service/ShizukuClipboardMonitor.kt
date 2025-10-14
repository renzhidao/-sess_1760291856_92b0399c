// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.ClipData
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.IClipboardUserService
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val BIND_TIMEOUT_MS = 8000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false
    @Volatile private var binding = false
    @Volatile private var userService: IClipboardUserService? = null

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs
    private val retryAttempt = AtomicInteger(0)
    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(service)
            binding = false
            running = true
            retryAttempt.set(0)
            cancelBindTimeout()
            LogUtils.d(TAG, "CONNECTED: Shizuku UserService 已连接 component=${name?.flattenToShortString()}")
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: Shizuku UserService 已断开 component=${name?.flattenToShortString()}")
            scheduleReconnect("SERVICE_DISCONNECTED")
        }
    }

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()}")
        Shizuku.addBinderReceivedListener {
            LogUtils.d(TAG, "BINDER_RECEIVED")
            if (hasPermission()) start(context)
        }
        Shizuku.addBinderDeadListener {
            LogUtils.d(TAG, "BINDER_DEAD")
            stop()
            scheduleReconnect("BINDER_DEAD", context)
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val ok = result == PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "PERM_RESULT=$ok")
            if (ok) start(context)
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
        mainHandler.postDelayed({ onResult(hasPermission()) }, 1000)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: avail=$avail perm=$perm running=$running binding=$binding")
        logUserServiceInfo(context)

        if (!avail || !perm) return
        if (!::userServiceArgs.isInitialized) {
            userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
                .processNameSuffix("shizuku")
                .daemon(true)
                .tag("clipboard")
                .version(1)
        }
        if (!running && !binding) {
            try {
                binding = true
                Shizuku.bindUserService(userServiceArgs, connection)
                scheduleBindTimeout(context, timeoutMs = BIND_TIMEOUT_MS)
                LogUtils.d(TAG, "BIND_START args={suffix=shizuku, daemon=true, tag=clipboard, version=1}")
            } catch (t: Throwable) {
                binding = false
                LogUtils.e(TAG, "BIND_EXCEPTION", t)
                scheduleReconnect("BIND_EXCEPTION", context)
            }
        }
        // 无论绑定是否成功，立刻启动轮询（包含直连兜底），避免等待绑定期间错失采集窗口
        if (!running) {
            running = true // 标记为运行中以启动轮询
            startPolling()
        }
    }

    fun stop() {
        LogUtils.d(TAG, "STOP")
        pollJob?.cancel()
        pollJob = null
        running = false
        binding = false
        cancelBindTimeout()
        try {
            userService?.destroy()
            if (::userServiceArgs.isInitialized) Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Throwable) {}
        userService = null
    }

    fun onPrimaryClipChanged() {
        if (!running) return
        scope.launch {
            repeat(6) {
                if (tryReadOnce()) return@launch
                delay(120)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                tryReadOnce()
                delay(500)
            }
        }
    }

    private suspend fun tryReadOnce(): Boolean {
        // 优先走用户服务；失败则走直连兜底（FALLBACK_DIRECT）
        val svc = userService
        if (svc != null) {
            try {
                val text = svc.getClipboardText()
                return handleTextIfAny(text, source = "Shizuku后台")
            } catch (t: Throwable) {
                LogUtils.e(TAG, "READ_FAIL_VIA_USERSERVICE", t)
            }
        }
        // 直连兜底：不依赖绑定，不再被 BIND_TIMEOUT 卡住
        return try {
            val text = readClipboardDirect()
            handleTextIfAny(text, source = "FALLBACK_DIRECT")
        } catch (t: Throwable) {
            LogUtils.e(TAG, "READ_FAIL_DIRECT", t)
            false
        }
    }

    private suspend fun handleTextIfAny(text: String?, source: String): Boolean {
        if (!text.isNullOrEmpty()) {
            val h = text.hashCode().toLong()
            if (h != lastSavedHash.get()) {
                lastSavedHash.set(h)
                val id = ClipboardApplication.instance.repository.insertItem(text)
                LogUtils.clipboard(source, text)
                LogUtils.d(TAG, "SAVE_OK id=$id")
            }
            return true
        }
        return false
    }

    @Volatile private var bindTimeoutRunnable: Runnable? = null
    private fun scheduleBindTimeout(context: Context, timeoutMs: Long = BIND_TIMEOUT_MS) {
        cancelBindTimeout()
        bindTimeoutRunnable = Runnable {
            if (userService == null && binding) {
                binding = false
                LogUtils.d(TAG, "BIND_TIMEOUT after ${timeoutMs}ms")
                scheduleReconnect("BIND_TIMEOUT", context)
            }
        }
        mainHandler.postDelayed(bindTimeoutRunnable!!, timeoutMs)
    }

    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    private fun scheduleReconnect(reason: String, context: Context? = null) {
        val backoff = computeBackoff()
        LogUtils.d(TAG, "RECONNECT reason=$reason in ${backoff}ms")
        scope.launch {
            delay(backoff)
            withContext(Dispatchers.Main) {
                context?.let { start(it) }
            }
        }
    }

    private fun computeBackoff(): Long {
        val a = retryAttempt.getAndIncrement()
        val shift = if (a < 4) a else 4
        return (700L shl shift).coerceAtMost(10000L)
    }

    // ===== 运行时观测：打印 UserService 清单信息 =====
    private fun logUserServiceInfo(context: Context) {
        try {
            val pm = context.packageManager
            val cn = ComponentName(context, ClipboardUserService::class.java)
            val si = try { pm.getServiceInfo(cn, 0) } catch (_: Throwable) { null }
            if (si == null) {
                LogUtils.d(TAG, "ServiceInfo: NOT_FOUND for ${cn.flattenToShortString()}")
                return
            }
            val exported = si.exported
            val proc = si.processName ?: "null"
            val perm = si.permission ?: "null"
            LogUtils.d(TAG, "ServiceInfo: exported=$exported process=$proc permission=$perm")
        } catch (t: Throwable) {
            LogUtils.e(TAG, "SERVICE_INFO_FAIL", t)
        }
    }

    // ===== 直连兜底：使用 Shizuku 的 SystemServiceHelper 直接反射 IClipboard =====
    private fun readClipboardDirect(): String? {
        if (!isAvailable() || !hasPermission()) return null
        val proxy = obtainIClipboard() ?: return null
        val (clip, shape) = getPrimaryClipAllShapes(proxy)
        LogUtils.d(TAG, "FALLBACK_DIRECT: getPrimaryClip via $shape, items=${clip?.itemCount ?: 0}")
        return clipDataToText(ClipboardApplication.instance, clip)
    }

    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (_: Throwable) { null }
    }

    private fun getPrimaryClipAllShapes(proxy: Any): Pair<ClipData?, String> {
        val clazz = proxy.javaClass
        val userId = myUserId() ?: 0

        // 优先 (String, Int) 形态：传入 shell 包名，匹配调用身份
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java
        }?.let { m ->
            val tag = "getPrimaryClip(String,Int)"
            tryInvoke(tag, m, proxy, SHELL_PACKAGE, userId)?.let { return it to tag }
        }

        // 其次 (AttributionSource, Int)：构造 uid=2000(shell) + pkg=com.android.shell
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
            it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildShellAttributionSource()
            val tag = "getPrimaryClip(AttributionSource,Int)"
            tryInvoke(tag, m, proxy, src, userId)?.let { return it to tag }
        }

        // 最后 () 无参
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 0
        }?.let { m ->
            val tag = "getPrimaryClip()"
            tryInvoke(tag, m, proxy)?.let { return it to tag }
        }

        return null to "none"
    }

    private fun tryInvoke(tag: String, m: Method, target: Any, vararg args: Any?): ClipData? {
        return try {
            m.invoke(target, *args) as? ClipData
        } catch (t: Throwable) {
            LogUtils.e(TAG, "Invoke $tag failed", t)
            null
        }
    }

    private fun buildShellAttributionSource(): Any? {
        return try {
            val cls = Class.forName("android.content.AttributionSource")
            // 优先尝试 (int uid, String packageName, String attributionTag)
            cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java
            }?.newInstance(2000, SHELL_PACKAGE, null) ?: run {
                // 兼容 Builder 版本
                val bCls = Class.forName("android.content.AttributionSource\$Builder")
                val b = bCls.getConstructor(Int::class.javaPrimitiveType, String::class.java)
                    .newInstance(2000, SHELL_PACKAGE)
                val build = bCls.getMethod("build")
                build.invoke(b)
            }
        } catch (_: Throwable) { null }
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

    private fun myUserId(): Int? {
        return try {
            val uh = Class.forName("android.os.UserHandle")
            val m = uh.getMethod("myUserId")
            m.invoke(null) as? Int
        } catch (_: Throwable) { null }
    }
}