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
)package com.infiniteclipboard.data

import kotlinx.coroutines.flow.Flow

class ClipboardRepository(private val dao: ClipboardDao) {
    
    val allItems: Flow<List<ClipboardEntity>> = dao.getAllItems()
    
    val itemCount: Flow<Int> = dao.getItemCount()
    
    fun searchItems(query: String): Flow<List<ClipboardEntity>> {
        return dao.searchItems(query)
    }
    
    suspend fun insertItem(content: String): Long {
        // 避免重复插入相同内容
        val existing = dao.findByContent(content)
        if (existing != null) {
            return existing.id
        }
        
        val item = ClipboardEntity(
            content = content,
            timestamp = System.currentTimeMillis(),
            length = content.length
        )
        return dao.insertItem(item)
    }
    
    suspend fun deleteItem(item: ClipboardEntity) {
        dao.deleteItem(item)
    }
    
    suspend fun deleteAll() {
        dao.deleteAll()
    }
}package com.infiniteclipboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipboardEntity::class], version = 1, exportSchema = false)
abstract class ClipboardDatabase : RoomDatabase() {
    
    abstract fun clipboardDao(): ClipboardDao
    
    companion object {
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null
        
        fun getDatabase(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClipboardDatabase::class.java,
                    "clipboard_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}package com.infiniteclipboard.data

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
}