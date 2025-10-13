// 文件: app/src/main/java/com/infiniteclipboard/utils/LogUtils.kt
package com.infiniteclipboard.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun init(context: Context) {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File(logDir, "clipboard_log.txt")
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log("E", tag, if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message)
    
    // 剪切板读取专用日志（带来源标记）
    fun clipboard(source: String, content: String?) {
        val preview = content?.take(50)?.replace("\n", "\\n") ?: "null"
        log("D", "Clipboard[$source]", preview)
    }

    private fun log(level: String, tag: String, message: String) {
        val ts = dateFormat.format(Date())
        val line = "$ts [$level] $tag: $message\n"
        android.util.Log.println(
            when (level) { "E" -> android.util.Log.ERROR; "D" -> android.util.Log.DEBUG; else -> android.util.Log.INFO },
            tag, message
        )
        try { logFile?.appendText(line) } catch (_: Exception) {}
    }

    fun getLogContent(): String = try { logFile?.readText() ?: "日志文件不存在" } catch (e: Exception) { "读取日志失败: ${e.message}" }
    fun clearLog() { try { logFile?.writeText("") } catch (_: Exception) {} }
    fun getLogFilePath(): String? = logFile?.absolutePath
}