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
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ActivityMainBinding
import com.infiniteclipboard.service.ClipboardMonitorService
import com.infiniteclipboard.utils.LogUtils
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.lifecycleScope
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
    private val prefs by lazy { getSharedPreferences("proxy_ime", Context.MODE_PRIVATE) }

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

        LogUtils.init(this)
        LogUtils.d("MainActivity", "应用启动")

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_all -> { showClearAllDialog(); true }
        R.id.action_export -> { createDoc.launch("clipboard_backup.json"); true }
        R.id.action_import -> { openDoc.launch(arrayOf("application/json")); true }
        R.id.action_set_proxy_keyboard -> { showProxyKeyboardPicker(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showProxyKeyboardPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val list = imm.enabledInputMethodList
        val currentPkg = packageName
        val displayList = list.filter { it.packageName != currentPkg }
        if (displayList.isEmpty()) {
            Toast.makeText(this, "未找到可中转的键盘，请先启用其它键盘", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            return
        }
        val labels = displayList.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_keyboard_title))
            .setItems(labels) { _, which ->
                val info: InputMethodInfo = displayList[which]
                val id = info.id
                val label = labels[which]
                prefs.edit().putString("target_ime_id", id)
                    .putString("target_ime_label", label)
                    .apply()
                Toast.makeText(this, getString(R.string.proxy_keyboard_saved, label), Toast.LENGTH_LONG).show()
                // 引导用户把“剪切板代理键盘”设为默认
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // 其余代码保持不变（列表、搜索、导入导出、权限等）
    // ... 省略：你的现有实现（为简洁未重复粘贴）
    // 请保留 ClipboardMonitorService.start(this) 以通过测试
}