// 文件: app/src/main/java/com/infiniteclipboard/ClipboardApplication.kt
// ClipboardApplication - 应用程序主类（区分主进程与 :shizuku 进程的初始化）
package com.infiniteclipboard

import android.app.Application
import android.os.Build
import com.infiniteclipboard.data.ClipboardDatabase
import com.infiniteclipboard.data.ClipboardRepository
import com.infiniteclipboard.service.ShizukuClipboardMonitor
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class ClipboardApplication : Application() {

    val database by lazy { ClipboardDatabase.getDatabase(this) }
    val repository by lazy { ClipboardRepository(database.clipboardDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 放开隐藏 API 限制（两种进程都需要）
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/")
        } catch (_: Throwable) { }

        // 仅主进程初始化 Shizuku 监听，避免 :shizuku 远程进程递归初始化导致绑定假死
        val proc = currentProcessNameSafe()
        val isShizukuProc = proc.endsWith(":shizuku")
        if (!isShizukuProc) {
            try { ShizukuClipboardMonitor.init(this) } catch (_: Throwable) { }
        }
    }

    private fun currentProcessNameSafe(): String {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                BufferedReader(FileReader(File("/proc/self/cmdline"))).use { br ->
                    val raw = br.readLine() ?: ""
                    raw.trim { it <= ' ' }
                }
            }
        } catch (_: Throwable) {
            packageName
        }
    }

    companion object {
        lateinit 