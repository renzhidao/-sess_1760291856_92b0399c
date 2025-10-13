// 文件: app/src/main/java/com/infiniteclipboard/ui/TestProbeActivity.kt
// 最小桩：保留类避免编译问题（不暴露入口，不占用资源）
package com.infiniteclipboard.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TestProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}