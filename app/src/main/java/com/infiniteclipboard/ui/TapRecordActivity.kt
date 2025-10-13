// 文件: app/src/main/java/com/infiniteclipboard/ui/TapRecordActivity.kt
package com.infiniteclipboard.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.ClipboardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TapRecordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 透明无界面，无动画
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 前台态，读取并写库，然后立即关闭
        lifecycleScope.launch(Dispatchers.IO) {
            val text = ClipboardUtils.getClipboardTextWithRetries(this@TapRecordActivity, attempts = 4, intervalMs = 120L)
            if (!text.isNullOrEmpty()) {
                val repo = (application as ClipboardApplication).repository
                try { repo.insertItem(text) } catch (_: Throwable) { }
            }
            withContext(Dispatchers.Main) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }
}