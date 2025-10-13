// 文件: app/src/main/java/com/infiniteclipboard/utils/ClipboardUtils.kt
// ClipboardUtils - 工具类
package com.infiniteclipboard.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock

object ClipboardUtils {

    fun getClipboardText(context: Context): String? {
        // 统一走更鲁棒的实现
        return getClipboardTextRobust(context)
    }

    // 更鲁棒：遍历所有 Item，优先 coerceToText，兼容 URI/HTML/Intent 等非常规来源
    fun getClipboardTextRobust(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            collectTextFromClip(context, clip)
        } catch (_: Throwable) {
            null
        }
    }

    // 带短延迟重试：适配“回调先到、内容稍后可读”的时序差异
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

    fun setClipboardText(context: Context, text: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("text", text)
        clipboardManager.setPrimaryClip(clip)
    }

    fun formatSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}