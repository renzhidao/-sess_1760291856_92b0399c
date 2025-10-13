// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
// 无障碍服务：监听变化入库 + 提供“边缘小条”可调用的复制/剪切/粘贴（带直录入库的文本抓取能力）
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
                LogUtils.d("AccessibilityService", "检测到剪切板变化读取: ${text?.take(50)}")
                if (!text.isNullOrEmpty() && text != lastClipboardContent) {
                    lastClipboardContent = text
                    try { repository.insertItem(text) } catch (_: Throwable) { }
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
            return focused
        }

        // 选中全部（兜底）
        private fun selectAll(node: AccessibilityNodeInfo): Boolean {
            return try {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            } catch (_: Throwable) { false }
        }

        // 直接设置文本（兜底）
        private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            return try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Throwable) { false }
        }

        // 抓取文本（优先 node.text；选区细粒度多数控件不提供，退化为全量文本）
        private fun captureNodeText(node: AccessibilityNodeInfo?): String? {
            return node?.text?.toString()
        }

        // 复制：尽力而为 + 返回我们认为复制/应被记录的文本（直接用于入库与设置系统剪贴板）
        fun captureCopy(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null
            // 先抓文本，避免复制受限时拿不到
            var text = captureNodeText(node)
            // 先尝试 COPY；失败则全选后 COPY
            var copied = node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            if (!copied) {
                selectAll(node)
                copied = node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                if (text.isNullOrEmpty()) text = captureNodeText(node)
            }
            // 系统剪贴板同步（不依赖系统广播）
            if (!text.isNullOrEmpty()) {
                ClipboardUtils.setClipboardText(svc, text)
            }
            return text
        }

        // 剪切：尽力而为 + 返回剪切掉的文本（直接用于入库与设置系统剪贴板）
        fun captureCut(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null
            // 抓旧文本
            val oldText = captureNodeText(node)
            // 优先 CUT；失败则全选后 CUT；仍失败则“复制后置空”
            var cut = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            if (!cut) {
                selectAll(node)
                cut = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                if (!cut && !oldText.isNullOrEmpty()) {
                    // 置空兜底
                    setText(node, "")
                    ClipboardUtils.setClipboardText(svc, oldText)
                }
            }
            // 同步系统剪贴板（部分控件 CUT 成功也会自动写系统剪贴板，这里再次确保）
            if (!oldText.isNullOrEmpty()) {
                ClipboardUtils.setClipboardText(svc, oldText)
            }
            return oldText
        }

        // 粘贴：优先 ACTION_PASTE；失败则直接 SET_TEXT
        fun performPaste(text: String?): Boolean {
            val svc = instanceRef?.get() ?: return false
            val node = focusedEditableNode(svc) ?: return false
            return try {
                if (!text.isNullOrEmpty()) {
                    ClipboardUtils.setClipboardText(svc, text)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
                if (!text.isNullOrEmpty()) setText(node, text) else false
            } catch (_: Throwable) { false }
        }
    }
}