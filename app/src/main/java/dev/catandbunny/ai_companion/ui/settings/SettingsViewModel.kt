package dev.catandbunny.ai_companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private data class FiveSettings(
    val prompt: String,
    val temperature: Double,
    val model: String,
    val compression: Boolean,
    val currencyEnabled: Boolean
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

    init {
        // Загружаем настройки из базы при инициализации
        loadSettingsFromDatabase()
        
        // Сохраняем настройки при изменении (combine поддерживает до 5 потоков, поэтому два уровня)
        viewModelScope.launch {
            combine(
                _systemPrompt,
                _temperature,
                _selectedModel,
                _historyCompressionEnabled,
                _currencyNotificationEnabled
            ) { prompt, temp, model, compression, currencyEnabled ->
                FiveSettings(prompt, temp, model, compression, currencyEnabled)
            }.combine(_currencyIntervalMinutes) { five, currencyInterval ->
                databaseRepository?.saveSettings(
                    five.prompt,
                    five.temperature,
                    five.model,
                    five.compression,
                    currencyNotificationEnabled = five.currencyEnabled,
                    currencyIntervalMinutes = currencyInterval
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
}
