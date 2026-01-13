package dev.catandbunny.ai_companion.data.repository

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dev.catandbunny.ai_companion.data.api.RetrofitClient
import dev.catandbunny.ai_companion.data.model.Message
import dev.catandbunny.ai_companion.data.model.OpenAIRequest
import dev.catandbunny.ai_companion.model.ChatMessage
import dev.catandbunny.ai_companion.model.ResponseMetadata

class ChatRepository(private val apiKey: String) {
    private val openAIService = RetrofitClient.openAIService

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<Pair<String, ResponseMetadata>> {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Системный промпт - бот должен возвращать ответ в формате JSON
            val systemPrompt = Message(
                role = "system",
                content = """
                    Ты - полезный AI-ассистент. Отвечай на вопросы пользователя полно, информативно и дружелюбно. 
                    
                    Твой ответ должен быть строго в формате JSON со следующей структурой:
                    {
                      "questionText": "текст вопроса пользователя (точная копия вопроса)",
                      "answerText": "твой ответ на вопрос",
                      "language": "ISO 639-1 код языка ответа (например: ru, en, de, fr, es, zh, ja, ko, it, pt, ar, hi, или mixed для смешанного, unknown для неизвестного)",
                      "timestamp": текущее время в миллисекундах Unix timestamp,
                      "status": "статус ответа (success - успешный полный ответ, partial - частичный ответ, warning - ответ с предупреждением)",
                      "answerLength": длина ответа в символах (число),
                      "category": "категория/тема вопроса если можешь определить (например: technology, science, health, education, general, и т.д.), иначе null",
                      "confidence": уровень уверенности в ответе от 0.0 до 1.0 (1.0 - максимальная уверенность), если применимо, иначе null
                    }
                    
                    Правила:
                    - questionText - точная копия вопроса пользователя из текущего сообщения
                    - answerText - твой содержательный ответ
                    - language - определи язык ответа используя ISO 639-1 код (ru, en, de, fr, es, zh, ja, ko, it, pt, ar, hi и т.д.). Если язык смешанный - используй "mixed", если не можешь определить - "unknown"
                    - timestamp - текущее время в миллисекундах (Unix timestamp). Используй текущее время на момент генерации ответа
                    - status - статус ответа: "success" (полный успешный ответ), "partial" (частичный ответ), "warning" (ответ с предупреждением или неполной информацией)
                    - answerLength - длина текста ответа в символах (включая пробелы)
                    - category - попытайся определить категорию вопроса (technology, science, health, education, business, entertainment, sports, politics, general и т.д.). Если не можешь определить, используй null
                    - confidence - уровень уверенности в правильности и полноте ответа от 0.0 до 1.0. Используй null если не применимо
                    
                    ВАЖНО: 
                    - Возвращай ТОЛЬКО валидный JSON, без дополнительного текста до или после JSON
                    - Не используй markdown код блоки
                    - Не добавляй пояснений или комментариев
                    - JSON должен быть валидным и корректно экранированным
                    - Все числовые значения должны быть числами, не строками
                    - null значения используй только для необязательных полей (category, confidence)
                """.trimIndent()
            )
            
            // Формируем историю сообщений для контекста
            val messages = listOf(systemPrompt) + conversationHistory.map { chatMessage ->
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
                val responseBody = response.body()!!
                val botResponse = responseBody.choices.firstOrNull()?.message?.content
                    ?: "Извините, не удалось получить ответ."
                
                // Парсим JSON ответ от бота и получаем очищенный JSON для отображения
                val parseResult = parseJsonResponse(botResponse, userMessage)
                val parsedData = parseResult.first
                val cleanedJson = parseResult.second
                
                val responseTime = System.currentTimeMillis() - startTime
                val tokensUsed = responseBody.usage.totalTokens
                val timestamp = System.currentTimeMillis()
                
                val metadata = ResponseMetadata(
                    questionText = parsedData.questionText,
                    answerText = parsedData.answerText,
                    language = parsedData.language,
                    timestamp = timestamp,
                    responseTimeMs = responseTime,
                    tokensUsed = tokensUsed,
                    status = parsedData.status,
                    botTimestamp = parsedData.botTimestamp,
                    answerLength = parsedData.answerLength,
                    category = parsedData.category,
                    confidence = parsedData.confidence
                )
                
                // Возвращаем полный JSON ответ от бота для отображения в чате
                Result.success(Pair(cleanedJson, metadata))
            } else {
                val errorMessage = response.errorBody()?.string() 
                    ?: "Ошибка при отправке запроса: ${response.code()}"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private data class ParsedResponse(
        val questionText: String,
        val answerText: String,
        val language: String,
        val botTimestamp: Long? = null,
        val status: String = "success",
        val answerLength: Int,
        val category: String? = null,
        val confidence: Double? = null
    )
    
    private fun parseJsonResponse(botResponse: String, userMessage: String): Pair<ParsedResponse, String> {
        return try {
            // Пытаемся найти JSON в ответе (может быть обернут в markdown код блоки)
            var jsonString = botResponse.trim()
            
            // Убираем markdown код блоки если есть
            if (jsonString.startsWith("```json")) {
                jsonString = jsonString.removePrefix("```json").trim()
            } else if (jsonString.startsWith("```")) {
                jsonString = jsonString.removePrefix("```").trim()
            }
            if (jsonString.endsWith("```")) {
                jsonString = jsonString.removeSuffix("```").trim()
            }
            
            // Парсим JSON
            val gson = Gson()
            val jsonObject = gson.fromJson(jsonString, Map::class.java) as Map<*, *>
            
            val questionText = (jsonObject["questionText"] as? String) ?: userMessage
            val answerText = (jsonObject["answerText"] as? String) ?: botResponse
            val language = (jsonObject["language"] as? String) ?: detectLanguage(answerText)
            
            // Парсим числовые значения для timestamp
            val botTimestamp = when (val ts = jsonObject["timestamp"]) {
                is Number -> ts.toLong()
                is String -> ts.toLongOrNull()
                else -> null
            }
            
            // Парсим статус
            val status = (jsonObject["status"] as? String) ?: "success"
            
            // Парсим answerLength
            val answerLength = when (val len = jsonObject["answerLength"]) {
                is Number -> len.toInt()
                is String -> len.toIntOrNull() ?: answerText.length
                else -> answerText.length
            }
            
            // Парсим category
            val category = (jsonObject["category"] as? String).takeIf { it != "null" && it.orEmpty().isNotBlank() }
            
            // Парсим confidence
            val confidence = when (val conf = jsonObject["confidence"]) {
                is Number -> conf.toDouble()
                is String -> conf.toDoubleOrNull()
                else -> null
            }
            
            Pair(
                ParsedResponse(
                    questionText = questionText,
                    answerText = answerText,
                    language = language,
                    botTimestamp = botTimestamp,
                    status = status,
                    answerLength = answerLength,
                    category = category,
                    confidence = confidence
                ),
                jsonString
            )
        } catch (e: JsonSyntaxException) {
            // Если не удалось распарсить JSON, используем fallback
            val language = detectLanguage(botResponse)
            Pair(
                ParsedResponse(
                    questionText = userMessage,
                    answerText = botResponse,
                    language = language,
                    status = "success",
                    answerLength = botResponse.length
                ),
                botResponse
            )
        } catch (e: Exception) {
            // Если произошла другая ошибка, используем fallback
            val language = detectLanguage(botResponse)
            Pair(
                ParsedResponse(
                    questionText = userMessage,
                    answerText = botResponse,
                    language = language,
                    status = "success",
                    answerLength = botResponse.length
                ),
                botResponse
            )
        }
    }
    
    private fun detectLanguage(text: String): String {
        // Простое определение языка по кириллице/латинице
        val cyrillicPattern = Regex("[А-Яа-яЁё]")
        val latinPattern = Regex("[A-Za-z]")
        
        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatin = latinPattern.containsMatchIn(text)
        
        return when {
            hasCyrillic && !hasLatin -> "ru" // Русский
            hasLatin && !hasCyrillic -> "en" // Английский
            hasCyrillic && hasLatin -> "mixed" // Смешанный
            else -> "unknown" // Неизвестный
        }
    }
}
