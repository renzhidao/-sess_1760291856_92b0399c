// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
// 无障碍服务：监听变化入库 + 提供复制/剪切/粘贴动作给边缘小条调用
package com.infiniteclipboard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class ClipboardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private var lastClipboardContent: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        instanceRef = WeakReference(this)
        LogUtils.d("AccessibilityService", "辅助服务已启动，剪切板监听已注册")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClipboardChange()
        }
    }

    override fun onInterrupt() {
        LogUtils.d("AccessibilityService", "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) { }
        serviceScope.cancel()
        instanceRef = null
        LogUtils.d("AccessibilityService", "服务已销毁")
    }

    private fun handleClipboardChange() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val text = ClipboardUtils.getClipboardTextWithRetries(
                    context = this@ClipboardAccessibilityService,
                    attempts = 4,
                    intervalMs = 120L
                )
                if (!text.isNullOrEmpty() && text != lastClipboardContent) {
                    lastClipboardContent = text
                    try { repository.insertItem(text) } catch (_: Throwable) { }
                    // 确保前台服务常驻（双通道监听）
                    ClipboardMonitorService.start(applicationContext)
                }
            } catch (e: Exception) {
                LogUtils.e("AccessibilityService", "处理剪切板失败", e)
            }
        }
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<ClipboardAccessibilityService>? = null

        // 查找当前焦点可编辑节点
        private fun focusedEditableNode(svc: AccessibilityService?): AccessibilityNodeInfo? {
            val root = svc?.rootInActiveWindow ?: return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return if (focused.isEditable || (focused.className?.toString()?.contains("EditText", true) == true)) focused else focused
        }

        // 复制：有选区则复制，无选区则尝试全选后复制
        fun performCopy(): Boolean {
            val svc = instanceRef?.get() ?: return false
            val node = focusedEditableNode(svc) ?: return false
            return try {
                // 先尝试复制
                if (node.performAction(AccessibilityNodeInfo.ACTION_COPY)) return true
                // 无选区则尝试全选
                selectAll(node)
                node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            } catch (_: Throwable) { false }
        }

        // 剪切：有选区则剪切，无选区全选后剪切；不支持时置空
        fun performCut(): Boolean {
            val svc = instanceRef?.get() ?: return false
            val node = focusedEditableNode(svc) ?: return false
            return try {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CUT)) return true
                selectAll(node)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CUT)) return true
                // 置空文本兜底（部分控件支持）
                setText(node, "")
            } catch (_: Throwable) { false }
        }

        // 粘贴：优先 ACTION_PASTE；不支持则直接设置文本
        fun performPaste(text: String?): Boolean {
            val svc = instanceRef?.get() ?: return false
            val node = focusedEditableNode(svc) ?: return false
            return try {
                if (!text.isNullOrEmpty()) {
                    ClipboardUtils.setClipboardText(svc, text)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
                // 直接设置文本（会替换全部）
                if (!text.isNullOrEmpty()) setText(node, text) else false
            } catch (_: Throwable) { false }
        }

        private fun selectAll(node: AccessibilityNodeInfo): Boolean {
            return try {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    // 粗暴使用大值，部分实现会自动裁剪到末尾
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
    }
}