// ClipboardApplication - 应用程序主类
package com.infiniteclipboard

import android.app.Application
import com.infiniteclipboard.data.ClipboardDatabase
import com.infiniteclipboard.data.ClipboardRepository

class ClipboardApplication : Application() {
    
    val database by lazy { ClipboardDatabase.getDatabase(this) }
    val repository by lazy { ClipboardRepository(database.clipboardDao()) }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        lateinit var instance: ClipboardApplication
            private set
    }
}