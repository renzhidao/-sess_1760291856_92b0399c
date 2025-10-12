// 文件: app/src/main/java/com/infiniteclipboard/utils/LogUtils.kt
package com.infiniteclipboard.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtils {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    
    fun init(context: Context) {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        logFile = File(logDir, "clipboard_log.txt")
    }
    
    fun d(tag: String, message: String) {
        log("D", tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log("E", tag, msg)
    }
    
    private fun log(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logMessage = "$timestamp [$level] $tag: $message\n"
        
        android.util.Log.println(
            when (level) {
                "E" -> android.util.Log.ERROR
                "D" -> android.util.Log.DEBUG
                else -> android.util.Log.INFO
            },
            tag,
            message
        )
        
        try {
            logFile?.appendText(logMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getLogContent(): String {
        return try {
            logFile?.readText() ?: "日志文件不存在"
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }
    
    fun clearLog() {
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
}