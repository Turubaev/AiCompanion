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
    val historyCompressionEnabled: Boolean = true,
    /** Включены ли пуш-уведомления о курсе USD/RUB */
    val currencyNotificationEnabled: Boolean = false,
    /** Интервал запроса курса в минутах (1, 5, 15, 30) */
    val currencyIntervalMinutes: Int = 5,
    /** Telegram chat_id для отправки рекомендаций (например по портфелю) в Telegram */
    val telegramChatId: String = "",
    /** Режим RAG: вопрос → поиск релевантных чанков по индексу → объединение с вопросом → запрос к LLM */
    val ragEnabled: Boolean = false,
    /** Порог релевантности RAG (0.0–1.0): чанки с score ниже не отправляются в контекст */
    val ragMinScore: Double = 0.0,
    /** Использовать reranker (cross-encoder) для переранжирования кандидатов на сервере RAG */
    val ragUseReranker: Boolean = false,
    /** GitHub username для получения ревью PR (привязка к автору PR в CloudBuddy и др.) */
    val githubUsername: String = "",
    /** Email пользователя для контекста поддержки (тикеты, история обращений через CRM MCP) */
    val supportUserEmail: String = "",
    /** Автоматически добавлять контекст поддержки (тикеты, история) в каждый запрос к боту */
    val autoIncludeSupportContext: Boolean = false
)
