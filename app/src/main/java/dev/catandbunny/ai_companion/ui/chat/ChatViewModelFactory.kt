package dev.catandbunny.ai_companion.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ChatViewModelFactory(
    private val apiKey: String,
    private val getSystemPrompt: () -> String,
    private val getTemperature: () -> Double,
    private val getModel: () -> String,
    private val getHistoryCompressionEnabled: () -> Boolean
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(apiKey, getSystemPrompt, getTemperature, getModel, getHistoryCompressionEnabled) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
