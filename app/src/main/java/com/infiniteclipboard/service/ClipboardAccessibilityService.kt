// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
// 无障碍服务：监听变化入库 + 提供“边缘小条”可调用的复制/剪切/粘贴
// 增强：判断是否有选区；有则按选区复制/剪切；无选区则自动全选后生效；剪切失败时手动置空或删除选区文本
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
import kotlin.math.max
import kotlin.math.min

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
            return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        // 读取当前选区范围（若无有效选区则返回 null）
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

        // 设置文本（兜底）
        private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            return try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Throwable) { false }
        }

        // 捕获节点文本（全量）
        private fun captureNodeText(node: AccessibilityNodeInfo?): String? {
            return node?.text?.toString()
        }

        // 捕获选区文本（若存在选区）
        private fun captureSelectedText(node: AccessibilityNodeInfo?): String? {
            val full = node?.text?.toString() ?: return null
            val range = getSelectionRange(node) ?: return null
            val (s, e) = range
            return if (s in 0..full.length && e in 0..full.length && s < e) {
                full.substring(s, e)
            } else null
        }

        // 复制：有选区→复制选区；无选区→自动全选后复制；无论系统广播是否触发，直接设置系统剪贴板并返回文本用于入库
        fun captureCopy(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null

            // 先尽量拿选区文本
            var textToRecord = captureSelectedText(node)

            // 若无选区，尝试全选以准备复制
            if (textToRecord.isNullOrEmpty()) {
                // 先读取全量文本作为备份
                textToRecord = captureNodeText(node)
                selectAll(node)
            }

            // 尝试 COPY（即便控件不触发系统剪贴板，我们也会手动写入）
            node.performAction(AccessibilityNodeInfo.ACTION_COPY)

            // 手动写系统剪贴板并返回
            if (!textToRecord.isNullOrEmpty()) {
                ClipboardUtils.setClipboardText(svc, textToRecord)
            }
            return textToRecord
        }

        // 剪切：有选区→剪切选区；无选区→自动全选后剪切；
        // 如 ACTION_CUT 失败，则手动把被剪内容写入系统剪贴板，并用 SET_TEXT 置空或删除选区文本
        fun captureCut(): String? {
            val svc = instanceRef?.get() ?: return null
            val node = focusedEditableNode(svc) ?: return null

            val full = captureNodeText(node) ?: ""
            val sel = getSelectionRange(node)

            var cutText: String?
            var cutOk: Boolean

            if (sel != null) {
                val (s, e) = sel
                cutText = if (s in 0..full.length && e in 0..full.length && s < e) full.substring(s, e) else ""
                cutOk = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                if (!cutOk) {
                    // 手动删除选区文本
                    val newText = full.removeRange(s, e.coerceAtMost(full.length))
                    setText(node, newText)
                }
            } else {
                // 无选区：剪切全部
                cutText = full
                cutOk = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                if (!cutOk) {
                    setText(node, "")
                }
            }

            if (!cutText.isNullOrEmpty()) {
                // 无论 ACTION_CUT 是否成功，手动同步系统剪贴板
                ClipboardUtils.setClipboardText(svc, cutText)
            }
            return cutText
        }

        // 粘贴：优先 ACTION_PASTE；失败则直接 SET_TEXT（替换全部）
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