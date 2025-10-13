// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardAccessibilityService.kt
package com.infiniteclipboard.service

import android.accessibilityservice.AccessibilityService
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
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        LogUtils.d("AccessibilityService", "辅助服务已启动，剪切板监听已注册")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        LogUtils.d("AccessibilityService", "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        serviceScope.cancel()
        LogUtils.d("AccessibilityService", "服务已销毁")
    }

    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip == null) {
                LogUtils.d("AccessibilityService", "剪切板为空")
                return
            }

            if (clip.itemCount <= 0) {
                LogUtils.d("AccessibilityService", "剪切板项目数为0")
                return
            }

            val text = clip.getItemAt(0).text?.toString()
            LogUtils.d("AccessibilityService", "检测到剪切板变化: ${text?.take(50)}")

            if (!text.isNullOrEmpty()) {
                if (text != lastClipboardContent) {
                    lastClipboardContent = text
                    saveClipboardContent(text)
                } else {
                    LogUtils.d("AccessibilityService", "重复内容，跳过")
                }
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