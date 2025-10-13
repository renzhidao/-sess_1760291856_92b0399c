// 文件: app/src/main/java/com/infiniteclipboard/ui/TapRecordActivity.kt
package com.infiniteclipboard.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TapRecordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
    }
    
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            // 增加重试：6次，间隔150ms
            val text = ClipboardUtils.getClipboardTextWithRetries(
                this@TapRecordActivity, 
                attempts = 6, 
                intervalMs = 150L
            )
            
            LogUtils.clipboard("前台Activity", text)
            
            if (!text.isNullOrEmpty()) {
                try {
                    val repo = (application as ClipboardApplication).repository
                    val id = repo.insertItem(text)
                    LogUtils.d("TapRecordActivity", "前台入库成功 id=$id")
                } catch (e: Throwable) {
                    LogUtils.e("TapRecordActivity", "前台入库失败", e)
                }
            } else {
                LogUtils.d("TapRecordActivity", "前台读取失败：内容为空")
            }
            
            withContext(Dispatchers.Main) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }
}