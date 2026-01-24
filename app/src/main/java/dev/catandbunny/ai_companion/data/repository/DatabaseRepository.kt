package dev.catandbunny.ai_companion.data.repository

import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.data.local.entity.ChatMessageEntity
import dev.catandbunny.ai_companion.data.local.entity.ConversationStateEntity
import dev.catandbunny.ai_companion.data.local.entity.SettingsEntity
import dev.catandbunny.ai_companion.model.ChatMessage

class DatabaseRepository(private val database: AppDatabase) {
    suspend fun saveMessages(messages: List<ChatMessage>) {
        try {
            val entities = messages.map { messageToEntity(it) }
            database.chatMessageDao().deleteAllMessages()
            database.chatMessageDao().insertMessages(entities)
        } catch (e: Exception) {
            // Игнорируем ошибки сохранения
        }
    }

    suspend fun loadMessages(): List<ChatMessage> {
        return try {
            val entities = database.chatMessageDao().getAllMessagesSync()
            entities.map { entityToMessage(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveSettings(
        systemPrompt: String,
        temperature: Double,
        selectedModel: String,
        historyCompressionEnabled: Boolean
    ) {
        try {
            val settings = SettingsEntity(
                id = 1,
                systemPrompt = systemPrompt,
                temperature = temperature,
                selectedModel = selectedModel,
                historyCompressionEnabled = historyCompressionEnabled
            )
            database.settingsDao().insertSettings(settings)
        } catch (e: Exception) {
            // Игнорируем ошибки сохранения
        }
    }

    suspend fun loadSettings(): SettingsEntity? {
        return try {
            database.settingsDao().getSettingsSync()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveConversationState(accumulatedCompressedTokens: Int) {
        try {
            val state = ConversationStateEntity(
                id = 1,
                accumulatedCompressedTokens = accumulatedCompressedTokens
            )
            database.conversationStateDao().insertConversationState(state)
        } catch (e: Exception) {
            // Игнорируем ошибки сохранения
        }
    }

    suspend fun loadConversationState(): ConversationStateEntity? {
        return try {
            database.conversationStateDao().getConversationStateSync()
        } catch (e: Exception) {
            null
        }
    }

    private fun messageToEntity(message: ChatMessage): ChatMessageEntity {
        return ChatMessageEntity(
            text = message.text,
            isFromUser = message.isFromUser,
            timestamp = message.timestamp,
            responseMetadata = message.responseMetadata,
            manualTokenCount = message.manualTokenCount,
            apiTokenCount = message.apiTokenCount,
            isSummary = message.isSummary
        )
    }

    private fun entityToMessage(entity: ChatMessageEntity): ChatMessage {
        return ChatMessage(
            text = entity.text,
            isFromUser = entity.isFromUser,
            timestamp = entity.timestamp,
            responseMetadata = entity.responseMetadata,
            manualTokenCount = entity.manualTokenCount,
            apiTokenCount = entity.apiTokenCount,
            isSummary = entity.isSummary
        )
    }
}
