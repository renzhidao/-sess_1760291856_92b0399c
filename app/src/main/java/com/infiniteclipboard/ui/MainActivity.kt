// 文件: app/src/main/java/com/infiniteclipboard/ui/MainActivity.kt
package com.infiniteclipboard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ActivityMainBinding
import com.infiniteclipboard.service.ClipboardMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ClipboardAdapter
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportToUri(uri)
    }
    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        setupRecyclerView()
        setupSearchView()
        setupButtons()
        checkPermissions()
        observeData()

        ClipboardMonitorService.start(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> { showClearAllDialog(); true }
            R.id.action_export -> { createDoc.launch("clipboard_backup.json"); true }
            R.id.action_import -> { openDoc.launch(arrayOf("application/json")); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = ClipboardAdapter(
            onCopyClick = { item -> copyToClipboard(item.content) },
            onDeleteClick = { item -> deleteItem(item) },
            onItemClick = { item -> copyToClipboard(item.content) },
            onShareClick = { item -> shareText(item.content) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupSearchView() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                adapter.setHighlightQuery(query)
                searchItems(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupButtons() {
        binding.btnClearAll.setOnClickListener { showClearAllDialog() }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.allItems.collectLatest { items ->
                adapter.submitList(items)
                updateEmptyView(items.isEmpty())
            }
        }
        lifecycleScope.launch {
            repository.itemCount.collectLatest { count ->
                binding.tvItemCount.text = getString(R.string.item_count, count)
            }
        }
    }

    private fun searchItems(query: String) {
        lifecycleScope.launch {
            if (query.isEmpty()) {
                repository.allItems.collectLatest { items ->
                    adapter.submitList(items)
                    updateEmptyView(items.isEmpty())
                }
            } else {
                repository.searchItems(query).collectLatest { items ->
                    adapter.submitList(items)
                    updateEmptyView(items.isEmpty())
                }
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

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all)
            .setMessage("确定要清空所有剪切板记录吗？")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch { repository.deleteAll() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
            }
        }
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_required)
            .setMessage("需要悬浮窗权限以显示剪切板窗口")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 导出/导入（保持不变）
    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = repository.getAllOnce()
                val arr = JSONArray()
                list.forEach { e ->
                    val obj = JSONObject()
                    obj.put("content", e.content)
                    obj.put("timestamp", e.timestamp)
                    obj.put("length", e.length)
                    arr.put(obj)
                }
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.export_success), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.export_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            sb.append(line).append('\n')
                        }
                    }
                }
                val text = sb.toString()
                val arr = JSONArray(text)
                val list = mutableListOf<ClipboardEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val c = obj.optString("content", "")
                    val t = obj.optLong("timestamp", System.currentTimeMillis())
                    list.add(ClipboardEntity(content = c, timestamp = t, length = c.length))
                }
                repository.importItems(list)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.import_success, list.size), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.import_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}