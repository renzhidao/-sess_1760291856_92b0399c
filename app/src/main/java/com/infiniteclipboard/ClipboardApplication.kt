// 文件: app/src/main/java/com/infiniteclipboard/ClipboardApplication.kt
// ClipboardApplication - 应用程序主类
package com.infiniteclipboard

import android.app.Application
import com.infiniteclipboard.data.ClipboardDatabase
import com.infiniteclipboard.data.ClipboardRepository
import com.infiniteclipboard.service.ShizukuClipboardMonitor
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ClipboardApplication : Application() {

    val database by lazy { ClipboardDatabase.getDatabase(this) }
    val repository by lazy { ClipboardRepository(database.clipboardDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 放开隐藏 API 限制（允许反射系统隐藏类/方法：IClipboard/AttributionSource 等）
        try {
            // 宽松模式：放开 android.* 下的隐藏API
            HiddenApiBypass.addHiddenApiExemptions("Landroid/")
        } catch (_: Throwable) { }

        // 初始化 Shizuku 监听器
        try { ShizukuClipboardMonitor.init(this) } catch (_: Throwable) { }
    }

    companion object {
        lateinit var instance: ClipboardApplication
            private set
    }
}