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
    suspend fun insertItem(item: ClipboardEntity): Long

    @Delete
    suspend fun deleteItem(item: ClipboardEntity)

    @Query("DELETE FROM clipboard_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM clipboard_items")
    fun getItemCount(): Flow<Int>

    @Query("SELECT * FROM clipboard_items WHERE content = :content LIMIT 1")
    suspend fun findByContent(content: String): ClipboardEntity?

    // 导出用：一次性取出全部数据（非 Flow）
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ClipboardEntity>
}