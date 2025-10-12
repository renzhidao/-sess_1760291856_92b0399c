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

    // 改为非 suspend，返回 Long（Room 支持的返回类型）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItem(item: ClipboardEntity): Long

    // 改为非 suspend，返回 Int（删除行数）
    @Delete
    fun deleteItem(item: ClipboardEntity): Int

    // 改为非 suspend，返回 Int（删除行数）
    @Query("DELETE FROM clipboard_items")
    fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM clipboard_items")
    fun getItemCount(): Flow<Int>

    // 改为非 suspend（查询单条）
    @Query("SELECT * FROM clipboard_items WHERE content = :content LIMIT 1")
    fun findByContent(content: String): ClipboardEntity?

    // 导出用：一次性取出全部数据（非 Flow，非 suspend）
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllOnce(): List<ClipboardEntity>
}