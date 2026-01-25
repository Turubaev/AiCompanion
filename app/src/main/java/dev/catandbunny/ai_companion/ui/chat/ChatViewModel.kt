package dev.catandbunny.ai_companion.ui.chat

import android.util.Log
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
        loadDataFromDatabase()
        
        viewModelScope.launch {
            _accumulatedCompressedTokens.collect { tokens ->
                databaseRepository?.saveConversationState(tokens)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d("ChatViewModel", "=== onCleared вызван ===")
        // Сообщения уже сохранены в БД при отправке/получении, 
        // но на всякий случай сохраняем текущее состояние
        viewModelScope.launch {
            databaseRepository?.saveMessages(_messages.value)
        }
    }
    
    fun saveHistoryOnAppPause() {
        Log.d("ChatViewModel", "=== saveHistoryOnAppPause вызван ===")
        // Сообщения уже сохранены в БД при отправке/получении,
        // но на всякий случай сохраняем текущее состояние
        viewModelScope.launch {
            databaseRepository?.saveMessages(_messages.value)
        }
    }
    
    private fun loadDataFromDatabase() {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "=== loadDataFromDatabase НАЧАЛО ===")
                Log.d("ChatViewModel", "databaseRepository is null: ${databaseRepository == null}")
                
                val savedMessages = databaseRepository?.loadMessages() ?: emptyList()
                Log.d("ChatViewModel", "Загружено сообщений из БД: ${savedMessages.size}")
                
                if (savedMessages.isEmpty()) {
                    Log.d("ChatViewModel", "БД пуста, начинаем с чистого листа")
                    _messages.value = emptyList()
                    return@launch
                }
                
                savedMessages.forEachIndexed { index, message ->
                    Log.d("ChatViewModel", "Сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(100)}...")
                }
                
                val savedState = databaseRepository?.loadConversationState()
                savedState?.let {
                    _accumulatedCompressedTokens.value = it.accumulatedCompressedTokens
                    Log.d("ChatViewModel", "Загружено состояние: accumulatedTokens=${it.accumulatedCompressedTokens}")
                }
                
                // При старте создаем саммари из всех сообщений
                val nonSummaryMessages = savedMessages.filter { !it.isSummary }
                Log.d("ChatViewModel", "Не-summary сообщений: ${nonSummaryMessages.size}, всего сообщений: ${savedMessages.size}")
                
                if (nonSummaryMessages.isNotEmpty()) {
                    Log.d("ChatViewModel", "Найдено ${nonSummaryMessages.size} не-summary сообщений, создаем саммари")
                    val systemPrompt = getSystemPrompt()
                    val model = getModel()
                    Log.d("ChatViewModel", "Вызываем historyCompressor.createSummary с ${nonSummaryMessages.size} сообщениями")
                    val summaryResult = historyCompressor.createSummary(nonSummaryMessages, model)
                    
                    summaryResult.fold(
                        onSuccess = { result ->
                            Log.d("ChatViewModel", "Саммари успешно создано, длина: ${result.summary.length}, первые 200 символов: ${result.summary.take(200)}...")
                            val compressedTokens = nonSummaryMessages.sumOf { message ->
                                if (!message.isFromUser && message.responseMetadata != null) {
                                    message.responseMetadata.tokensUsed
                                } else {
                                    0
                                }
                            }
                            
                            val tokensToAccumulate = compressedTokens + result.tokensUsed
                            val newAccumulatedTokens = _accumulatedCompressedTokens.value + tokensToAccumulate
                            _accumulatedCompressedTokens.value = newAccumulatedTokens
                            
                            val summaryMessage = ChatMessage(
                                text = result.summary,
                                isFromUser = false,
                                isSummary = true
                            )
                            
                            // Сохраняем только саммари в БД (удаляя все старые сообщения)
                            Log.d("ChatViewModel", "Сохраняем саммари в БД, удаляя старые сообщения")
                            databaseRepository?.saveMessages(listOf(summaryMessage))
                            databaseRepository?.saveConversationState(newAccumulatedTokens)
                            Log.d("ChatViewModel", "Саммари сохранено в БД")
                            
                            // Показываем саммари в UI
                            _messages.value = listOf(summaryMessage)
                            Log.d("ChatViewModel", "Саммари отображено в UI, _messages.value.size=${_messages.value.size}")
                        },
                        onFailure = { error ->
                            Log.e("ChatViewModel", "Ошибка при создании саммари при старте", error)
                            error.printStackTrace()
                            // В случае ошибки показываем существующие сообщения
                            val existingSummary = savedMessages.filter { it.isSummary }
                            if (existingSummary.isNotEmpty()) {
                                _messages.value = existingSummary
                                Log.d("ChatViewModel", "Показаны существующие саммари из-за ошибки: ${existingSummary.size}")
                            } else {
                                _messages.value = emptyList()
                                Log.d("ChatViewModel", "Нет саммари, показываем пустой список")
                            }
                        }
                    )
                } else {
                    // Если есть только саммари, показываем его
                    val existingSummary = savedMessages.filter { it.isSummary }
                    if (existingSummary.isNotEmpty()) {
                        _messages.value = existingSummary
                        Log.d("ChatViewModel", "Показано существующее саммари: ${existingSummary.size}, текст: ${existingSummary.first().text.take(200)}...")
                    } else {
                        _messages.value = emptyList()
                        Log.d("ChatViewModel", "Нет сообщений в БД (ни саммари, ни обычных)")
                    }
                }
                Log.d("ChatViewModel", "=== loadDataFromDatabase КОНЕЦ ===")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка при загрузке данных из БД", e)
                e.printStackTrace()
                _messages.value = emptyList()
            }
        }
    }
    
    private fun compressAndSaveHistory() {
        // Резервный метод для сохранения текущих сообщений
        // Обычно сообщения уже сохранены в БД при отправке/получении
        Log.d("ChatViewModel", "=== compressAndSaveHistory (резервное сохранение) ===")
        viewModelScope.launch {
            try {
                databaseRepository?.saveMessages(_messages.value)
                Log.d("ChatViewModel", "Резервное сохранение завершено: ${_messages.value.size} сообщений")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка в compressAndSaveHistory", e)
            }
        }
    }
    
    fun createNewChat() {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "=== createNewChat ===")
                val currentMessages = _messages.value
                Log.d("ChatViewModel", "Текущие сообщения: ${currentMessages.size}")
                currentMessages.forEachIndexed { index, message ->
                    Log.d("ChatViewModel", "Текущее сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(100)}...")
                }
                
                val nonSummaryMessages = currentMessages.filter { !it.isSummary }
                Log.d("ChatViewModel", "Не-summary сообщений: ${nonSummaryMessages.size}")
                
                if (nonSummaryMessages.isNotEmpty()) {
                    val systemPrompt = getSystemPrompt()
                    val model = getModel()
                    Log.d("ChatViewModel", "Сжимаем ${nonSummaryMessages.size} не-summary сообщений")
                    val summaryResult = historyCompressor.createSummary(nonSummaryMessages, model)
                    
                    summaryResult.fold(
                        onSuccess = { result ->
                            val compressedTokens = nonSummaryMessages.sumOf { message ->
                                if (!message.isFromUser && message.responseMetadata != null) {
                                    message.responseMetadata.tokensUsed
                                } else {
                                    0
                                }
                            }
                            
                            val tokensToAccumulate = compressedTokens + result.tokensUsed
                            val newAccumulatedTokens = _accumulatedCompressedTokens.value + tokensToAccumulate
                            _accumulatedCompressedTokens.value = newAccumulatedTokens
                            
                            val summaryMessage = ChatMessage(
                                text = result.summary,
                                isFromUser = false,
                                isSummary = true
                            )
                            
                            Log.d("ChatViewModel", "Создано новое саммари: ${summaryMessage.text.take(200)}...")
                            databaseRepository?.saveMessages(listOf(summaryMessage))
                            databaseRepository?.saveConversationState(newAccumulatedTokens)
                            
                            _messages.value = listOf(summaryMessage)
                            Log.d("ChatViewModel", "Установлено саммари в _messages, размер: ${_messages.value.size}")
                        },
                        onFailure = { error ->
                            Log.e("ChatViewModel", "Ошибка при создании саммари", error)
                            val savedMessages = databaseRepository?.loadMessages() ?: emptyList()
                            val summaryMessages = savedMessages.filter { it.isSummary }
                            Log.d("ChatViewModel", "Загружаем саммари из БД: ${summaryMessages.size}")
                            if (summaryMessages.isNotEmpty()) {
                                _messages.value = summaryMessages
                                Log.d("ChatViewModel", "Установлено саммари из БД в _messages, размер: ${_messages.value.size}")
                            } else {
                                _messages.value = emptyList()
                            }
                        }
                    )
                } else {
                    Log.d("ChatViewModel", "Нет не-summary сообщений, загружаем саммари из БД")
                    val savedMessages = databaseRepository?.loadMessages() ?: emptyList()
                    val summaryMessages = savedMessages.filter { it.isSummary }
                    Log.d("ChatViewModel", "Найдено саммари в БД: ${summaryMessages.size}")
                    summaryMessages.forEachIndexed { index, message ->
                        Log.d("ChatViewModel", "Саммари $index из БД: text=${message.text.take(200)}...")
                    }
                    if (summaryMessages.isNotEmpty()) {
                        _messages.value = summaryMessages
                        Log.d("ChatViewModel", "Установлено саммари из БД в _messages, размер: ${_messages.value.size}")
                    } else {
                        _messages.value = emptyList()
                    }
                }
                
                _error.value = null
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка в createNewChat", e)
                val savedMessages = databaseRepository?.loadMessages() ?: emptyList()
                val summaryMessages = savedMessages.filter { it.isSummary }
                if (summaryMessages.isNotEmpty()) {
                    _messages.value = summaryMessages
                } else {
                    _messages.value = emptyList()
                }
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
            Log.d("ChatViewModel", "=== sendMessage ===")
            val currentMessages = _messages.value
            Log.d("ChatViewModel", "Текущие сообщения перед отправкой: ${currentMessages.size}")
            currentMessages.forEachIndexed { index, message ->
                Log.d("ChatViewModel", "Сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(100)}...")
            }
            
            val summaryMessages = currentMessages.filter { it.isSummary }
            val nonSummaryMessages = currentMessages.filter { !it.isSummary }
            Log.d("ChatViewModel", "Summary сообщений: ${summaryMessages.size}, не-summary: ${nonSummaryMessages.size}")
            val compressionEnabled = getHistoryCompressionEnabled()
            
            val compressedMessages = if (compressionEnabled && nonSummaryMessages.size >= COMPRESSION_THRESHOLD) {
                Log.d("ChatViewModel", "Требуется сжатие: ${nonSummaryMessages.size} >= $COMPRESSION_THRESHOLD")
                val indicesToCompress = mutableListOf<Int>()
                var count = 0
                for ((index, message) in currentMessages.withIndex()) {
                    if (!message.isSummary && count < COMPRESSION_THRESHOLD) {
                        indicesToCompress.add(index)
                        count++
                    }
                    if (count >= COMPRESSION_THRESHOLD) break
                }
                
                val messagesToCompress = indicesToCompress.map { currentMessages[it] }
                val systemPrompt = getSystemPrompt()
                val model = getModel()
                val summaryResult = historyCompressor.createSummary(messagesToCompress, model)
                
                summaryResult.fold(
                    onSuccess = { summaryResult ->
                        val compressedTokens = messagesToCompress.sumOf { message ->
                            if (!message.isFromUser && message.responseMetadata != null) {
                                message.responseMetadata.tokensUsed
                            } else {
                                0
                            }
                        }
                        
                        val tokensToAccumulate = compressedTokens + summaryResult.tokensUsed
                        _accumulatedCompressedTokens.value += tokensToAccumulate
                        
                        val summaryMessage = ChatMessage(
                            text = summaryResult.summary,
                            isFromUser = false,
                            isSummary = true
                        )
                        
                        val resultList = currentMessages.toMutableList()
                        indicesToCompress.sortedDescending().forEach { index ->
                            resultList.removeAt(index)
                        }
                        val insertIndex = indicesToCompress.minOrNull() ?: 0
                        resultList.add(insertIndex, summaryMessage)
                        resultList
                    },
                    onFailure = {
                        currentMessages
                    }
                )
            } else {
                Log.d("ChatViewModel", "Сжатие не требуется, используем все текущие сообщения")
                currentMessages
            }
            
            Log.d("ChatViewModel", "=== compressedMessages перед отправкой ===")
            Log.d("ChatViewModel", "Размер compressedMessages: ${compressedMessages.size}")
            compressedMessages.forEachIndexed { index, message ->
                Log.d("ChatViewModel", "compressedMessages[$index]: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(150)}...")
            }
            
            val manualTokenCount = TokenCounter.countTokens(text)

            val userMessage = ChatMessage(
                text = text,
                isFromUser = true,
                manualTokenCount = manualTokenCount
            )
            val messagesWithUser = compressedMessages + userMessage
            _messages.value = messagesWithUser
            
            // Сохраняем все сообщения в БД сразу (используем NonCancellable для гарантии сохранения)
            try {
                withContext(NonCancellable) {
                    databaseRepository?.saveMessages(_messages.value)
                }
                Log.d("ChatViewModel", "Сообщения сохранены в БД после добавления пользовательского сообщения: ${_messages.value.size}")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка при сохранении сообщений в БД", e)
                e.printStackTrace()
            }
            
            val systemPrompt = getSystemPrompt()
            val temperature = getTemperature()
            val model = getModel()
            Log.d("ChatViewModel", "Отправляем в repository.sendMessage: compressedMessages.size=${compressedMessages.size}, userMessage=${text.take(50)}...")
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
                
                // Сохраняем все сообщения в БД сразу после получения ответа (используем NonCancellable)
                try {
                    withContext(NonCancellable) {
                        databaseRepository?.saveMessages(_messages.value)
                    }
                    Log.d("ChatViewModel", "Сообщения сохранены в БД после получения ответа бота: ${_messages.value.size}")
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Ошибка при сохранении сообщений в БД после ответа", e)
                    e.printStackTrace()
                }
                
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
                
                // Сохраняем все сообщения в БД даже при ошибке (используем NonCancellable)
                try {
                    withContext(NonCancellable) {
                        databaseRepository?.saveMessages(_messages.value)
                    }
                    Log.d("ChatViewModel", "Сообщения сохранены в БД после ошибки: ${_messages.value.size}")
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Ошибка при сохранении сообщений в БД после ошибки", e)
                    e.printStackTrace()
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
