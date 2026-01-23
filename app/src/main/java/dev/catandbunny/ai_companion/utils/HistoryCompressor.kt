package dev.catandbunny.ai_companion.utils

import android.util.Log
import dev.catandbunny.ai_companion.data.api.RetrofitClient
import dev.catandbunny.ai_companion.data.model.Message
import dev.catandbunny.ai_companion.data.model.OpenAIRequest
import dev.catandbunny.ai_companion.model.ChatMessage

/**
 * Результат создания summary с информацией о токенах
 */
data class SummaryResult(
    val summary: String,
    val tokensUsed: Int
)

/**
 * Сервис для сжатия истории диалога путем создания summary
 */
class HistoryCompressor(private val apiKey: String) {
    private val openAIService = RetrofitClient.openAIService

    /**
     * Создает summary для списка сообщений
     * 
     * @param messages Список сообщений для сжатия
     * @param model Модель для генерации summary (по умолчанию gpt-3.5-turbo)
     * @return SummaryResult с текстом summary и количеством использованных токенов
     */
    suspend fun createSummary(
        messages: List<ChatMessage>,
        model: String = "gpt-3.5-turbo"
    ): Result<SummaryResult> {
        return try {
            if (messages.isEmpty()) {
                return Result.failure(Exception("Список сообщений пуст"))
            }

            // Формируем текст диалога для summary
            val conversationText = messages.joinToString("\n") { message ->
                val role = if (message.isFromUser) "Пользователь" else "Ассистент"
                "$role: ${message.text}"
            }

            // Создаем промпт для summary
            val summaryPrompt = """
                Создай краткое резюме следующего диалога, сохраняя ключевые моменты, важные детали и контекст разговора.
                Резюме должно быть на русском языке и содержать основную информацию из диалога.
                
                Диалог:
                $conversationText
                
                Резюме:
            """.trimIndent()

            val systemMessage = Message(
                role = "system",
                content = "Ты помощник, который создает краткие и информативные резюме диалогов."
            )

            val userMessage = Message(
                role = "user",
                content = summaryPrompt
            )

            val request = OpenAIRequest(
                model = model,
                messages = listOf(systemMessage, userMessage),
                temperature = 0.3, // Низкая температура для более детерминированного summary
                maxTokens = 500 // Ограничиваем длину summary
            )

            val response = openAIService.createChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                val summary = responseBody.choices.firstOrNull()?.message?.content
                    ?: return Result.failure(Exception("Не удалось получить summary от API"))
                
                val tokensUsed = responseBody.usage.totalTokens
                
                Log.d("HistoryCompressor", "Summary создан успешно, длина: ${summary.length}, токенов: $tokensUsed")
                Result.success(SummaryResult(summary, tokensUsed))
            } else {
                val errorMessage = response.errorBody()?.string()
                    ?: "Ошибка при создании summary: ${response.code()}"
                Log.e("HistoryCompressor", errorMessage)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e("HistoryCompressor", "Ошибка при создании summary", e)
            Result.failure(e)
        }
    }

    /**
     * Подсчитывает количество не-summary сообщений в списке
     */
    fun countNonSummaryMessages(messages: List<ChatMessage>): Int {
        return messages.count { !it.isSummary }
    }
}
