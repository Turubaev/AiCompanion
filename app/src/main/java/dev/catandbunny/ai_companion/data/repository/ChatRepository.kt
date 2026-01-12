package dev.catandbunny.ai_companion.data.repository

import dev.catandbunny.ai_companion.data.api.RetrofitClient
import dev.catandbunny.ai_companion.data.model.Message
import dev.catandbunny.ai_companion.data.model.OpenAIRequest
import dev.catandbunny.ai_companion.model.ChatMessage

class ChatRepository(private val apiKey: String) {
    private val openAIService = RetrofitClient.openAIService

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> {
        return try {
            // Формируем историю сообщений для контекста
            val messages = conversationHistory.map { chatMessage ->
                Message(
                    role = if (chatMessage.isFromUser) "user" else "assistant",
                    content = chatMessage.text
                )
            } + Message(role = "user", content = userMessage)

            val request = OpenAIRequest(
                model = "gpt-3.5-turbo",
                messages = messages,
                temperature = 0.7,
                maxTokens = 1000
            )

            val response = openAIService.createChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful && response.body() != null) {
                val botResponse = response.body()!!.choices.firstOrNull()?.message?.content
                    ?: "Извините, не удалось получить ответ."
                Result.success(botResponse)
            } else {
                val errorMessage = response.errorBody()?.string() 
                    ?: "Ошибка при отправке запроса: ${response.code()}"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
