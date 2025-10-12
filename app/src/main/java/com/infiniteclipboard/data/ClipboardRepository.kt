// 文件: app/src/main/java/com/infiniteclipboard/data/ClipboardRepository.kt
package com.infiniteclipboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

class ClipboardRepository(private val dao: ClipboardDao) {

    val allItems: Flow<List<ClipboardEntity>> = dao.getAllItems()

    val itemCount: Flow<Int> = dao.getItemCount()

    fun searchItems(query: String): Flow<List<ClipboardEntity>> {
        return dao.searchItems(query)
    }

    // 保持 suspend，对应内部在 IO 线程调用非 suspend DAO
    suspend fun insertItem(content: String): Long = withContext(Dispatchers.IO) {
        val existing = dao.findByContent(content)
        if (existing != null) {
            existing.id
        } else {
            val item = ClipboardEntity(
                content = content,
                timestamp = System.currentTimeMillis(),
                length = content.length
            )
            dao.insertItem(item)
        }
    }

    suspend fun deleteItem(item: ClipboardEntity) = withContext(Dispatchers.IO) {
        dao.deleteItem(item)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    // 导出全部
    suspend fun getAllOnce(): List<ClipboardEntity> = withContext(Dispatchers.IO) {
        dao.getAllOnce()
    }

    // 导入：保持原时间戳，按内容插入（由上层决定是否去重）
    suspend fun importItems(items: List<ClipboardEntity>) = withContext(Dispatchers.IO) {
        for (it in items) {
            dao.insertItem(
                ClipboardEntity(
                    id = 0, // 让 Room 自增
                    content = it.content,
                    timestamp = it.timestamp,
                    length = it.content.length
                )
            )
        }
    }
}