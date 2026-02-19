package dev.catandbunny.ai_companion.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catandbunny.ai_companion.BuildConfig
import dev.catandbunny.ai_companion.data.api.PrReviewItem
import dev.catandbunny.ai_companion.data.api.fetchPrReviews
import dev.catandbunny.ai_companion.data.repository.ChatRepository
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import dev.catandbunny.ai_companion.data.repository.SupportContext
import dev.catandbunny.ai_companion.model.ChatMessage
import dev.catandbunny.ai_companion.utils.HistoryCompressor
import dev.catandbunny.ai_companion.utils.TokenCounter
import kotlinx.coroutines.Dispatchers
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
    private val getTelegramChatId: () -> String = { "" },
    private val getRagEnabled: () -> Boolean = { false },
    private val getRagMinScore: () -> Double = { 0.0 },
    private val getRagUseReranker: () -> Boolean = { false },
    private val getGitHubUsername: () -> String = { "" },
    private val getSupportUserEmail: () -> String = { "" },
    private val getAutoIncludeSupportContext: () -> Boolean = { false },
    private val databaseRepository: DatabaseRepository? = null
) : ViewModel() {
    private val repository = ChatRepository(
        apiKey,
        getTelegramChatId,
        getRagEnabled,
        getRagMinScore,
        getRagUseReranker,
        getSupportUserEmail,
        getAutoIncludeSupportContext
    )
    private val historyCompressor = HistoryCompressor(apiKey)
    
    companion object {
        private const val COMPRESSION_THRESHOLD = 10 // Сжимать каждые 10 сообщений
        /** Команда /help: бот отвечает о структуре проекта CloudBuddy на основе RAG (README + docs). */
        private const val HELP_COMMAND = "/help"
        /** Вопрос для LLM при /help — подставляется в запрос с принудительным RAG. */
        private const val HELP_QUESTION = "Опиши структуру и архитектуру проекта CloudBuddy. Что есть в README и в папке docs? Дай краткий обзор по документации проекта."

        private fun formatPrReviewMessage(r: PrReviewItem): String {
            val prNum = when (r.prNumber) {
                is Number -> (r.prNumber as Number).toString()
                else -> r.prNumber.toString()
            }
            val titlePart = r.prTitle?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            return "Ревью PR #$prNum (${r.repo})$titlePart\n\n${r.reviewText}"
        }
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
                } else {
                    savedMessages.forEachIndexed { index, message ->
                        Log.d("ChatViewModel", "Сообщение $index: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(100)}...")
                    }
                    val savedState = databaseRepository?.loadConversationState()
                    savedState?.let {
                        _accumulatedCompressedTokens.value = it.accumulatedCompressedTokens
                        Log.d("ChatViewModel", "Загружено состояние: accumulatedTokens=${it.accumulatedCompressedTokens}")
                    }
                    _messages.value = savedMessages
                    Log.d("ChatViewModel", "Восстановлено сообщений в UI: ${savedMessages.size}")
                }

                // Загружаем ревью PR и добавляем в чат (даже при пустой БД — тогда ревью появятся первыми)
                fetchPrReviewsIntoChat()

                Log.d("ChatViewModel", "=== loadDataFromDatabase КОНЕЦ ===")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка при загрузке данных из БД", e)
                e.printStackTrace()
                _messages.value = emptyList()
            }
        }
    }

    /**
     * Запрашивает непрочитанные ревью PR с сервера, добавляет их в чат и обновляет UI.
     * Вызывается при загрузке чата и при возврате на экран чата (чтобы подтянуть ревью после смены username в настройках).
     */
    fun fetchPrReviewsIntoChat() {
        viewModelScope.launch {
            val ghUser = getGitHubUsername().trim()
            if (ghUser.isBlank() || databaseRepository == null) return@launch
            withContext(Dispatchers.IO) {
                val baseUrl = BuildConfig.PR_REVIEW_SERVICE_URL
                val reviews = fetchPrReviews(baseUrl, ghUser)
                for (r in reviews) {
                    val text = formatPrReviewMessage(r)
                    databaseRepository!!.appendAssistantMessage(text)
                }
                if (reviews.isNotEmpty()) {
                    val updated = databaseRepository!!.loadMessages()
                    _messages.value = updated
                    Log.d("ChatViewModel", "Добавлено ревью PR в чат: ${reviews.size}, всего сообщений: ${updated.size}")
                }
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
    
    /**
     * Начать новую беседу: очистить экран и историю в БД.
     * Следующий запрос к боту уйдёт без контекста предыдущих сообщений (только системный промпт + RAG + новый вопрос).
     * Удобно для сравнения ответов при разных настройках RAG (порог, reranker) в разных «чатах».
     */
    fun createNewChat() {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "=== createNewChat: полная очистка беседы ===")
                _messages.value = emptyList()
                _accumulatedCompressedTokens.value = 0
                _error.value = null
                databaseRepository?.clearConversation()
                Log.d("ChatViewModel", "Новая беседа: сообщения и БД очищены")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка в createNewChat", e)
                _messages.value = emptyList()
                _error.value = null
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

        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        when {
            lower.startsWith("/tickets") -> {
                viewModelScope.launch { handleTicketsCommand() }
                return
            }
            lower.startsWith("/ticket") -> {
                val arg = trimmed.substring(7.coerceAtMost(trimmed.length)).trim()
                viewModelScope.launch { handleTicketDetailCommand(arg) }
                return
            }
            lower.startsWith("/newticket") -> {
                val arg = trimmed.substring(10.coerceAtMost(trimmed.length)).trim()
                viewModelScope.launch { handleNewTicketCommand(arg) }
                return
            }
        }

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
            // Команда /help с опциональным уточнением: "/help", "/help о чём приложение" и т.д.
            val trimmed = text.trim()
            val isHelpCommand = trimmed.equals(HELP_COMMAND, ignoreCase = true) ||
                trimmed.lowercase().startsWith("$HELP_COMMAND ")
            val messageToSend = if (isHelpCommand) HELP_QUESTION else text
            val useRagForThisRequest = if (isHelpCommand) true else null
            Log.d("ChatViewModel", "Отправляем в repository.sendMessage: compressedMessages.size=${compressedMessages.size}, userMessage=${messageToSend.take(50)}..., useRagForThisRequest=$useRagForThisRequest")
            val result = repository.sendMessage(messageToSend, compressedMessages, systemPrompt, temperature, model, useRagForThisRequest)

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

    private suspend fun handleTicketsCommand() {
        val email = getSupportUserEmail().trim()
        if (email.isBlank()) {
            appendSupportMessage("Сначала укажите ваш email в настройках поддержки (Настройки → Настройки поддержки).")
            return
        }
        val context = withContext(Dispatchers.IO) { repository.getSupportContext(email) }
        if (context?.open_tickets?.isNotEmpty() == true) {
            val message = buildString {
                appendLine("📋 Ваши открытые тикеты:")
                context.open_tickets.forEachIndexed { index, ticket ->
                    appendLine("${index + 1}. #${ticket.id} — ${ticket.subject}")
                    appendLine("   Статус: ${ticket.status}")
                    appendLine("   Последнее сообщение: ${ticket.last_message}")
                    appendLine()
                }
                appendLine("Используйте /ticket [номер или id] для просмотра деталей.")
            }
            appendSupportMessage(message)
        } else {
            appendSupportMessage("У вас нет открытых тикетов.")
        }
    }

    private suspend fun handleTicketDetailCommand(ticketId: String) {
        if (ticketId.isBlank()) {
            appendSupportMessage("Укажите номер или id тикета, например: /ticket 1 или /ticket TICKET-123")
            return
        }
        val normalizedId = ticketId.trim()
        val details = withContext(Dispatchers.IO) { repository.getTicketDetails(normalizedId) }
        if (!details.isNullOrBlank()) {
            appendSupportMessage(details)
        } else {
            appendSupportMessage("Тикет не найден или сервис недоступен.")
        }
    }

    private suspend fun handleNewTicketCommand(message: String) {
        if (message.isBlank()) {
            appendSupportMessage("Укажите текст обращения, например: /newticket Не могу войти в приложение")
            return
        }
        val email = getSupportUserEmail().trim()
        if (email.isBlank()) {
            appendSupportMessage("Сначала укажите ваш email в настройках поддержки.")
            return
        }
        val result = withContext(Dispatchers.IO) { repository.createTicket(email, message) }
        if (!result.isNullOrBlank()) {
            appendSupportMessage(result)
        } else {
            appendSupportMessage("Не удалось создать тикет. Проверьте подключение к MCP и сервис поддержки.")
        }
    }

    private suspend fun appendSupportMessage(text: String) {
        val botMessage = ChatMessage(text = text, isFromUser = false)
        _messages.value = _messages.value + botMessage
        databaseRepository?.appendAssistantMessage(text)
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Добавляет сообщение от бота в чат (например, текст из пуш-уведомления о курсе).
     */
    fun addBotMessage(text: String) {
        if (text.isBlank()) return
        val botMessage = ChatMessage(
            text = text,
            isFromUser = false
        )
        _messages.value = _messages.value + botMessage
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    databaseRepository?.saveMessages(_messages.value)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка сохранения при добавлении сообщения из уведомления", e)
            }
        }
    }
}
