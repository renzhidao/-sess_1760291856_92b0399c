// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// 终局方案-客户端：只负责连接 Shizuku UserService，调用其接口读取剪贴板，不再在本进程做任何反射。
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.IClipboardUserService
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086

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
            LogUtils.d(TAG, "CONNECTED: Shizuku UserService 已连接")
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: Shizuku UserService 已断开")
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
        if (!avail || !perm || running || binding) return

        if (!::userServiceArgs.isInitialized) {
            userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
                .processNameSuffix("shizuku")
                .daemon(true)
                .tag("clipboard")
                .version(1)
        }
        try {
            binding = true
            Shizuku.bindUserService(userServiceArgs, connection)
            scheduleBindTimeout(context)
            LogUtils.d(TAG, "BIND_START")
        } catch (t: Throwable) {
            binding = false
            LogUtils.e(TAG, "BIND_EXCEPTION", t)
            scheduleReconnect("BIND_EXCEPTION", context)
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
                if (tryReadOnce()) return@launch // 读到就收工
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
        val svc = userService ?: return false
        var success = false
        try {
            val text = svc.getClipboardText() // 调用远程服务在 :shizuku 进程里读
            if (!text.isNullOrEmpty()) {
                success = true
                val h = text.hashCode().toLong()
                if (h != lastSavedHash.get()) {
                    lastSavedHash.set(h)
                    val id = ClipboardApplication.instance.repository.insertItem(text)
                    LogUtils.clipboard("Shizuku后台", text)
                    LogUtils.d(TAG, "SAVE_OK id=$id")
                }
            }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "READ_FAIL", t)
        }
        return success
    }
    
    @Volatile private var bindTimeoutRunnable: Runnable? = null
    private fun scheduleBindTimeout(context: Context, timeoutMs: Long = 4000L) {
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
            // 在主线程启动，确保 Shizuku API 调用安全
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
}