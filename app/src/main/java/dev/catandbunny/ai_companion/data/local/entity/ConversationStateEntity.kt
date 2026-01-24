package dev.catandbunny.ai_companion.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_state")
data class ConversationStateEntity(
    @PrimaryKey
    val id: Int = 1, // Всегда один экземпляр состояния
    val accumulatedCompressedTokens: Int = 0
)
