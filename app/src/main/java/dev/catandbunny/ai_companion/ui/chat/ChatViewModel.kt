package dev.catandbunny.ai_companion.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catandbunny.ai_companion.data.repository.ChatRepository
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import dev.catandbunny.ai_companion.model.ChatMessage
import dev.catandbunny.ai_companion.utils.HistoryCompressor
import dev.catandbunny.ai_companion.utils.TokenCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val apiKey: String,
    private val getSystemPrompt: () -> String,
    private val getTemperature: () -> Double,
    private val getModel: () -> String,
    private val getHistoryCompressionEnabled: () -> Boolean,
    private val databaseRepository: DatabaseRepository? = null
) : ViewModel() {
    private val repository = ChatRepository(apiKey)
    private val historyCompressor = HistoryCompressor(apiKey)
    
    companion object {
        private const val COMPRESSION_THRESHOLD = 10 // Сжимать каждые 10 сообщений
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Накопленные токены из сжатых сообщений (для сохранения истории токенов)
    private val _accumulatedCompressedTokens = MutableStateFlow(0)
    val accumulatedCompressedTokens: StateFlow<Int> = _accumulatedCompressedTokens.asStateFlow()

    init {
        // Загружаем данные из базы при инициализации
        loadDataFromDatabase()
        
        // Сохраняем сообщения при изменении
        viewModelScope.launch {
            _messages.collect { messages ->
                databaseRepository?.saveMessages(messages)
            }
        }
        
        // Сохраняем состояние разговора при изменении
        viewModelScope.launch {
            _accumulatedCompressedTokens.collect { tokens ->
                databaseRepository?.saveConversationState(tokens)
            }
        }
    }
    
    private fun loadDataFromDatabase() {
        viewModelScope.launch {
            try {
                val savedMessages = databaseRepository?.loadMessages() ?: emptyList()
                if (savedMessages.isNotEmpty()) {
                    _messages.value = savedMessages
                }
                
                val savedState = databaseRepository?.loadConversationState()
                savedState?.let {
                    _accumulatedCompressedTokens.value = it.accumulatedCompressedTokens
                }
            } catch (e: Exception) {
                // Игнорируем ошибки загрузки
            }
        }
    }

    val totalApiTokens: StateFlow<Int> = combine(
        _messages,
        _accumulatedCompressedTokens
    ) { messages, accumulated ->
        calculateTotalApiTokens(messages) + accumulated
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    /**
     * Подсчитывает общее количество API токенов из всех сообщений
     * Учитывает только токены, посчитанные через API
     * 
     * tokensUsed в ResponseMetadata включает все токены запроса (prompt + completion),
     * поэтому суммируем только tokensUsed из ответов бота для получения общего количества токенов
     */
    private fun calculateTotalApiTokens(messages: List<ChatMessage>): Int {
        return messages.sumOf { message ->
            // Используем только tokensUsed из ResponseMetadata ответов бота
            // Это total tokens для каждого запроса, включая prompt и completion
            if (!message.isFromUser && message.responseMetadata != null) {
                message.responseMetadata.tokensUsed
            } else {
                0
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        // Отправляем запрос боту
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            // Проверяем, нужно ли сжать историю перед добавлением нового сообщения
            val currentMessages = _messages.value
            val nonSummaryMessages = currentMessages.filter { !it.isSummary }
            val compressionEnabled = getHistoryCompressionEnabled()
            
            val compressedMessages = if (compressionEnabled && nonSummaryMessages.size >= COMPRESSION_THRESHOLD) {
                // Берем первые COMPRESSION_THRESHOLD не-summary сообщений для сжатия
                // Собираем индексы сообщений для сжатия
                val indicesToCompress = mutableListOf<Int>()
                var count = 0
                for ((index, message) in currentMessages.withIndex()) {
                    if (!message.isSummary && count < COMPRESSION_THRESHOLD) {
                        indicesToCompress.add(index)
                        count++
                    }
                    if (count >= COMPRESSION_THRESHOLD) break
                }
                
                // Получаем сообщения для сжатия
                val messagesToCompress = indicesToCompress.map { currentMessages[it] }
                
                // Создаем summary
                val systemPrompt = getSystemPrompt()
                val model = getModel()
                val summaryResult = historyCompressor.createSummary(messagesToCompress, model)
                
                summaryResult.fold(
                    onSuccess = { summaryResult ->
                        // Подсчитываем токены из сжатых сообщений
                        val compressedTokens = messagesToCompress.sumOf { message ->
                            if (!message.isFromUser && message.responseMetadata != null) {
                                message.responseMetadata.tokensUsed
                            } else {
                                0
                            }
                        }
                        
                        val tokensToAccumulate = compressedTokens + summaryResult.tokensUsed
                        _accumulatedCompressedTokens.value += tokensToAccumulate
                        
                        // Создаем summary сообщение
                        val summaryMessage = ChatMessage(
                            text = summaryResult.summary,
                            isFromUser = false,
                            isSummary = true
                        )
                        // Удаляем сжатые сообщения и вставляем summary на их место
                        val resultList = currentMessages.toMutableList()
                        // Удаляем сообщения в обратном порядке, чтобы индексы не сдвигались
                        indicesToCompress.sortedDescending().forEach { index ->
                            resultList.removeAt(index)
                        }
                        // Вставляем summary на место первого удаленного сообщения
                        val insertIndex = indicesToCompress.minOrNull() ?: 0
                        resultList.add(insertIndex, summaryMessage)
                        resultList
                    },
                    onFailure = {
                        currentMessages
                    }
                )
            } else {
                // Сжатие не требуется
                currentMessages
            }
            
            // Подсчитываем токены для сообщения пользователя
            val manualTokenCount = TokenCounter.countTokens(text)

            // Добавляем сообщение пользователя
            val userMessage = ChatMessage(
                text = text,
                isFromUser = true,
                manualTokenCount = manualTokenCount
            )
            val messagesWithUser = compressedMessages + userMessage
            _messages.value = messagesWithUser
            
            val systemPrompt = getSystemPrompt()
            val temperature = getTemperature()
            val model = getModel()
            val result = repository.sendMessage(text, compressedMessages, systemPrompt, temperature, model)

            result.onSuccess { (botResponse, metadata) ->
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
