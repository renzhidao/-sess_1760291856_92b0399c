// 文件: app/src/main/java/com/infiniteclipboard/data/ClipboardEntity.kt
package com.infiniteclipboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_items")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val length: Int = content.length
)