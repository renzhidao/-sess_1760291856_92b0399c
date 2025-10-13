// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 全局剪贴板监听（shell 权限）：cmd clipboard monitor + cmd clipboard get
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

    fun isAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    }

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
                    monitorProc = Shizuku.newProcess(cmd, null, null)
                    val reader = BufferedReader(InputStreamReader(monitorProc!!.inputStream))
                    LogUtils.d("ShizukuMonitor", "启动 monitor 进程成功")
                    var line: String?
                    while (isActive && reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue
                        LogUtils.d("ShizukuMonitor", "监测到变化: $line")
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
                } catch (e: Throwable) {
                    LogUtils.e("ShizukuMonitor", "monitor 异常", e)
                } finally {
                    try { monitorProc?.destroy() } catch (_: Throwable) { }
                    monitorProc = null
                }
                // 进程异常退出，指数回退重启
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
            val p = Shizuku.newProcess(arrayOf("sh", "-c", "cmd clipboard get"), null, null)
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
            }
            val out = sb.toString().trim()
            if (out.isEmpty() || out == "null") null else out
        } catch (e: Throwable) {
            LogUtils.e("ShizukuMonitor", "get 失败", e)
            null
        }
    }
}