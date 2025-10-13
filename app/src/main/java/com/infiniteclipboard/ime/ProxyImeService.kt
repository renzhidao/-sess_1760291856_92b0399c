===== ./app/src/main/java/com/infiniteclipboard/ime/ProxyImeService.kt =====
// 文件: app/src/main/java/com/infiniteclipboard/ime/ProxyImeService.kt
package com.infiniteclipboard.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.infiniteclipboard.ui.MainActivity

class ProxyImeService : InputMethodService() {

    override fun onCreateInputView(): View {
        // 不弹任何面板，返回一个极简不可见视图
        return View(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 读取用户偏好：优先切换到指定键盘；否则打开指定应用；再否则打开主界面
        val prefs = getSharedPreferences("proxy_ime", Context.MODE_PRIVATE)
        val targetImeId = prefs.getString("target_ime_id", null)
        val targetAppComponent = prefs.getString("target_app_component", null)
        val targetAppPkg = prefs.getString("target_app_pkg", null)

        if (!targetImeId.isNullOrEmpty()) {
            trySwitchToIme(targetImeId)
            requestHideSelf(0)
            return
        }

        var launched = false
        // 指定组件优先
        if (!targetAppComponent.isNullOrEmpty()) {
            val comp = ComponentName.unflattenFromString(targetAppComponent)
            if (comp != null) {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = comp
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                try {
                    startActivity(intent)
                    launched = true
                } catch (_: Throwable) { }
            }
        }
        // 仅包名
        if (!launched && !targetAppPkg.isNullOrEmpty()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetAppPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launchIntent)
                    launched = true
                } catch (_: Throwable) { }
            }
        }

        // 都没有配置/启动失败：回到主界面让用户选择
        if (!launched) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("from_proxy_ime", true)
                }
                startActivity(intent)
            } catch (_: Throwable) { }
        }

        requestHideSelf(0)
    }

    private fun trySwitchToIme(imeId: String): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            // 通过反射调用 setInputMethod(token, id)；如果失败则返回 false
            val tokenField = InputMethodService::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            val token = tokenField.get(this) as? IBinder
            val method = InputMethodManager::class.java.getMethod(
                "setInputMethod",
                IBinder::class.java,
                String::class.java
            )
            method.invoke(imm, token, imeId)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

===== ./app/src/main/java/com/infiniteclipboard/ui/MainActivity.kt =====
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
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
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
import com.infiniteclipboard.service.ClipboardAccessibilityService
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

        // 启动前台服务（满足测试、保持存活）
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
        R.id.action_set_quick_app -> { showQuickAppPicker(); true }
        else -> super.onOptionsItemSelected(item)
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
        // 可见按钮：选择目标键盘（只存储偏好，不做静默切换）
        binding.btnKeyboard.setOnClickListener { showProxyKeyboardPicker() }
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
                prefs.edit()
                    .putString("target_ime_id", id)
                    .putString("target_ime_label", label)
                    .apply()
                Toast.makeText(this, getString(R.string.proxy_keyboard_saved, label), Toast.LENGTH_LONG).show()
                // 引导用户把“剪切板代理键盘”设为默认（一次性）
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQuickAppPicker() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        if (activities.isEmpty()) {
            Toast.makeText(this, "未找到可启动的应用", Toast.LENGTH_LONG).show()
            return
        }
        val sorted = activities.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val labels = sorted.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_quick_app_title))
            .setItems(labels) { _, which ->
                val ri = sorted[which]
                val comp = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                prefs.edit()
                    .putString("target_app_component", comp.flattenToString())
                    .putString("target_app_label", labels[which])
                    .apply()
                Toast.makeText(this, getString(R.string.quick_app_saved, labels[which]), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object { private const val PERMISSION_REQUEST_CODE = 1001 }
}

===== ./app/src/main/res/values/strings.xml =====
<?xml version="1.0" encoding="utf-8"?>
<!-- 文件: app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">无限剪切板</string>
    <string name="notification_channel_name">剪切板监控</string>
    <string name="notification_channel_desc">监控系统剪切板变化</string>
    <string name="notification_title">剪切板服务运行中</string>
    <string name="notification_content">点击查看剪切板历史</string>
    <string name="clipboard_empty">剪切板为空</string>
    <string name="copied_to_clipboard">已复制到剪切板</string>
    <string name="paste">粘贴</string>
    <string name="copy">复制</string>
    <string name="delete">删除</string>
    <string name="share">分享</string>
    <string name="clear_all">清空全部</string>
    <string name="search_hint">搜索剪切板内容...</string>
    <string name="item_count">共 %d 条</string>
    <string name="permission_required">需要通知权限</string>
    <string name="overlay_permission_required">需要悬浮窗权限</string>

    <!-- 通知快捷操作 -->
    <string name="notification_action_pause">暂停监听</string>
    <string name="notification_action_resume">继续监听</string>
    <string name="notification_action_clear_all">清空全部</string>
    <string name="notification_paused">剪切板监听已暂停</string>

    <!-- 导入导出 -->
    <string name="action_export">导出</string>
    <string name="action_import">导入</string>
    <string name="export_success">导出成功</string>
    <string name="export_failed">导出失败</string>
    <string name="import_success">导入完成：%1$d 条</string>
    <string name="import_failed">导入失败</string>

    <!-- 日志页 -->
    <string name="log_viewer">日志</string>
    <string name="log_viewer_title">运行日志</string>
    <string name="log_refresh">刷新</string>
    <string name="log_copy">复制</string>
    <string name="log_clear">清空</string>
    <string name="log_refreshed">日志已刷新</string>
    <string name="log_copied">日志已复制</string>
    <string name="log_cleared">日志已清空</string>
    <string name="log_clear_title">清空日志</string>
    <string name="log_clear_message">确定要清空所有日志吗？</string>

    <!-- 通用 -->
    <string name="confirm">确定</string>
    <string name="cancel">取消</string>
    <string name="go_settings">去设置</string>

    <!-- 无障碍 -->
    <string name="accessibility_permission_title">需要辅助功能权限</string>
    <string name="accessibility_permission_message">为了在后台监听剪切板，需要开启辅助功能权限。\n\n请在设置中找到“无限剪切板”并开启。</string>
    <string name="accessibility_service_description">用于监听系统剪切板变化，自动保存复制的内容。不会收集任何隐私信息。</string>

    <!-- 代理键盘 -->
    <string name="ime_proxy_name">剪切板代理键盘</string>
    <string name="ime_subtype_label">代理</string>
    <string name="ime_switch_now">切换</string>
    <string name="set_proxy_keyboard">设置中转键盘</string>
    <string name="pick_keyboard_title">选择要中转的键盘</string>
    <string name="proxy_keyboard_saved">已设置为：%1$s</string>

    <!-- 快捷应用（供代理键盘唤起时直接打开） -->
    <string name="set_quick_app">设置快捷应用</string>
    <string name="pick_quick_app_title">选择快捷应用</string>
    <string name="quick_app_saved">已设置快捷应用：%1$s</string>
</resources>

===== ./app/src/main/res/menu/menu_main.xml =====
<?xml version="1.0" encoding="utf-8"?>
<!-- 文件: app/src/main/res/menu/menu_main.xml -->
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/action_clear_all"
        android:title="@string/clear_all"
        android:icon="@drawable/ic_clear_all"
        android:showAsAction="ifRoom|withText" />
    <item
        android:id="@+id/action_export"
        android:title="@string/action_export"
        android:icon="@drawable/ic_export"
        android:showAsAction="never|withText" />
    <item
        android:id="@+id/action_import"
        android:title="@string/action_import"
        android:icon="@drawable/ic_import"
        android:showAsAction="never|withText" />
    <item
        android:id="@+id/action_set_proxy_keyboard"
        android:title="@string/set_proxy_keyboard"
        android:showAsAction="never|withText" />
    <item
        android:id="@+id/action_set_quick_app"
        android:title="@string/set_quick_app"
        android:showAsAction="never|withText" />
</menu>