// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
package com.infiniteclipboard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
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
                }
            } catch (e: Exception) {
                LogUtils.e("AccessibilityService", "处理剪切板失败", e)
            }
        }
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<ClipboardAccessibilityService>? = null

        // 对外：注入一次轻点（屏幕绝对坐标）
        fun dispatchTap(x: Float, y: Float): Boolean {
            val svc = instanceRef?.get() ?: return false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                svc.dispatchGesture(gesture, null, null)
            } catch (_: Throwable) { false }
        }
    }
}