// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
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
    private val lastTextHash = AtomicLong(0L)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.infiniteclipboard",
            ClipboardUserService::class.java.name
        )
    ).daemon(false).tag("clipboard").version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(binder)
            LogUtils.d(TAG, "UserService 已连接")
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            LogUtils.d(TAG, "UserService 已断开")
        }
    }

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
    fun hasPermission(): Boolean = try { 
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED 
    } catch (_: Throwable) { false }

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { 
            onResult(true)
            return 
        }
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
        LogUtils.d(TAG, "start(): available=$avail permission=$perm")
        
        if (!avail || !perm) {
            LogUtils.d(TAG, "不可用或未授权，start 跳过")
            return
        }
        
        if (userService != null) {
            LogUtils.d(TAG, "UserService 已绑定，跳过")
            return
        }

        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            LogUtils.d(TAG, "正在绑定 UserService...")
        } catch (e: Throwable) {
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
                        LogUtils.d(TAG, "UserService 未就绪，等待重连...")
                        delay(2000)
                        continue
                    }

                    val text = svc.clipboardText
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
                    delay(1000)
                }

                delay(500)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "stop()")
        pollJob?.cancel()
        pollJob = null
        
        try {
            userService?.destroy()
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Throwable) {}
        
        userService = null
    }
}