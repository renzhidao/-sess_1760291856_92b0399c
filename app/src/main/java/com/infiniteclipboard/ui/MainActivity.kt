// 文件: app/src/main/java/com/infiniteclipboard/ui/MainActivity.kt
package com.infiniteclipboard.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ActivityMainBinding
import com.infiniteclipboard.service.ClipboardAccessibilityService
import com.infiniteclipboard.service.ClipboardMonitorService
import com.infiniteclipboard.service.ShizukuClipboardMonitor
import com.infiniteclipboard.utils.LogUtils
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
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportToUri(uri) }

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFromUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        LogUtils.init(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        setupRecyclerView()
        setupSearchView()
        setupButtons()
        checkPermissions()
        observeData()

        // 初始化 Shizuku 回调
        ShizukuClipboardMonitor.init(this)

        ClipboardMonitorService.start(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val shizukuEnabled = prefs.getBoolean("shizuku_enabled", false)
        menu.findItem(R.id.action_shizuku_toggle)?.title =
            if (shizukuEnabled) "关闭Shizuku全局监听" else "开启Shizuku全局监听"

        val edgeBarEnabled = prefs.getBoolean("edge_bar_enabled", false)
        menu.findItem(R.id.action_edge_bar_toggle)?.title =
            if (edgeBarEnabled) getString(R.string.edge_bar_disable) else getString(R.string.edge_bar_enable)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_all -> { showClearAllDialog(); true }
        R.id.action_export -> { createDoc.launch("clipboard_backup.json"); true }
        R.id.action_import -> { openDoc.launch(arrayOf("application/json")); true }
        R.id.action_shizuku_toggle -> { toggleShizuku(); true }
        R.id.action_edge_bar_toggle -> { toggleEdgeBar(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleShizuku() {
        val enabled = prefs.getBoolean("shizuku_enabled", false)
        if (!enabled) {
            ShizukuClipboardMonitor.ensurePermission(this) { granted ->
                if (granted) {
                    prefs.edit().putBoolean("shizuku_enabled", true).apply()
                    ShizukuClipboardMonitor.start(this)
                    Toast.makeText(this, "已开启 Shizuku 全局监听", Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu()
                } else {
                    Toast.makeText(this, "Shizuku 未授权或未连接", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            prefs.edit().putBoolean("shizuku_enabled", false).apply()
            ShizukuClipboardMonitor.stop()
            Toast.makeText(this, "已关闭 Shizuku 全局监听", Toast.LENGTH_SHORT).show()
            invalidateOptionsMenu()
        }
    }

    private fun toggleEdgeBar() {
        val enabled = prefs.getBoolean("edge_bar_enabled", false)
        if (!enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "请授予“在其他应用上层显示”权限后再开启", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                    Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_LONG).show()
                }
                return
            }
            prefs.edit().putBoolean("edge_bar_enabled", true).apply()
            ClipboardMonitorService.start(this)
            val it = Intent(this, ClipboardMonitorService::class.java).apply {
                action = ClipboardMonitorService.ACTION_EDGE_BAR_ENABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
            Toast.makeText(this, "边缘小条已开启", Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putBoolean("edge_bar_enabled", false).apply()
            val it = Intent(this, ClipboardMonitorService::class.java).apply {
                action = ClipboardMonitorService.ACTION_EDGE_BAR_DISABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
            Toast.makeText(this, "边缘小条已关闭", Toast.LENGTH_SHORT).show()
        }
        invalidateOptionsMenu()
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
        binding.btnLog.setOnClickListener { startActivity(Intent(this, LogViewerActivity::class.java)) }
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
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch { repository.deleteAll() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE
                )
            }
        }
        checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_permission_title)
                .setMessage(R.string.accessibility_permission_message)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expected = ComponentName(this, ClipboardAccessibilityService::class.java).flattenToString()
        if (enabled.any {
                val si = it.resolveInfo.serviceInfo
                "${si.packageName}/${si.name}".equals(expected, ignoreCase = true)
            }) return true

        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val expected2 = "${packageName}/${ClipboardAccessibilityService::class.java.name}"
        return flat?.split(':')?.any { it.equals(expected2, ignoreCase = true) } == true
    }

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

    companion object { private const val PERMISSION_REQUEST_CODE = 1001 }
}