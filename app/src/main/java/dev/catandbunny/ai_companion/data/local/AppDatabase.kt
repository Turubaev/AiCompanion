package dev.catandbunny.ai_companion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.catandbunny.ai_companion.data.local.converter.ResponseMetadataConverter
import dev.catandbunny.ai_companion.data.local.dao.ChatMessageDao
import dev.catandbunny.ai_companion.data.local.dao.ConversationStateDao
import dev.catandbunny.ai_companion.data.local.dao.SettingsDao
import dev.catandbunny.ai_companion.data.local.entity.ChatMessageEntity
import dev.catandbunny.ai_companion.data.local.entity.ConversationStateEntity
import dev.catandbunny.ai_companion.data.local.entity.SettingsEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        SettingsEntity::class,
        ConversationStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(ResponseMetadataConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun conversationStateDao(): ConversationStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_companion_database"
                )
                    .fallbackToDestructiveMigration() // Для разработки - удаляет данные при изменении схемы
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
