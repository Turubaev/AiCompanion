package dev.catandbunny.ai_companion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 4,
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN currencyNotificationEnabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN currencyIntervalMinutes INTEGER NOT NULL DEFAULT 5"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN telegramChatId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN ragEnabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_companion_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
