// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
package com.infiniteclipboard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

class ClipboardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        instanceRef = WeakReference(this)
        LogUtils.d("AccessibilityService", "辅助服务已启动")
    }

    // 用 startService 投递点击事件到前台服务（避免广播/重载冲突）
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try {
                val it = Intent(this, ClipboardMonitorService::class.java).apply {
                    action = ClipboardMonitorService.ACTION_SCREEN_TAPPED
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(it)
                } else {
                    startService(it)
                }
            } catch (_: Throwable) { }
        }
    }

    override fun onInterrupt() {
        LogUtils.d("AccessibilityService", "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instanceRef = null
        LogUtils.d("AccessibilityService", "服务已销毁")
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<ClipboardAccessibilityService>? = null

        private fun focusedEditableNode(svc: AccessibilityService?): AccessibilityNodeInfo? {
            val root = svc?.rootInActiveWindow ?: return null
            return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        private fun getSelectionRange(node: AccessibilityNodeInfo?): Pair<Int, Int>? {
            if (node == null) return null
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start >= 0 && end >= 0 && start != end) {
                val s = min(start, end)
                val e = max(start, end)
                return if (s < e) s to e else null
            }
            return null
        }

        private fun selectAll(node: AccessibilityNodeInfo): Boolean {
            return try {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            } catch (_: Throwable) { false }
        }

        private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            return try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Throwable) { false }
        }

        private fun captureNodeText(node: AccessibilityNodeInfo?): String? {
            return node?.text?.toString()
        }

        private fun captureSelectedText(node: AccessibilityNodeInfo?): String? {
            val full = node?.text?.toString() ?: return null
            val range = getSelectionRange(node) ?: return null
            val (s, e) = range
            return if (s in 0..full.length && e in 0..full.length && s < e) {
                full.substring(s, e)
            } else null
        }

        // 复制
        fun captureCopy(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null

            var textToRecord = captureSelectedText(node)
            if (textToRecord.isNullOrEmpty()) {
                textToRecord = captureNodeText(node)
                selectAll(node)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_COPY)

            if (!textToRecord.isNullOrEmpty()) {
                ClipboardUtils.setClipboardText(svc, textToRecord)
                LogUtils.clipboard("无障碍复制", textToRecord)
            }
            return textToRecord
        }

        // 剪切
        fun captureCut(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null

            val full = captureNodeText(node) ?: ""
            val sel = getSelectionRange(node)

            var cutText: String?
            if (sel != null) {
                val (s, e) = sel
                cutText = if (s in 0..full.length && e in 0..full.length && s < e) full.substring(s, e) else ""
                val cutOk = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                if (!cutOk) {
                    val newText = full.removeRange(s, e.coerceAtMost(full.length))
                    setText(node, newText)
                }
            } else {
                cutText = full
                val cutOk = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                if (!cutOk) setText(node, "")
            }

            if (!cutText.isNullOrEmpty()) {
                ClipboardUtils.setClipboardText(svc, cutText)
                LogUtils.clipboard("无障碍剪切", cutText)
            }
            return cutText
        }

        // 粘贴（修复：明确返回布尔，避免“null 作为非空 Boolean”）
        fun performPaste(text: String?): Boolean {
            val svc = instanceRef?.get() ?: return false
            val node = focusedEditableNode(svc) ?: return false
            return try {
                // 先尝试直接粘贴
                var ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                // 失败时用指定文本兜底
                if (!ok && !text.isNullOrEmpty()) {
                    ClipboardUtils.setClipboardText(svc, text)
                    ok = setText(node, text)
                }
                ok
            } catch (_: Throwable) { false }
        }
    }
}