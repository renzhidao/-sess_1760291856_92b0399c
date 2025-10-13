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
        // 不弹任何系统面板，返回一个极简不可见视图
        return View(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        val prefs = getSharedPreferences("proxy_ime", Context.MODE_PRIVATE)
        val targetImeId = prefs.getString("target_ime_id", null)
        val targetAppComponent = prefs.getString("target_app_component", null)
        val targetAppPkg = prefs.getString("target_app_pkg", null)

        var acted = false

        // 1) 如果设置了目标键盘，尝试静默切换
        if (!targetImeId.isNullOrEmpty()) {
            acted = trySwitchToIme(targetImeId)
        }

        // 2) 切换失败或未设置键盘：尝试打开用户指定应用（组件优先，再按包名）
        if (!acted) {
            acted = tryLaunchComponent(targetAppComponent) || tryLaunchPackage(targetAppPkg)
        }

        // 3) 都没有配置：打开主界面让用户选择
        if (!acted) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("from_proxy_ime", true)
                }
                startActivity(intent)
            } catch (_: Throwable) { }
        }

        // 隐藏自身
        requestHideSelf(0)
    }

    private fun trySwitchToIme(imeId: String): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val tokenField = InputMethodService::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            val token = tokenField.get(this) as? IBinder ?: return false
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

    private fun tryLaunchComponent(flat: String?): Boolean {
        if (flat.isNullOrEmpty()) return false
        val comp = ComponentName.unflattenFromString(flat) ?: return false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = comp
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return try {
            startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryLaunchPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}