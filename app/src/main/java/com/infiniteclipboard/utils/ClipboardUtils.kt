// 文件: app/src/main/java/com/infiniteclipboard/utils/ClipboardUtils.kt
package com.infiniteclipboard.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock

object ClipboardUtils {

    private const val INTERNAL_LABEL = "com.infiniteclipboard"

    fun getClipboardText(context: Context): String? {
        return getClipboardTextRobust(context)
    }

    // 兼容非常规来源：优先 coerceToText，再回退到 item.text
    fun getClipboardTextRobust(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            collectTextFromClip(context, clip)
        } catch (_: Throwable) {
            null
        }
    }

    // 短延迟重试：适配部分系统“回调先到、内容稍后可读”的时序
    fun getClipboardTextWithRetries(
        context: Context,
        attempts: Int = 4,
        intervalMs: Long = 120L
    ): String? {
        var i = 0
        while (i < attempts) {
            val text = getClipboardTextRobust(context)
            if (!text.isNullOrEmpty()) return text
            if (i < attempts - 1) SystemClock.sleep(intervalMs)
            i++
        }
        return null
    }

    private fun collectTextFromClip(context: Context, clip: ClipData): String? {
        if (clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val coerced = try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
            val piece = when {
                !coerced.isNullOrBlank() -> coerced
                item.text != null -> item.text.toString()
                else -> null
            }
            if (!piece.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(piece)
            }
        }
        val all = sb.toString().trim()
        return if (all.isEmpty()) null else all
    }

    // 使用我们自己的标签，方便在监听里识别“自家写入”的变更以跳过
    fun setClipboardText(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(INTERNAL_LABEL, text)
        cm.setPrimaryClip(clip)
    }

    fun formatSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}