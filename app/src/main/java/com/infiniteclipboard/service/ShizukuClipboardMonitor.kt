// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// 仅用 Shizuku 的稳定方案：绑定 Shizuku UserService（运行于 :shizuku/shell 身份）读取系统 IClipboard，规避后台限制。
// 特点：
// - 不再在应用进程里直接反射 IClipboard（避免 checkPackage/后台限制）；改为在 :shizuku 进程调用（UID=shell）。
// - 绑定加 startUserService + 超时 + 退避重试；连接成功后轮询入库（协程/IO）。
// - 提供 onPrimaryClipChanged 突发拉读（不必等下一拍轮询）。
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
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
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false
    @Volatile private var binding = false
    @Volatile private var userService: IClipboardUserService? = null

    private lateinit var args: Shizuku.UserServiceArgs
    private val retry = AtomicInteger(0)
    private val lastHash = AtomicLong(Long.MIN_VALUE)

    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(service)
            binding = false
            running = true
            retry.set(0)
            LogUtils.d(TAG, "CONNECTED: Shizuku UserService")
            startPolling()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: Shizuku UserService")
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
        if (isAvailable()) main.post { req() } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    main.post { req() }
                }
            })
        }
        main.postDelayed({ onResult(hasPermission()) }, 1000)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: avail=$avail perm=$perm running=$running binding=$binding")
        if (!avail || !perm) return
        if (running || binding) return

        if (!::args.isInitialized) {
            args = Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
                .processNameSuffix("shizuku")
                .daemon(true)
                .tag("clipboard")
                .version(1)
        }
        try {
            // 先显式启动，再绑定（某些机型仅 bind 不拉起）
            try {
                Shizuku.startUserService(args)
                LogUtils.d(TAG, "START_USER_SERVICE: ok")
            } catch (e: Throwable) {
                LogUtils.e(TAG, "START_USER_SERVICE: fail", e)
            }
            binding = true
            Shizuku.bindUserService(args, conn)
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
        try {
            userService?.destroy()
            if (::args.isInitialized) Shizuku.unbindUserService(args, conn, true)
        } catch (_: Throwable) {}
        userService = null
    }

    // 剪贴板变更时触发一次“突发读取”（不等下一拍轮询）
    fun onPrimaryClipChanged(context: Context) {
        if (!hasPermission() || !isAvailable()) return
        if (!running) start(context)
        scope.launch {
            repeat(6) {
                tryReadOnce()
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

    private suspend fun tryReadOnce() {
        val svc = userService ?: return
        try {
            val text = svc.getClipboardText() // 在 :shizuku（shell）进程读取，绕过后台限制
            if (!text.isNullOrEmpty()) {
                val h = text.hashCode().toLong()
                if (h != lastHash.get()) {
                    lastHash.set(h)
                    val id = ClipboardApplication.instance.repository.insertItem(text)
                    LogUtils.clipboard("Shizuku后台", text)
                    LogUtils.d(TAG, "SAVE_OK id=$id")
                }
            }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "READ_FAIL", t)
        }
    }

    private fun scheduleBindTimeout(context: Context, timeoutMs: Long = 4000L) {
        main.postDelayed({
            if (userService == null) {
                binding = false
                running = false
                LogUtils.d(TAG, "BIND_TIMEOUT after ${timeoutMs}ms")
                scheduleReconnect("BIND_TIMEOUT", context)
            }
        }, timeoutMs)
    }

    private fun scheduleReconnect(reason: String, context: Context? = null) {
        val backoff = computeBackoff()
        LogUtils.d(TAG, "RECONNECT reason=$reason in ${backoff}ms")
        scope.launch {
            delay(backoff)
            context?.let { start(it) }
        }
    }

    private fun computeBackoff(): Long {
        val a = retry.getAndIncrement()
        val shift = if (a < 3) a else 3 // 0,1,2,3 => x1,x2,x4,x8
        var d = 700L shl shift // 700,1400,2800,5600
        if (d > 7000L) d = 7000L
        return d
    }
}