// 文件: app/src/main/java/com/infiniteclipboard/ui/ClipboardWindowActivity.kt
package com.infiniteclipboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ActivityClipboardWindowBinding
import com.infiniteclipboard.ui.overlay.LinkOverlayPanel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ClipboardWindowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipboardWindowBinding
    private lateinit var adapter: ClipboardAdapter
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }

    private lateinit var linkOverlay: LinkOverlayPanel
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (linkOverlay.hideIfShowing()) return
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClipboardWindowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.8f).toInt()
        val h = (dm.heightPixels * 0.8f).toInt()
        window.setLayout(w, h)
        window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 小窗里面板更窄：0.9 占比
        linkOverlay = LinkOverlayPanel(binding.root as ViewGroup, binding.recyclerView, 0.9f)
        linkOverlay.onShowStateChanged = { showing -> backCallback.isEnabled = showing }
        onBackPressedDispatcher.addCallback(this, backCallback)

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
            onShareClick = { item -> shareText(item.content) },
            onEditRequest = { item -> showEditDialog(item) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ClipboardWindowActivity)
            adapter = this@ClipboardWindowActivity.adapter
            addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val tv = view.findViewById<TextView>(R.id.tvContent)
                    tv?.maxLines = 1
                    tv?.ellipsize = TextUtils.TruncateAt.END
                    view.minimumHeight = dp(56f)
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }

        val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos in 0 until adapter.itemCount) {
                    val item = adapter.currentList[pos]
                    linkOverlay.showForText(item.content)
                    adapter.notifyItemChanged(pos)
                }
            }
        }
        ItemTouchHelper(swipe).attachToRecyclerView(binding.recyclerView)
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

    private fun showEditDialog(item: ClipboardEntity) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_clipboard, null)
        val et = view.findViewById<EditText>(R.id.etContent)
        et.setText(item.content)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("编辑内容")
            .setView(view)
            .create()

        view.findViewById<View>(R.id.btnClear).setOnClickListener { et.setText("") }
        view.findViewById<View>(R.id.btnCopy).setOnClickListener {
            val text = et.text?.toString().orEmpty()
            if (text.isNotEmpty()) copyToClipboard(text)
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnShare).setOnClickListener {
            val text = et.text?.toString().orEmpty()
            if (text.isNotEmpty()) shareText(text)
        }
        view.findViewById<View>(R.id.btnDelete).setOnClickListener {
            lifecycleScope.launch { repository.deleteItem(item) }
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            val text = et.text?.toString().orEmpty()
            lifecycleScope.launch {
                repository.deleteItem(item)
                if (text.isNotEmpty()) repository.insertItem(text)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v + 0.5f).toInt()
}