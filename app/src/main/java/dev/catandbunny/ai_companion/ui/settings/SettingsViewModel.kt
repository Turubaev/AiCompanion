package dev.catandbunny.ai_companion.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel : ViewModel() {
    // Дефолтный системный промпт (пустой)
    private val defaultSystemPrompt = ""

    private val _systemPrompt = MutableStateFlow(defaultSystemPrompt)
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

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    fun getSystemPrompt(): String = _systemPrompt.value

    fun updateTemperature(newTemperature: Double) {
        // Ограничиваем температуру диапазоном от 0.0 до 2.0
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
}
