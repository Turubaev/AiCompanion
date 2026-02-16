package dev.catandbunny.ai_companion.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository

class ChatViewModelFactory(
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
    private val databaseRepository: DatabaseRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(apiKey, getSystemPrompt, getTemperature, getModel, getHistoryCompressionEnabled, getTelegramChatId, getRagEnabled, getRagMinScore, getRagUseReranker, getGitHubUsername, databaseRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
