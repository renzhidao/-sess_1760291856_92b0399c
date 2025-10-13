// 文件: app/src/main/java/com/infiniteclipboard/ime/ProxyImeService.kt
package com.infiniteclipboard.ime

import android.content.Context
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LogUtils

class ProxyImeService : InputMethodService() {

    private val prefs by lazy { getSharedPreferences("proxy_ime", Context.MODE_PRIVATE) }
    private val targetImeId: String
        get() = prefs.getString("target_ime_id", "") ?: ""

    private val targetImeLabel: String
        get() = prefs.getString("target_ime_label", "") ?: ""

    private var root: View? = null

    override fun onCreateInputView(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.ime_proxy_view, null, false)
        root = v
        v.findViewById<View>(R.id.btnSwitchNow).setOnClickListener { forwardToTarget() }
        v.post { forwardToTarget() } // 尝试自动转发
        return v
    }

    private fun forwardToTarget() {
        if (targetImeId.isBlank()) {
            LogUtils.d("ProxyIME", "未设置目标键盘，弹出系统选择器")
            showSystemPicker()
            return
        }
        // 读取当前 IME（有些 ROM 允许读取）
        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Throwable) { null }

        if (current == targetImeId) {
            LogUtils.d("ProxyIME", "已在目标键盘: $targetImeLabel")
            return
        }

        // 尝试“切到下一个输入法”（系统允许才会成功）
        val ok = try {
            // InputMethodService 自带的便捷方法
            this.switchToNextInputMethod(false)
        } catch (_: Throwable) { false }

        if (!ok) {
            LogUtils.d("ProxyIME", "直接切换失败，弹出系统选择器")
            showSystemPicker()
            return
        }

        // 切一次后再检查，不对就再切几次（最多循环一圈）
        cycleTowardsTarget(tries = 1, maxTries = getEnabledImeCount().coerceAtLeast(2))
    }

    private fun cycleTowardsTarget(tries: Int, maxTries: Int) {
        if (tries > maxTries) {
            LogUtils.d("ProxyIME", "多次尝试后仍未到达，弹出选择器")
            showSystemPicker()
            return
        }
        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Throwable) { null }

        if (current == targetImeId) {
            LogUtils.d("ProxyIME", "已切换到目标键盘: $targetImeLabel")
            return
        }
        // 再切一步
        val ok = try { this.switchToNextInputMethod(false) } catch (_: Throwable) { false }
        if (!ok) {
            showSystemPicker(); return
        }
        root?.postDelayed({ cycleTowardsTarget(tries + 1, maxTries) }, 30)
    }

    private fun getEnabledImeCount(): Int {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.size
        } catch (_: Throwable) { 2 }
    }

    private fun showSystemPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Throwable) { /* ignore */ }
    }
}