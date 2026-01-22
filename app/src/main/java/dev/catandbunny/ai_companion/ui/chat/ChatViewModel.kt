package dev.catandbunny.ai_companion.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catandbunny.ai_companion.data.repository.ChatRepository
import dev.catandbunny.ai_companion.model.ChatMessage
import dev.catandbunny.ai_companion.utils.TokenCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val apiKey: String,
    private val getSystemPrompt: () -> String,
    private val getTemperature: () -> Double,
    private val getModel: () -> String
) : ViewModel() {
    private val repository = ChatRepository(apiKey)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        // Подсчитываем токены для сообщения пользователя
        val manualTokenCount = TokenCounter.countTokens(text)

        // Добавляем сообщение пользователя
        val userMessage = ChatMessage(
            text = text,
            isFromUser = true,
            manualTokenCount = manualTokenCount
        )
        _messages.value = _messages.value + userMessage

        // Отправляем запрос боту
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val systemPrompt = getSystemPrompt()
            val temperature = getTemperature()
            val model = getModel()
            val result = repository.sendMessage(text, _messages.value, systemPrompt, temperature, model)

            result.onSuccess { (botResponse, metadata) ->
                Log.d("ChatViewModel", "=== Создание ChatMessage ===")
                Log.d("ChatViewModel", "botResponse length: ${botResponse.length}")
                Log.d("ChatViewModel", "metadata.isRequirementsResponse: ${metadata.isRequirementsResponse}")
                Log.d("ChatViewModel", "metadata.questionText: ${metadata.questionText}")
                Log.d("ChatViewModel", "metadata.requirements: ${if (metadata.requirements != null) "present (${metadata.requirements?.length} chars)" else "null"}")
                Log.d("ChatViewModel", "metadata.recommendations: ${if (metadata.recommendations != null) "present (${metadata.recommendations?.length} chars)" else "null"}")
                Log.d("ChatViewModel", "metadata.confidence: ${metadata.confidence}")
                
                // Вычисляем токены текущего запроса пользователя от API
                val currentPromptTokens = metadata.promptTokens ?: 0
                val previousPromptTokens = _messages.value
                    .lastOrNull { !it.isFromUser && it.responseMetadata?.promptTokens != null }
                    ?.responseMetadata?.promptTokens ?: 0
                
                // Токены текущего запроса пользователя = разница между текущим и предыдущим promptTokens
                val userApiTokenCount = if (currentPromptTokens > previousPromptTokens && previousPromptTokens > 0) {
                    // Для последующих запросов: разница между текущим и предыдущим promptTokens
                    currentPromptTokens - previousPromptTokens
                } else {
                    // Для первого запроса: вычитаем токены системного промпта
                    val systemPromptTokens = TokenCounter.countTokens(systemPrompt)
                    (currentPromptTokens - systemPromptTokens).coerceAtLeast(0)
                }
                
                // Обновляем последнее сообщение пользователя с apiTokenCount
                val updatedMessages = _messages.value.toMutableList()
                val lastUserMessageIndex = updatedMessages.indexOfLast { it.isFromUser }
                if (lastUserMessageIndex >= 0) {
                    val lastUserMessage = updatedMessages[lastUserMessageIndex]
                    updatedMessages[lastUserMessageIndex] = lastUserMessage.copy(
                        apiTokenCount = userApiTokenCount
                    )
                }
                
                val botMessage = ChatMessage(
                    text = botResponse,
                    isFromUser = false,
                    responseMetadata = metadata
                )
                
                Log.d("ChatViewModel", "=== ChatMessage создан ===")
                Log.d("ChatViewModel", "botMessage.responseMetadata?.isRequirementsResponse: ${botMessage.responseMetadata?.isRequirementsResponse}")
                Log.d("ChatViewModel", "userApiTokenCount: $userApiTokenCount (current: $currentPromptTokens, previous: $previousPromptTokens)")
                
                // Обновляем список сообщений: обновленное сообщение пользователя + ответ бота
                _messages.value = updatedMessages + botMessage
                _isLoading.value = false
            }.onFailure { exception ->
                _error.value = exception.message ?: "Произошла ошибка"
                _isLoading.value = false
                
                // Добавляем сообщение об ошибке
                val errorMessage = ChatMessage(
                    text = "Извините, произошла ошибка: ${exception.message}",
                    isFromUser = false
                )
                _messages.value = _messages.value + errorMessage
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
