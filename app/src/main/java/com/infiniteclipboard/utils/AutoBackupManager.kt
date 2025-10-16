// 文件: app/src/main/java/com/infiniteclipboard/utils/AutoBackupManager.kt
package com.infiniteclipboard.utils

import android.content.Context
import android.os.Environment
import com.infiniteclipboard.data.ClipboardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AutoBackupManager {

    private const val BACKUP_DIR_NAME = "InfiniteClipboard"
    private const val BACKUP_FILE_NAME = "clipboard_data.json"

    private fun getBackupDir(): File? {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val backupDir = File(documentsDir, BACKUP_DIR_NAME)
        return if (backupDir.exists() || backupDir.mkdirs()) backupDir else null
    }

    private fun getBackupFile(): File? {
        val dir = getBackupDir() ?: return null
        return File(dir, BACKUP_FILE_NAME)
    }

    suspend fun saveBackup(items: List<ClipboardEntity>): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getBackupFile() ?: return@withContext false
            val arr = JSONArray()
            items.forEach { e ->
                val obj = JSONObject()
                obj.put("content", e.content)
                obj.put("timestamp", e.timestamp)
                obj.put("length", e.length)
                arr.put(obj)
            }
            file.writeText(arr.toString(2), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            LogUtils.e("AutoBackup", "保存失败", e)
            false
        }
    }

    suspend fun loadBackup(): List<ClipboardEntity>? = withContext(Dispatchers.IO) {
        try {
            val file = getBackupFile()
            if (file == null || !file.exists()) return@withContext null
            val text = file.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            val list = mutableListOf<ClipboardEntity>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val c = obj.optString("content", "")
                val t = obj.optLong("timestamp", System.currentTimeMillis())
                list.add(ClipboardEntity(content = c, timestamp = t, length = c.length))
            }
            list
        } catch (e: Exception) {
            LogUtils.e("AutoBackup", "加载失败", e)
            null
        }
    }
}