// 文件: app/src/main/java/com/infiniteclipboard/data/ClipboardDatabase.kt
package com.infiniteclipboard.data

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
}