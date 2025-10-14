// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// 绑定稳态化：先 start 再 bind；超时/死链/异常重连也先 start；退避重试；更详日志
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    @Volatile private var pollJob: Job? = null
    @Volatile private var userService: IClipboardUserService? = null
    @Volatile private var running = false
    @Volatile private var binding = false

    private val lastTextHash = AtomicLong(0L)

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var bindTimeoutRunnable: Runnable? = null
    private val retryAttempt = AtomicInteger(0)

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(service)
            running = true
            binding = false
            retryAttempt.set(0)
            cancelBindTimeout()
            LogUtils.d(TAG, "CONNECTED: UserService 已连接")
            startPolling()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: UserService 已断开")
            scheduleReconnect(reason = "SERVICE_DISCONNECTED")
        }
    }

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()} sdk=${Build.VERSION.SDK_INT}")
        Shizuku.addBinderReceivedListener {
            LogUtils.d(TAG, "BINDER_RECEIVED")
            if (hasPermission()) start(context)
        }
        Shizuku.addBinderDeadListener {
            LogUtils.d(TAG, "BINDER_DEAD")
            stop()
            scheduleReconnect(context, "BINDER_DEAD")
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "PERM_RESULT: $granted")
            if (granted) start(context) else LogUtils.d(TAG, "PERM_DENIED")
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun isRunning(): Boolean = running

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { onResult(true); return }
        val post = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) mainHandler.post { post() }
        else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    mainHandler.post { post() }
                }
            })
        }
        mainHandler.postDelayed({ onResult(hasPermission()) }, 1200L)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: available=$avail permission=$perm running=$running binding=$binding")
        if (!avail || !perm) {
            LogUtils.d(TAG, "START_SKIP: 不可用或未授权")
            return
        }
        if (running || binding) {
            LogUtils.d(TAG, "START_SKIP: 已连接或正在绑定")
            return
        }
        if (!::userServiceArgs.isInitialized) {
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(context, ClipboardUserService::class.java)
            )
                .processNameSuffix("shizuku")
                .daemon(true)
                .tag("clipboard")
                .version(1)
        }
        try {
            // 关键：先显式启动，再绑定（部分环境仅 bind 不触发拉起）
            try {
                Shizuku.startUserService(userServiceArgs)
                LogUtils.d(TAG, "START_SERVICE: 调用 startUserService 成功")
            } catch (e: Throwable) {
                LogUtils.e(TAG, "START_SERVICE_FAIL", e)
            }

            binding = true
            LogUtils.d(TAG, "BIND_START: 触发绑定")
            Shizuku.bindUserService(userServiceArgs, connection)
            scheduleBindTimeout(context)
        } catch (e: Throwable) {
            binding = false
            LogUtils.e(TAG, "BIND_EXCEPTION: 绑定异常", e)
            scheduleReconnect(context, "BIND_EXCEPTION")
        }
    }

    private fun scheduleBindTimeout(context: Context, timeoutMs: Long = 4000L) {
        cancelBindTimeout()
        bindTimeoutRunnable = Runnable {
            if (userService == null) {
                binding = false
                running = false
                val delay = computeBackoffDelay()
                LogUtils.d(TAG, "BIND_TIMEOUT: ${timeoutMs}ms 未连接，${delay}ms 后重试")

                // 再尝试显式启动一次
                try {
                    Shizuku.startUserService(userServiceArgs)
                    LogUtils.d(TAG, "START_SERVICE@TIMEOUT: 已再次调用")
                } catch (_: Throwable) {}

                // 防御性解绑（忽略异常）
                try {
                    if (::userServiceArgs.isInitialized) {
                        Shizuku.unbindUserService(userServiceArgs, connection, true)
                    }
                } catch (_: Throwable) {}

                scope.launch { delay(delay); start(context) }
            }
        }
        mainHandler.postDelayed(bindTimeoutRunnable!!, timeoutMs)
    }

    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    private fun computeBackoffDelay(): Long {
        val a = retryAttempt.getAndIncrement()
        val shift = if (a < 3) a else 3 // 0,1,2,3 => x1,x2,x4,x8
        var d = 700L shl shift // 700, 1400, 2800, 5600
        if (d > 7000L) d = 7000L
        return d
    }

    private fun scheduleReconnect(context: Context? = null, reason: String) {
        val delay = computeBackoffDelay()
        LogUtils.d(TAG, "RECONNECT_SCHEDULE: reason=$reason delay=${delay}ms")
        scope.launch {
            delay(delay)
            context?.let { start(it) }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val svc = userService
                    if (svc == null) {
                        delay(500)
                        continue
                    }
                    val text = svc.getClipboardText()
                    LogUtils.clipboard("Shizuku后台", text)
                    if (!text.isNullOrEmpty()) {
                        val h = text.hashCode().toLong()
                        if (h != lastTextHash.get()) {
                            lastTextHash.set(h)
                            try {
                                val ctx = ClipboardApplication.instance
                                val id = ctx.repository.insertItem(text)
                                LogUtils.d(TAG, "POLL_SAVE_OK id=$id")
                            } catch (e: Throwable) {
                                LogUtils.e(TAG, "POLL_SAVE_FAIL", e)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    LogUtils.e(TAG, "POLL_ERROR", e)
                    delay(800)
                }
                delay(500)
            }
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
            if (::userServiceArgs.isInitialized) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (_: Throwable) {}
        userService = null
    }
}