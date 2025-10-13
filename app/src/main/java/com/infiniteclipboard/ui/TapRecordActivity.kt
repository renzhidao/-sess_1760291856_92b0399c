// 文件: app/src/main/java/com/infiniteclipboard/ui/TapRecordActivity.kt
// TapRecordActivity - 瞬时前台透明页（前台读取后立刻关闭）
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
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val text = ClipboardUtils.getClipboardTextWithRetries(
                this@TapRecordActivity, attempts = 4, intervalMs = 120L
            )
            if (!text.isNullOrEmpty()) {
                try {
                    val repo = (application as ClipboardApplication).repository
                    repo.insertItem(text)
                } catch (_: Throwable) { }
            }
            withContext(Dispatchers.Main) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }
}