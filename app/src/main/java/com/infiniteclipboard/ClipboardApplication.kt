// 文件: app/src/main/java/com/infiniteclipboard/ClipboardApplication.kt
// ClipboardApplication - 应用程序主类
package com.infiniteclipboard

import android.app.Application
import com.infiniteclipboard.data.ClipboardDatabase
import com.infiniteclipboard.data.ClipboardRepository
import com.infiniteclipboard.service.ShizukuClipboardMonitor

class ClipboardApplication : Application() {

    val database by lazy { ClipboardDatabase.getDatabase(this) }
    val repository by lazy { ClipboardRepository(database.clipboardDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化 Shizuku 监听器（Binder 收到/断开、权限回调），修复“未连接”的判定与自动启动
        try { ShizukuClipboardMonitor.init(this) } catch (_: Throwable) { }
    }

    companion object {
        lateinit var instance: ClipboardApplication
            private set
    }
}