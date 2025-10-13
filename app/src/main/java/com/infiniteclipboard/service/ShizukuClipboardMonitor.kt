// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 全局剪贴板监听（shell 权限）：反射 newProcess + 监听 Binder/权限回调，修复“未连接”误判
package com.infiniteclipboard.service

import android.content.Context
import android.content.pm.PackageManager
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuClipboardMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var monitorJob: Job? = null
    @Volatile private var monitorProc: Process? = null
    @Volatile private var binderReady: Boolean = false

    fun init(context: Context) {
        // 初始态
        binderReady = safePing()
        // Binder 连接/断开监听
        Shizuku.addBinderReceivedListener {
            binderReady = true
            LogUtils.d("ShizukuMonitor", "Binder received")
            val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("shizuku_enabled", false)
            if (enabled) start(context)
        }
        Shizuku.addBinderDeadListener {
            binderReady = false
            LogUtils.d("ShizukuMonitor", "Binder dead")
        }
        // 权限回调：授权后自动启动
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("shizuku_enabled", true).apply()
                start(context)
            }
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun isAvailable(): Boolean = binderReady || safePing()

    fun hasPermission(): Boolean {
        return try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    }

    fun requestPermission() {
        try {
            if (hasPermission()) return
            Shizuku.requestPermission(10086)
        } catch (_: Throwable) { }
    }

    fun start(context: Context) {
        if (!isAvailable() || !hasPermission()) {
            LogUtils.d("ShizukuMonitor", "不可用或未授权，start 跳过")
            return
        }
        if (monitorJob != null) {
            LogUtils.d("ShizukuMonitor", "已在运行，跳过")
            return
        }
        val appCtx = context.applicationContext
        monitorJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val cmd = arrayOf("sh", "-c", "cmd clipboard monitor")
                    val proc = newProcessCompat(cmd) ?: throw IllegalStateException("newProcess 返回 null")
                    monitorProc = proc
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        LogUtils.d("ShizukuMonitor", "启动 monitor 进程成功")
                        while (isActive) {
                            val ln = reader.readLine() ?: break
                            if (ln.isBlank()) continue
                            LogUtils.d("ShizukuMonitor", "监测到变化: $ln")
                            val text = tryGetClipboardText()
                            if (!text.isNullOrBlank()) {
                                try {
                                    val repo = (appCtx as ClipboardApplication).repository
                                    repo.insertItem(text)
                                    LogUtils.d("ShizukuMonitor", "入库成功，内容前50: ${text.take(50)}")
                                } catch (e: Throwable) {
                                    LogUtils.e("ShizukuMonitor", "入库失败", e)
                                }
                            }
                        }
                        LogUtils.d("ShizukuMonitor", "monitor 进程结束")
                    }
                } catch (e: Throwable) {
                    LogUtils.e("ShizukuMonitor", "monitor 异常", e)
                } finally {
                    try { monitorProc?.destroy() } catch (_: Throwable) { }
                    monitorProc = null
                }
                attempt++
                val backoff = (500L * attempt).coerceAtMost(5000L)
                delay(backoff)
            }
        }
    }

    fun stop() {
        try { monitorProc?.destroy() } catch (_: Throwable) { }
        monitorProc = null
        monitorJob?.cancel()
        monitorJob = null
        LogUtils.d("ShizukuMonitor", "停止 monitor")
    }

    private fun tryGetClipboardText(): String? {
        return try {
            val p = newProcessCompat(arrayOf("sh", "-c", "cmd clipboard get")) ?: return null
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                val sb = StringBuilder()
                while (true) {
                    val ln = reader.readLine() ?: break
                    sb.append(ln).append('\n')
                }
                val out = sb.toString().trim()
                if (out.isEmpty() || out == "null") null else out
            }
        } catch (e: Throwable) {
            LogUtils.e("ShizukuMonitor", "get 失败", e)
            null
        }
    }

    // 兼容 API 变动：通过反射调用 Shizuku.newProcess(String[], String[]?, String?)
    private fun newProcessCompat(cmd: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            LogUtils.e("ShizukuMonitor", "newProcess 反射失败", t)
            null
        }
    }
}