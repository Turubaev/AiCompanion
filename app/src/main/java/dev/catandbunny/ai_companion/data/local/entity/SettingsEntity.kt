package dev.catandbunny.ai_companion.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1, // Всегда один экземпляр настроек
    val systemPrompt: String = "",
    val temperature: Double = 0.7,
    val selectedModel: String = "gpt-3.5-turbo",
    val historyCompressionEnabled: Boolean = true
)
