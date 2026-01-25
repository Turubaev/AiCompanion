package dev.catandbunny.ai_companion.data.repository

import android.util.Log
import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.data.local.entity.ChatMessageEntity
import dev.catandbunny.ai_companion.data.local.entity.ConversationStateEntity
import dev.catandbunny.ai_companion.data.local.entity.SettingsEntity
import dev.catandbunny.ai_companion.model.ChatMessage

class DatabaseRepository(private val database: AppDatabase) {
    suspend fun saveMessages(messages: List<ChatMessage>) {
        try {
            Log.d("DatabaseRepository", "=== saveMessages ===")
            Log.d("DatabaseRepository", "Сохраняем ${messages.size} сообщений")
            messages.forEachIndexed { index, message ->
                Log.d("DatabaseRepository", "Сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(150)}...")
            }
            val entities = messages.map { messageToEntity(it) }
            database.chatMessageDao().deleteAllMessages()
            database.chatMessageDao().insertMessages(entities)
            Log.d("DatabaseRepository", "Сообщения успешно сохранены в БД")
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "Ошибка при сохранении сообщений", e)
        }
    }

    suspend fun loadMessages(): List<ChatMessage> {
        return try {
            Log.d("DatabaseRepository", "=== loadMessages ===")
            val entities = database.chatMessageDao().getAllMessagesSync()
            Log.d("DatabaseRepository", "Загружено entities из БД: ${entities.size}")
            val messages = entities.map { entityToMessage(it) }
            Log.d("DatabaseRepository", "Преобразовано в messages: ${messages.size}")
            messages.forEachIndexed { index, message ->
                Log.d("DatabaseRepository", "Загруженное сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(100)}...")
            }
            messages
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "Ошибка при загрузке сообщений", e)
            e.printStackTrace()
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
            Log.d("DatabaseRepository", "=== saveConversationState ===")
            Log.d("DatabaseRepository", "Сохраняем accumulatedCompressedTokens: $accumulatedCompressedTokens")
            val state = ConversationStateEntity(
                id = 1,
                accumulatedCompressedTokens = accumulatedCompressedTokens
            )
            database.conversationStateDao().insertConversationState(state)
            Log.d("DatabaseRepository", "Состояние разговора успешно сохранено в БД")
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "Ошибка при сохранении состояния разговора", e)
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
