// 文件: app/src/main/java/com/infiniteclipboard/ui/ClipboardWindowActivity.kt
package com.infiniteclipboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ActivityClipboardWindowBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ClipboardWindowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipboardWindowBinding
    private lateinit var adapter: ClipboardAdapter
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClipboardWindowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 等比例缩小：宽 = 屏幕 80%，高 = 屏幕 80%，顶部对齐，底部自然留 ~20% 空白；左右各 ~10%
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.8f).toInt()
        val h = (dm.heightPixels * 0.8f).toInt()
        window.setLayout(w, h)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        setupRecyclerView()
        setupCloseButton()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = ClipboardAdapter(
            onCopyClick = { item -> copyToClipboard(item.content) },
            onDeleteClick = { item -> deleteItem(item) },
            onItemClick = { item ->
                copyToClipboard(item.content)
                finish()
            },
            onShareClick = { item -> shareText(item.content) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ClipboardWindowActivity)
            adapter = this@ClipboardWindowActivity.adapter
            // 保持原界面：不额外改动内边距/排版
        }
    }

    private fun setupCloseButton() {
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.allItems.collectLatest { items ->
                adapter.submitList(items)
            }
        }
    }

    private fun copyToClipboard(content: String) {
        val clip = ClipData.newPlainText("clipboard", content)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun deleteItem(item: ClipboardEntity) {
        lifecycleScope.launch { repository.deleteItem(item) }
    }
}