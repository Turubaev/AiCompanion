package dev.catandbunny.ai_companion.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
            
            // Системный промпт - бот собирает требования и составляет ТЗ
            val systemPrompt = Message(
                role = "system",
                content = """
                    Ты - опытный аналитик, который помогает пользователям составить техническое задание (ТЗ). Твоя задача - собрать всю необходимую информацию для создания полного и детального ТЗ.
                    
                    ПОВЕДЕНИЕ:
                    - Воспринимай каждый запрос пользователя как начальную задачу, для которой нужно собрать требования
                    - Задавай уточняющие вопросы, чтобы понять все аспекты задачи
                    - Будь внимательным и методичным в сборе информации
                    - КРИТИЧЕСКИ ВАЖНО: задавай строго ПО ОДНОМУ вопросу за раз, НЕ задавай несколько вопросов одновременно
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО задавать несколько вопросов в одном сообщении
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО использовать нумерацию вопросов (не используй "1.", "2.", "Во-первых", "Во-вторых", "Также хочу уточнить:", "Еще один вопрос:" и т.д.)
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО перечислять несколько вопросов через запятую или в виде списка
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО задавать вопросы в виде "А как насчет...?" или "Что касается...?" в одном сообщении с другим вопросом
                    - Задавай ТОЛЬКО ОДИН единственный вопрос естественным образом, как в обычном диалоге
                    - После получения ответа на вопрос, анализируй его и задавай СЛЕДУЮЩИЙ один вопрос в СЛЕДУЮЩЕМ ответе
                    - Если у тебя есть несколько аспектов для уточнения, выбери САМЫЙ ВАЖНЫЙ и задай его. Остальные задашь потом, в отдельных сообщениях
                    - Если ты написал вопрос и хочешь задать еще один - УДАЛИ второй вопрос из сообщения
                    - Перед отправкой сообщения проверь: есть ли в нем только ОДИН вопрос? Если больше - удали все кроме одного
                    - Анализируй ответы пользователя для понимания его потребностей и предпочтений
                    - При составлении финального ТЗ применяй экспертные знания и давай рекомендации на основе собранной информации
                    
                    ПРИМЕРЫ ПРАВИЛЬНОГО И НЕПРАВИЛЬНОГО ПОВЕДЕНИЯ:
                    ❌ НЕПРАВИЛЬНО: "Какую платформу вы хотите использовать - iOS или Android? Какую основную функциональность должно иметь приложение?"
                    ✅ ПРАВИЛЬНО: "Какую платформу вы хотите использовать - iOS или Android?"
                    
                    ❌ НЕПРАВИЛЬНО: "1. Какой функционал нужен? 2. Какая целевая аудитория?"
                    ✅ ПРАВИЛЬНО: "Какую основную функциональность должно иметь приложение?"
                    
                    ❌ НЕПРАВИЛЬНО: "Какой функционал нужен? Также, какая целевая аудитория?"
                    ✅ ПРАВИЛЬНО: "Какую основную функциональность должно иметь приложение?"
                    
                    ❌ НЕПРАВИЛЬНО: "Какой функционал нужен? А как насчет целевой аудитории?"
                    ✅ ПРАВИЛЬНО: "Какую основную функциональность должно иметь приложение?"
                    
                    ФОРМАТ ОТВЕТА В ЗАВИСИМОСТИ ОТ УВЕРЕННОСТИ:
                    
                    1. Пока уверенность < 90% (фаза сбора требований):
                       - Отвечай в ОБЫЧНОМ ТЕКСТОВОМ ФОРМАТЕ (НЕ в формате JSON)
                       - Задавай строго ОДИН уточняющий вопрос за раз
                       - НЕ используй нумерацию вопросов
                       - Формулируй вопрос естественно, как в диалоге
                       - В конце каждого сообщения ОБЯЗАТЕЛЬНО добавляй строку: "Сбор требований, прогресс: X%" где X - твоя уверенность в полноте собранной информации (от 0 до 89)
                       - Будь дружелюбным и профессиональным
                    
                    2. Когда уверенность >= 90% (финальный ответ):
                       - Проанализируй ВСЮ собранную информацию из диалога
                       - Составь ДЕТАЛЬНОЕ и ПОЛНОЦЕННОЕ техническое задание с анализом и рекомендациями, а НЕ простую агрегацию ответов
                       - КРИТИЧЕСКИ ВАЖНО: Выдай ответ СТРОГО и ТОЛЬКО в формате JSON, БЕЗ дополнительного текста до или после JSON
                       - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО использовать markdown код блоки (```json или ```) - выводи ТОЛЬКО чистый JSON
                       - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО добавлять пояснения, комментарии, предисловия или любой текст перед JSON
                       - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО оборачивать JSON в markdown блоки
                       - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО начинать ответ словами типа "Вот техническое задание:", "Ниже представлено ТЗ:", "Готово:", и т.д.
                       - Твой ответ должен быть ТОЛЬКО JSON - ни одного символа до { и ни одного символа после }
                       - Ответ должен быть валидным JSON со следующей структурой (начинаться с { и заканчиваться }):
                       {
                         "questionText": "исходный вопрос/запрос пользователя, для которого собирались требования",
                         "requirements": "детальное и полное техническое задание с анализом всех собранных требований",
                         "recommendations": "детальные профессиональные рекомендации по реализации на основе собранных данных",
                         "confidence": число от 0.9 до 1.0
                       }
                       - Твой ответ должен начинаться строго с символа { и заканчиваться строго символом }
                       - ПЕРЕД ОТПРАВКОЙ проверь: начинается ли твой ответ с { ? Если нет - удали весь текст до {
                       - ПЕРЕД ОТПРАВКОЙ проверь: заканчивается ли твой ответ на } ? Если нет - проверь, нет ли текста после }
                       - ВАЖНО: Если ты начинаешь писать ```json, "Вот ТЗ:", "Ниже представлено:" или любой другой текст перед JSON, ОСТАНОВИСЬ и удали его - выводи только чистый JSON
                       - ТВОЙ ОТВЕТ ДОЛЖЕН БЫТЬ ИДЕАЛЬНО ЧИСТЫМ JSON БЕЗ ЛЮБОГО ЛИШНЕГО ТЕКСТА
                       - В поле questionText укажи ИЗНАЧАЛЬНЫЙ вопрос пользователя (тот, с которого начался сбор требований)
                       - В поле requirements составь ДЕТАЛЬНОЕ и ПОЛНОЦЕННОЕ ТЗ, которое должно включать:
                         * Анализ собранных требований и предпочтений пользователя
                         * Структурированное и детальное описание задачи с учетом всех ответов
                         * Детальное техническое задание с конкретными спецификациями
                         * Учет всех нюансов и предпочтений, выявленных в процессе сбора информации
                         * Конкретные технические детали, архитектурные решения, если применимо
                       - В поле recommendations составь ДЕТАЛЬНЫЕ профессиональные рекомендации:
                         * Рекомендации по реализации на основе собранных данных
                         * Рекомендации по технологиям и инструментам, если применимо
                         * Рекомендации по архитектуре и дизайну, если применимо
                         * Рекомендации по процессу разработки, если применимо
                         * Любые другие экспертные рекомендации, которые помогут в реализации
                       - НЕ просто перечисляй ответы пользователя - анализируй их, структурируй, добавляй экспертные рекомендации и детали
                       - Составляй ТЗ как опытный аналитик, который создает детальный документ для разработки
                       - В поле confidence укажи уровень уверенности (число от 0.9 до 1.0, например 0.95)
                    
                    ПРАВИЛА ОПРЕДЕЛЕНИЯ УВЕРЕННОСТИ:
                    - 0-30% - только исходный вопрос, нет уточнений
                    - 31-50% - получены базовые уточнения, но много неясностей
                    - 51-70% - собрана значительная часть информации, остались некоторые детали
                    - 71-89% - почти вся информация собрана, нужны финальные уточнения
                    - 90-100% - достаточно информации для составления полного ТЗ
                    
                    ВАЖНО:
                    - Во время сбора требований (уверенность < 90%) НИКОГДА не используй JSON формат - отвечай только текстом
                    - Задавай строго ОДИН вопрос за раз, БЕЗ нумерации, БЕЗ перечисления нескольких вопросов
                    - Если начинаешь задавать второй вопрос, ОСТАНОВИСЬ и оставь только один
                    - Финальный ответ (уверенность >= 90%) должен быть ТОЛЬКО валидным JSON, БЕЗ дополнительного текста до или после
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО использовать markdown код блоки (```json или ```) в финальном JSON ответе
                    - КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО добавлять пояснения, комментарии или дополнительный текст к JSON
                    - В финальном JSON все значения должны быть строками (кроме confidence - число)
                    - JSON должен быть валидным и корректно экранированным
                    - ФОРМАТ ОТВЕТА ОПРЕДЕЛЯЕТ ОТОБРАЖЕНИЕ UI: только финальный JSON-ответ позволяет клиенту отобразить кнопку "Просмотреть JSON", промежуточные текстовые ответы не должны содержать JSON и не должны вызывать отображение этой кнопки
                    
                    ПОВТОРЯЮ ЕЩЕ РАЗ ДЛЯ ЯСНОСТИ:
                    - ФИНАЛЬНЫЙ ОТВЕТ = ТОЛЬКО JSON, НИЧЕГО БОЛЬШЕ
                    - ОДИН ВОПРОС = ОДНО СООБЩЕНИЕ, НЕ БОЛЬШЕ
                    - Если пишешь текст перед JSON - УДАЛИ ЕГО
                    - Если пишешь несколько вопросов - ОСТАВЬ ТОЛЬКО ОДИН
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
                maxTokens = 2000 // Увеличиваем для длинных ТЗ
            )

            val response = openAIService.createChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                val botResponse = responseBody.choices.firstOrNull()?.message?.content
                    ?: "Извините, не удалось получить ответ."
                
                val responseTime = System.currentTimeMillis() - startTime
                val tokensUsed = responseBody.usage.totalTokens
                val timestamp = System.currentTimeMillis()
                
                // Пытаемся определить, является ли ответ JSON (финальный ТЗ) или текстовым (сбор требований)
                Log.d("ChatRepository", "=== Парсинг ответа от бота ===")
                Log.d("ChatRepository", "botResponse length: ${botResponse.length}")
                Log.d("ChatRepository", "botResponse preview: ${botResponse.take(200)}...")
                
                val parseResult = parseResponse(botResponse, userMessage)
                
                Log.d("ChatRepository", "=== Результат парсинга ===")
                Log.d("ChatRepository", "isRequirementsResponse: ${parseResult.isRequirementsResponse}")
                Log.d("ChatRepository", "questionText: ${parseResult.questionText}")
                Log.d("ChatRepository", "requirements: ${if (parseResult.requirements != null) "present (${parseResult.requirements?.length} chars)" else "null"}")
                Log.d("ChatRepository", "recommendations: ${if (parseResult.recommendations != null) "present (${parseResult.recommendations?.length} chars)" else "null"}")
                Log.d("ChatRepository", "confidence: ${parseResult.confidence}")
                
                val metadata = ResponseMetadata(
                    questionText = parseResult.questionText,
                    answerText = parseResult.answerText,
                    language = parseResult.language,
                    timestamp = timestamp,
                    responseTimeMs = responseTime,
                    tokensUsed = tokensUsed,
                    status = parseResult.status,
                    botTimestamp = parseResult.botTimestamp,
                    answerLength = parseResult.answerLength,
                    category = parseResult.category,
                    confidence = parseResult.confidence,
                    requirements = parseResult.requirements,
                    recommendations = parseResult.recommendations,
                    isRequirementsResponse = parseResult.isRequirementsResponse
                )
                
                Log.d("ChatRepository", "=== Созданная ResponseMetadata ===")
                Log.d("ChatRepository", "metadata.isRequirementsResponse: ${metadata.isRequirementsResponse}")
                
                // Возвращаем текст ответа для отображения в чате
                Result.success(Pair(parseResult.displayText, metadata))
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
        val confidence: Double? = null,
        val requirements: String? = null,
        val recommendations: String? = null,
        val isRequirementsResponse: Boolean = false,
        val displayText: String // Текст для отображения в чате
    )
    
    private fun parseResponse(botResponse: String, userMessage: String): ParsedResponse {
        // Сначала пытаемся определить, является ли ответ JSON (финальный ТЗ)
        // Проверяем, содержит ли ответ JSON структуру (начинается с { или обернут в markdown)
        val trimmedResponse = botResponse.trim()
        val looksLikeJson = trimmedResponse.startsWith("{") || 
                           trimmedResponse.startsWith("```json") || 
                           trimmedResponse.startsWith("```") ||
                           trimmedResponse.contains("\"questionText\"") ||
                           trimmedResponse.contains("\"requirements\"") ||
                           trimmedResponse.contains("\"recommendations\"") ||
                           trimmedResponse.contains('{') // Если есть хотя бы открывающая скобка
        
        // Проверяем, нет ли нескольких вопросов в текстовом ответе
        if (!looksLikeJson) {
            val questionCount = countQuestions(botResponse)
            if (questionCount > 1) {
                Log.w("ChatRepository", "⚠️ ОБНАРУЖЕНО НЕСКОЛЬКО ВОПРОСОВ В ОДНОМ СООБЩЕНИИ: $questionCount")
                Log.w("ChatRepository", "Текст ответа: $botResponse")
            }
        }
        
        if (looksLikeJson) {
            val requirementsResult = tryParseRequirementsResponse(botResponse, userMessage)
            if (requirementsResult != null) {
                return requirementsResult
            }
        }
        
        // Если это не JSON с ТЗ, значит это текстовый ответ (сбор требований)
        val language = detectLanguage(botResponse)
        return ParsedResponse(
            questionText = userMessage,
            answerText = botResponse,
            language = language,
            status = "success",
            answerLength = botResponse.length,
            isRequirementsResponse = false,
            displayText = botResponse
        )
    }
    
    private fun countQuestions(text: String): Int {
        // Подсчитываем количество знаков вопроса, исключая многоточия и сокращения
        var count = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '?') {
                // Проверяем, не является ли это частью многоточия (???) или сокращения
                val before = if (i > 0) text[i - 1] else ' '
                val after = if (i < text.length - 1) text[i + 1] else ' '
                
                // Если это не повторяющийся знак вопроса, считаем как отдельный вопрос
                if (before != '?' && after != '?') {
                    count++
                }
                i++
            } else {
                i++
            }
        }
        return count
    }
    
    private fun tryParseRequirementsResponse(botResponse: String, userMessage: String): ParsedResponse? {
        return try {
            // Пытаемся найти JSON в ответе (может быть обернут в markdown код блоки или иметь текст перед ним)
            var jsonString = botResponse.trim()
            
            Log.d("ChatRepository", "=== Начало парсинга JSON ===")
            Log.d("ChatRepository", "Исходный ответ длина: ${botResponse.length}")
            Log.d("ChatRepository", "Исходный ответ первые 300 символов: ${botResponse.take(300)}")
            
            // Проверяем, есть ли текст перед JSON
            val jsonStartIndex = jsonString.indexOf('{')
            if (jsonStartIndex > 0) {
                val textBeforeJson = jsonString.substring(0, jsonStartIndex).trim()
                Log.w("ChatRepository", "⚠️ ОБНАРУЖЕН ТЕКСТ ПЕРЕД JSON (${textBeforeJson.length} символов): $textBeforeJson")
                Log.w("ChatRepository", "Извлекаю JSON из позиции $jsonStartIndex")
            }
            
            // Более агрессивное удаление markdown блоков
            // Убираем markdown код блоки в начале
            jsonString = jsonString.replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            jsonString = jsonString.replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
            // Убираем markdown код блоки в конце
            jsonString = jsonString.replace(Regex("\\s*```$", RegexOption.MULTILINE), "")
            jsonString = jsonString.trim()
            
            // Пытаемся найти JSON объект даже если есть лишний текст
            // Ищем начало JSON объекта (первая открывающая скобка)
            val jsonStart = jsonString.indexOf('{')
            if (jsonStart < 0) {
                Log.d("ChatRepository", "✗ JSON объект не найден (нет открывающей скобки {)")
                return null
            }
            
            if (jsonStart > 0) {
                // Есть текст перед JSON - логируем и обрезаем
                val prefixText = jsonString.substring(0, jsonStart).trim()
                Log.w("ChatRepository", "⚠️ Обнаружен текст перед JSON: \"$prefixText\"")
                Log.w("ChatRepository", "Удаляю текст перед JSON, начинаю парсинг с позиции $jsonStart")
            }
            
            // Ищем конец JSON объекта (соответствующая закрывающая скобка)
            var braceCount = 0
            var jsonEnd = jsonStart
            var inString = false
            var escapeNext = false
            
            for (i in jsonStart until jsonString.length) {
                val char = jsonString[i]
                
                if (escapeNext) {
                    escapeNext = false
                    continue
                }
                
                when (char) {
                    '\\' -> escapeNext = true
                    '"' -> inString = !inString
                    '{' -> if (!inString) braceCount++
                    '}' -> {
                        if (!inString) {
                            braceCount--
                            if (braceCount == 0) {
                                jsonEnd = i + 1
                                break
                            }
                        }
                    }
                }
            }
            
            if (braceCount != 0) {
                Log.w("ChatRepository", "⚠️ Несбалансированные скобки: braceCount = $braceCount")
                // Пытаемся найти последнюю закрывающую скобку
                val lastBraceIndex = jsonString.lastIndexOf('}')
                if (lastBraceIndex > jsonStart) {
                    jsonEnd = lastBraceIndex + 1
                    Log.w("ChatRepository", "Использую последнюю закрывающую скобку на позиции $lastBraceIndex")
                } else {
                    Log.d("ChatRepository", "✗ Не удалось найти конец JSON объекта")
                    return null
                }
            }
            
            if (jsonEnd > jsonStart) {
                val extractedJson = jsonString.substring(jsonStart, jsonEnd)
                if (jsonStart > 0 || jsonEnd < jsonString.length) {
                    Log.w("ChatRepository", "✓ JSON извлечен из ответа с текстом до/после")
                    Log.d("ChatRepository", "Извлеченный JSON длина: ${extractedJson.length}")
                }
                jsonString = extractedJson
            } else {
                Log.d("ChatRepository", "✗ Не удалось извлечь JSON объект")
                return null
            }
            
            // Пытаемся распарсить как JSON
            val gson = Gson()
            val jsonObject = gson.fromJson(jsonString, Map::class.java) as Map<*, *>
            
            // Проверяем, соответствует ли структуре ТЗ (questionText, requirements, recommendations, confidence)
            val questionText = jsonObject["questionText"] as? String
            val requirements = jsonObject["requirements"] as? String
            val recommendations = jsonObject["recommendations"] as? String
            val confidence = when (val conf = jsonObject["confidence"]) {
                is Number -> conf.toDouble()
                is String -> conf.toDoubleOrNull()
                else -> null
            }
            
            // Если есть все необходимые поля для ТЗ (минимум questionText, requirements, confidence), значит это финальный ответ
            // recommendations может быть null, но если есть requirements и confidence >= 0.9, это финальный ответ
            Log.d("ChatRepository", "=== Проверка структуры JSON ===")
            Log.d("ChatRepository", "questionText: ${if (questionText != null) "present (${questionText.length} chars)" else "null"}")
            Log.d("ChatRepository", "requirements: ${if (requirements != null) "present (${requirements.length} chars)" else "null"}")
            Log.d("ChatRepository", "recommendations: ${if (recommendations != null) "present (${recommendations.length} chars)" else "null"}")
            Log.d("ChatRepository", "confidence: $confidence")
            
            if (questionText != null && requirements != null && confidence != null && confidence >= 0.9) {
                Log.d("ChatRepository", "✓ Определен финальный JSON ответ (ТЗ)")
                val language = detectLanguage(requirements + (recommendations ?: ""))
                // Создаем форматированный JSON для отображения (чистый JSON без markdown)
                val jsonBuilder = GsonBuilder().setPrettyPrinting().create()
                val jsonForDisplay = mutableMapOf<String, Any>(
                    "questionText" to questionText,
                    "requirements" to requirements,
                    "confidence" to confidence
                )
                // Добавляем recommendations только если оно есть
                if (recommendations != null) {
                    jsonForDisplay["recommendations"] = recommendations
                }
                val formattedJson = jsonBuilder.toJson(jsonForDisplay)
                
                Log.d("ChatRepository", "formattedJson length: ${formattedJson.length}")
                
                // Формируем полный текст ответа (requirements + recommendations)
                val fullAnswerText = if (recommendations != null) {
                    "$requirements\n\nРекомендации:\n$recommendations"
                } else {
                    requirements
                }
                
                return ParsedResponse(
                    questionText = questionText,
                    answerText = fullAnswerText,
                    language = language,
                    status = "success",
                    answerLength = fullAnswerText.length,
                    confidence = confidence,
                    requirements = requirements,
                    recommendations = recommendations,
                    isRequirementsResponse = true,
                    displayText = formattedJson // Отображаем форматированный JSON (чистый, без markdown)
                )
            } else {
                Log.d("ChatRepository", "✗ Не соответствует структуре ТЗ:")
                Log.d("ChatRepository", "  - questionText is null: ${questionText == null}")
                Log.d("ChatRepository", "  - requirements is null: ${requirements == null}")
                Log.d("ChatRepository", "  - confidence is null or < 0.9: ${confidence == null || (confidence != null && confidence < 0.9)}")
            }
            
            null // Не соответствует структуре ТЗ
        } catch (e: Exception) {
            // Если не удалось распарсить, возвращаем null
            null // Не удалось распарсить как JSON или не соответствует структуре
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
