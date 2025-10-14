// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 监控：绑定 UserService（防并发绑定）、daemon 常驻、成功后轮询后台读取
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

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(service)
            running = true
            binding = false
            LogUtils.d(TAG, "UserService 已连接")
            startPolling()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            userService = null
            LogUtils.d(TAG, "UserService 已断开")
        }
    }

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()} sdk=${Build.VERSION.SDK_INT}")
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
    fun isRunning(): Boolean = running

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
        LogUtils.d(TAG, "start(): available=$avail permission=$perm running=$running binding=$binding")
        if (!avail || !perm) {
            LogUtils.d(TAG, "不可用或未授权，start 跳过")
            return
        }
        if (running || binding) {
            LogUtils.d(TAG, "已连接或正在绑定，跳过")
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
            binding = true
            Shizuku.bindUserService(userServiceArgs, connection)
            LogUtils.d(TAG, "正在绑定 UserService...")
        } catch (e: Throwable) {
            binding = false
            LogUtils.e(TAG, "绑定 UserService 失败", e)
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
                                LogUtils.d(TAG, "Shizuku入库成功 id=$id")
                            } catch (e: Throwable) {
                                LogUtils.e(TAG, "Shizuku入库失败", e)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    LogUtils.e(TAG, "Shizuku轮询失败", e)
                    delay(800)
                }
                delay(500)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "stop()")
        pollJob?.cancel()
        pollJob = null
        running = false
        binding = false
        try {
            userService?.destroy()
            if (::userServiceArgs.isInitialized) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (_: Throwable) {}
        userService = null
    }
}