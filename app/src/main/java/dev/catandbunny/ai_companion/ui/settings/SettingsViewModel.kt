package dev.catandbunny.ai_companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catandbunny.ai_companion.BuildConfig
import dev.catandbunny.ai_companion.data.api.registerPrReviewTelegram
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SixSettings(
    val prompt: String,
    val temperature: Double,
    val model: String,
    val compression: Boolean,
    val currencyEnabled: Boolean,
    val ragEnabled: Boolean
)

class SettingsViewModel(
    private val databaseRepository: DatabaseRepository? = null
) : ViewModel() {
    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // Температура модели (по умолчанию 0.7)
    private val _temperature = MutableStateFlow(0.7)
    val temperature: StateFlow<Double> = _temperature.asStateFlow()

    // Доступные модели OpenAI
    val availableModels = listOf(
        "gpt-3.5-turbo",
        "gpt-4",
        "gpt-4-turbo-preview",
        "gpt-4o",
        "gpt-4o-mini"
    )

    // Выбранная модель (по умолчанию gpt-3.5-turbo)
    private val _selectedModel = MutableStateFlow("gpt-3.5-turbo")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // Сжатие истории диалога (по умолчанию включено)
    private val _historyCompressionEnabled = MutableStateFlow(true)
    val historyCompressionEnabled: StateFlow<Boolean> = _historyCompressionEnabled.asStateFlow()

    // Уведомления о курсе USD/RUB (по умолчанию выключено)
    private val _currencyNotificationEnabled = MutableStateFlow(false)
    val currencyNotificationEnabled: StateFlow<Boolean> = _currencyNotificationEnabled.asStateFlow()

    // Интервал запроса курса в минутах (1, 5, 15, 30)
    val currencyIntervalOptions = listOf(1, 5, 15, 30)
    private val _currencyIntervalMinutes = MutableStateFlow(5)
    val currencyIntervalMinutes: StateFlow<Int> = _currencyIntervalMinutes.asStateFlow()

    // Telegram chat_id для отправки рекомендаций (портфель и т.д.)
    private val _telegramChatId = MutableStateFlow("")
    val telegramChatId: StateFlow<String> = _telegramChatId.asStateFlow()

    // Режим RAG: поиск релевантных чанков по индексу перед запросом к LLM
    private val _ragEnabled = MutableStateFlow(false)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled.asStateFlow()

    // Порог релевантности RAG (0.0–1.0): чанки с score ниже не попадают в контекст
    private val _ragMinScore = MutableStateFlow(0.0)
    val ragMinScore: StateFlow<Double> = _ragMinScore.asStateFlow()

    // Использовать cross-encoder reranker для переранжирования результатов RAG
    private val _ragUseReranker = MutableStateFlow(false)
    val ragUseReranker: StateFlow<Boolean> = _ragUseReranker.asStateFlow()

    // GitHub username для получения ревью PR в чат
    private val _githubUsername = MutableStateFlow("")
    val githubUsername: StateFlow<String> = _githubUsername.asStateFlow()

    // Email пользователя для контекста поддержки (тикеты CRM)
    private val _supportUserEmail = MutableStateFlow("")
    val supportUserEmail: StateFlow<String> = _supportUserEmail.asStateFlow()

    // Автоматически добавлять контекст поддержки в каждый запрос
    private val _autoIncludeSupportContext = MutableStateFlow(false)
    val autoIncludeSupportContext: StateFlow<Boolean> = _autoIncludeSupportContext.asStateFlow()

    // Результат привязки к ревью PR (Telegram): null, "OK", или текст ошибки
    private val _prReviewRegisterResult = MutableStateFlow<String?>(null)
    val prReviewRegisterResult: StateFlow<String?> = _prReviewRegisterResult.asStateFlow()

    init {
        loadSettingsFromDatabase()
        viewModelScope.launch {
            combine(
                _systemPrompt,
                _temperature,
                _selectedModel,
                _historyCompressionEnabled,
                _currencyNotificationEnabled
            ) { prompt, temp, model, compression, currencyEnabled ->
                SixSettings(prompt, temp, model, compression, currencyEnabled, false)
            }.combine(_ragEnabled) { six, ragEnabled ->
                six.copy(ragEnabled = ragEnabled)
            }.combine(_ragMinScore) { six, ragMinScore ->
                Pair(six, ragMinScore)
            }.combine(_currencyIntervalMinutes) { pair, currencyInterval ->
                Triple(pair.first, pair.second, currencyInterval)
            }.combine(_telegramChatId) { triple, chatId ->
                Pair(triple, chatId)
            }.combine(_ragUseReranker) { pair, ragUseRerankerVal ->
                Pair(pair, ragUseRerankerVal)
            }.combine(_githubUsername) { pair, githubUser ->
                Pair(pair, githubUser)
            }.combine(_supportUserEmail) { pair, supportEmail ->
                Pair(pair, supportEmail)
            }.combine(_autoIncludeSupportContext) { pair, autoSupport ->
                val (prev, supportEmail) = pair
                val (prev2, githubUser) = prev
                val (pairWithChat, ragUseRerankerVal) = prev2
                val (innerTriple, chatId) = pairWithChat
                val (six, ragMinScoreVal, interval) = innerTriple
                databaseRepository?.saveSettings(
                    six.prompt,
                    six.temperature,
                    six.model,
                    six.compression,
                    currencyNotificationEnabled = six.currencyEnabled,
                    currencyIntervalMinutes = interval,
                    telegramChatId = chatId,
                    ragEnabled = six.ragEnabled,
                    ragMinScore = ragMinScoreVal,
                    ragUseReranker = ragUseRerankerVal,
                    githubUsername = githubUser,
                    supportUserEmail = supportEmail,
                    autoIncludeSupportContext = autoSupport
                )
            }.collect {}
        }
    }
    
    private fun loadSettingsFromDatabase() {
        viewModelScope.launch {
            try {
                val savedSettings = databaseRepository?.loadSettings()
                savedSettings?.let {
                    _systemPrompt.value = it.systemPrompt
                    _temperature.value = it.temperature
                    _selectedModel.value = it.selectedModel
                    _historyCompressionEnabled.value = it.historyCompressionEnabled
                    _currencyNotificationEnabled.value = it.currencyNotificationEnabled
                    _currencyIntervalMinutes.value = it.currencyIntervalMinutes.coerceIn(1, 30)
                    _telegramChatId.value = it.telegramChatId
                    _ragEnabled.value = it.ragEnabled
                    _ragMinScore.value = it.ragMinScore.coerceIn(0.0, 1.0)
                    _ragUseReranker.value = it.ragUseReranker
                    _githubUsername.value = it.githubUsername
                    _supportUserEmail.value = it.supportUserEmail
                    _autoIncludeSupportContext.value = it.autoIncludeSupportContext
                }
            } catch (e: Exception) {
                // Игнорируем ошибки загрузки
            }
        }
    }

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    fun getSystemPrompt(): String = _systemPrompt.value

    fun updateTemperature(newTemperature: Double) {
        _temperature.value = newTemperature.coerceIn(0.0, 2.0)
    }

    fun getTemperature(): Double = _temperature.value

    fun updateSelectedModel(model: String) {
        if (model in availableModels) {
            _selectedModel.value = model
        }
    }

    fun getSelectedModel(): String = _selectedModel.value

    fun updateHistoryCompressionEnabled(enabled: Boolean) {
        _historyCompressionEnabled.value = enabled
    }

    fun getHistoryCompressionEnabled(): Boolean = _historyCompressionEnabled.value

    fun updateCurrencyNotificationEnabled(enabled: Boolean) {
        _currencyNotificationEnabled.value = enabled
    }

    fun getCurrencyNotificationEnabled(): Boolean = _currencyNotificationEnabled.value

    fun updateCurrencyIntervalMinutes(minutes: Int) {
        if (minutes in currencyIntervalOptions) {
            _currencyIntervalMinutes.value = minutes
        }
    }

    fun getCurrencyIntervalMinutes(): Int = _currencyIntervalMinutes.value

    fun updateTelegramChatId(chatId: String) {
        _telegramChatId.value = chatId.trim()
    }

    fun getTelegramChatId(): String = _telegramChatId.value

    fun updateRagEnabled(enabled: Boolean) {
        _ragEnabled.value = enabled
    }

    fun getRagEnabled(): Boolean = _ragEnabled.value

    fun updateRagMinScore(score: Double) {
        _ragMinScore.value = score.coerceIn(0.0, 1.0)
    }

    fun getRagMinScore(): Double = _ragMinScore.value

    fun updateRagUseReranker(use: Boolean) {
        _ragUseReranker.value = use
    }

    fun getRagUseReranker(): Boolean = _ragUseReranker.value

    fun updateGitHubUsername(username: String) {
        _githubUsername.value = username.trim()
    }

    fun getGitHubUsername(): String = _githubUsername.value

    fun updateSupportUserEmail(email: String) {
        _supportUserEmail.value = email.trim()
    }

    fun getSupportUserEmail(): String = _supportUserEmail.value

    fun updateAutoIncludeSupportContext(enabled: Boolean) {
        _autoIncludeSupportContext.value = enabled
    }

    fun getAutoIncludeSupportContext(): Boolean = _autoIncludeSupportContext.value

    /** Привязать текущие GitHub username и Telegram Chat ID к сервису ревью PR для доставки ревью в Telegram. */
    fun registerPrReviewForTelegram() {
        viewModelScope.launch {
            _prReviewRegisterResult.value = null
            val result = withContext(Dispatchers.IO) {
                registerPrReviewTelegram(
                    BuildConfig.PR_REVIEW_SERVICE_URL,
                    _githubUsername.value,
                    _telegramChatId.value
                )
            }
            _prReviewRegisterResult.value = if (result) "OK" else "Ошибка: сервис недоступен или неверные данные"
        }
    }

    fun clearPrReviewRegisterResult() {
        _prReviewRegisterResult.value = null
    }
}
