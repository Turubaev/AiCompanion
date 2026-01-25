package dev.catandbunny.ai_companion.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import dev.catandbunny.ai_companion.data.local.converter.ResponseMetadataConverter
import dev.catandbunny.ai_companion.model.ResponseMetadata

@Entity(tableName = "chat_messages")
@TypeConverters(ResponseMetadataConverter::class)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long,
    val responseMetadata: ResponseMetadata? = null, // Room автоматически конвертирует через TypeConverter
    val manualTokenCount: Int? = null,
    val apiTokenCount: Int? = null,
    val isSummary: Boolean = false
)
