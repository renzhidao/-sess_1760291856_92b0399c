// 文件: app/src/main/java/com/infiniteclipboard/data/ClipboardDao.kt
package com.infiniteclipboard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchItems(query: String): Flow<List<ClipboardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItem(item: ClipboardEntity): Long

    @Delete
    fun deleteItem(item: ClipboardEntity): Int

    @Query("DELETE FROM clipboard_items")
    fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM clipboard_items")
    fun getItemCount(): Flow<Int>

    @Query("SELECT * FROM clipboard_items WHERE content = :content LIMIT 1")
    fun findByContent(content: String): ClipboardEntity?

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllOnce(): List<ClipboardEntity>
}