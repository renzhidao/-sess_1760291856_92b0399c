// 文件: app/src/main/java/com/infiniteclipboard/ime/ProxyImeService.kt
package com.infiniteclipboard.ime

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService

class ProxyImeService : InputMethodService() {

    override fun onCreateInputView(): View {
        // 返回一个极简、不可见的视图，立刻弹出系统键盘选择器并隐藏自己
        val v = View(this)
        v.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            try { imm.showInputMethodPicker() } catch (_: Throwable) {}
            requestHideSelf(0)
        }
        return v
    }
}