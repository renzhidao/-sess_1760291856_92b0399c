// 文件: app/src/main/java/com/infiniteclipboard/ui/LogViewerActivity.kt
package com.infiniteclipboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.infiniteclipboard.R
import com.infiniteclipboard.databinding.ActivityLogViewerBinding
import com.infiniteclipboard.utils.LogUtils

class LogViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.log_viewer_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadLog()
        binding.btnRefresh.setOnClickListener {
            loadLog(); Toast.makeText(this, R.string.log_refreshed, Toast.LENGTH_SHORT).show()
        }
        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", binding.tvLog.text))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_message)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    LogUtils.clearLog(); loadLog(); Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun loadLog() {
        binding.tvLog.text = LogUtils.getLogContent()
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}