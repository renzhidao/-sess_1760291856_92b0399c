// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
package com.infiniteclipboard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        try {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Throwable) { }
        serviceScope.cancel()
        LogUtils.d("AccessibilityService", "服务已销毁")
    }

    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip == null || clip.itemCount <= 0) {
                LogUtils.d("AccessibilityService", "剪切板为空或无项目")
                return
            }
            val text = clip.getItemAt(0).text?.toString()
            LogUtils.d("AccessibilityService", "检测到剪切板变化: ${text?.take(50)}")
            if (!text.isNullOrEmpty() && text != lastClipboardContent) {
                lastClipboardContent = text
                saveClipboardContent(text)
            }
        } catch (e: Exception) {
            LogUtils.e("AccessibilityService", "处理剪切板失败", e)
        }
    }

    private fun saveClipboardContent(content: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val id = repository.insertItem(content)
                LogUtils.d("AccessibilityService", "保存成功，ID: $id, 内容: ${content.take(30)}")
            } catch (e: Exception) {
                LogUtils.e("AccessibilityService", "保存失败", e)
            }
        }
    }
}