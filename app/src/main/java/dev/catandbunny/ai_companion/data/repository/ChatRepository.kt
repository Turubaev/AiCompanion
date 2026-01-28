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
import dev.catandbunny.ai_companion.utils.CostCalculator
import dev.catandbunny.ai_companion.utils.TokenCounter
import dev.catandbunny.ai_companion.mcp.github.McpRepository
import dev.catandbunny.ai_companion.mcp.github.McpToolDetector
import dev.catandbunny.ai_companion.mcp.github.McpToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(private val apiKey: String) {
    private val openAIService = RetrofitClient.openAIService
    private val mcpRepository = McpRepository()

    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        systemPromptText: String,
        temperature: Double = 0.7,
        model: String = "gpt-3.5-turbo"
    ): Result<Pair<String, ResponseMetadata>> {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Проверяем нужен ли MCP инструмент
            var mcpContext = ""
            val needsMcp = McpToolDetector.needsMcpTool(userMessage)
            Log.d("ChatRepository", "=== MCP Tool Detection ===")
            Log.d("ChatRepository", "User message: $userMessage")
            Log.d("ChatRepository", "Needs MCP tool: $needsMcp")
            
            if (needsMcp) {
                Log.d("ChatRepository", "Вызываем MCP инструмент...")
                mcpContext = withContext(Dispatchers.IO) {
                    executeMcpTool(userMessage)
                }
                Log.d("ChatRepository", "MCP результат (первые 200 символов): ${mcpContext.take(200)}...")
            } else {
                Log.d("ChatRepository", "MCP инструмент не требуется")
            }
            
            // Формируем расширенный системный промпт с информацией о MCP
            val enhancedSystemPrompt = buildString {
                append(systemPromptText)
                if (mcpContext.isNotEmpty()) {
                    append("\n\n")
                    append("=== Данные из GitHub (через MCP) ===\n")
                    append(mcpContext)
                    append("\nИспользуй эту информацию для ответа на вопрос пользователя.")
                } else {
                    // Добавляем информацию о доступных инструментах
                    append("\n\n")
                    append("=== Доступные инструменты GitHub (через MCP) ===\n")
                    append("У тебя есть доступ к GitHub через MCP инструменты. Ты можешь использовать их для:\n")
                    append("- Получения списка репозиториев (спроси: 'покажи мои репозитории')\n")
                    append("- Получения информации о репозитории (укажи owner/repo)\n")
                    append("- Чтения файлов из репозиториев\n")
                    append("- Поиска в коде\n")
                    append("- Просмотра issues\n")
                    append("Когда пользователь просит что-то связанное с GitHub, используй доступные инструменты.")
                }
            }
            
            // Системный промпт - получаем из параметров
            val systemPrompt = Message(
                role = "system",
                content = enhancedSystemPrompt
            )
            
            // Формируем историю сообщений для контекста
            Log.d("ChatRepository", "=== sendMessage ===")
            Log.d("ChatRepository", "conversationHistory.size: ${conversationHistory.size}")
            conversationHistory.forEachIndexed { index, message ->
                Log.d("ChatRepository", "conversationHistory[$index]: isSummary=${message.isSummary}, isFromUser=${message.isFromUser}, text=${message.text.take(150)}...")
            }
            
            val summaryMessages = conversationHistory.filter { it.isSummary }
            val regularMessages = conversationHistory.filter { !it.isSummary }
            
            val messages = buildList {
                add(systemPrompt)
                
                if (summaryMessages.isNotEmpty()) {
                    val summaryText = summaryMessages.joinToString("\n\n") { 
                        "Резюме предыдущего разговора:\n${it.text}"
                    }
                    add(Message(
                        role = "system",
                        content = summaryText
                    ))
                }
                
                addAll(regularMessages.map { chatMessage ->
                    Message(
                        role = if (chatMessage.isFromUser) "user" else "assistant",
                        content = chatMessage.text
                    )
                })
                
                add(Message(role = "user", content = userMessage))
            }
            
            Log.d("ChatRepository", "=== Формируем messages для API ===")
            Log.d("ChatRepository", "Всего messages: ${messages.size}")
            messages.forEachIndexed { index, message ->
                Log.d("ChatRepository", "messages[$index]: role=${message.role}, content=${message.content.take(150)}...")
            }

            val request = OpenAIRequest(
                model = model,
                messages = messages,
                temperature = temperature,
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
                val promptTokens = responseBody.usage.promptTokens
                val completionTokens = responseBody.usage.completionTokens
                val timestamp = System.currentTimeMillis()
                
                // Ручной подсчет токенов для ответа бота
                val manualTokenCount = TokenCounter.countTokens(botResponse)
                
                // Рассчитываем стоимость
                val (costUSD, costRUB) = CostCalculator.calculateCost(
                    model = model,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens
                )
                
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
                
                val costFormatted = CostCalculator.formatCost(costUSD, costRUB)
                
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
                    isRequirementsResponse = parseResult.isRequirementsResponse,
                    manualTokenCount = manualTokenCount,
                    costFormatted = costFormatted,
                    promptTokens = promptTokens
                )
                
                Log.d("ChatRepository", "=== Созданная ResponseMetadata ===")
                Log.d("ChatRepository", "metadata.isRequirementsResponse: ${metadata.isRequirementsResponse}")
                
                // Возвращаем текст ответа без метаданных (метаданные отображаются под сообщением)
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
    
    /**
     * Выполняет MCP инструмент на основе запроса пользователя
     */
    private suspend fun executeMcpTool(userMessage: String): String {
        return try {
            // Инициализируем подключение если еще не подключены
            if (!mcpRepository.isConnected()) {
                val initResult = mcpRepository.initialize()
                if (initResult.isFailure) {
                    return "Ошибка подключения к MCP серверу: ${initResult.exceptionOrNull()?.message}"
                }
            }
            
            // Получаем список доступных инструментов
            val toolsResult = mcpRepository.getAvailableTools()
            if (toolsResult.isFailure) {
                return "Ошибка получения списка инструментов: ${toolsResult.exceptionOrNull()?.message}"
            }
            
            val availableTools = toolsResult.getOrNull() ?: emptyList()
            Log.d("ChatRepository", "Доступные инструменты: ${availableTools.map { it.name }}")
            
            val toolType = McpToolDetector.detectTool(userMessage)
            if (toolType == null) {
                return "Не удалось определить нужный инструмент для запроса."
            }
            
            // Находим нужный инструмент по типу
            val toolName = when (toolType) {
                McpToolType.LIST_REPOSITORIES -> {
                    // Для списка репозиториев используем search_repositories
                    availableTools.find { 
                        it.name == "search_repositories" || 
                        (it.name.contains("search", ignoreCase = true) && it.name.contains("repo", ignoreCase = true))
                    }?.name ?: "search_repositories"
                }
                McpToolType.GET_REPOSITORY_INFO -> {
                    // Информация о репозитории - можно использовать search_repositories с конкретным именем
                    availableTools.find { 
                        it.name == "search_repositories"
                    }?.name ?: "search_repositories"
                }
                McpToolType.GET_FILE_CONTENT -> {
                    availableTools.find { 
                        it.name == "get_file_contents" || 
                        (it.name.contains("file", ignoreCase = true) && it.name.contains("content", ignoreCase = true))
                    }?.name ?: "get_file_contents"
                }
                McpToolType.SEARCH -> {
                    availableTools.find { 
                        it.name == "search_code" || 
                        (it.name.contains("search", ignoreCase = true) && it.name.contains("code", ignoreCase = true))
                    }?.name ?: "search_code"
                }
                McpToolType.LIST_ISSUES -> {
                    availableTools.find { 
                        it.name == "list_issues" || 
                        (it.name.contains("list", ignoreCase = true) && it.name.contains("issue", ignoreCase = true))
                    }?.name ?: "list_issues"
                }
            }
            
            if (toolName == null) {
                return "Инструмент для запроса не найден. Доступные инструменты: ${availableTools.map { it.name }.joinToString(", ")}"
            }
            
            Log.d("ChatRepository", "Используем инструмент: $toolName")
            
            val params = McpToolDetector.extractParameters(userMessage, toolType)
            
            // Вызываем инструмент напрямую через mcpRepository
            val service = mcpRepository.getGitHubService()
                ?: return "MCP сервис не инициализирован"
            
            val result = when (toolType) {
                McpToolType.LIST_REPOSITORIES -> {
                    // Для search_repositories используем query для поиска репозиториев
                    // GitHub API требует непустой query и не принимает "*"
                    // Если owner указан, ищем репозитории этого пользователя
                    val owner = params["owner"]
                    if (owner == null) {
                        // Если owner не указан, возвращаем сообщение для бота
                        return "Для получения списка репозиториев необходимо указать GitHub username пользователя. " +
                               "Попросите пользователя указать его GitHub username или используйте формат: " +
                               "'Покажи репозитории пользователя USERNAME' или 'Список репозиториев @USERNAME'"
                    }
                    
                    val query = "user:$owner"
                    val arguments = mapOf(
                        "query" to query,
                        "perPage" to 20
                    )
                    Log.d("ChatRepository", "Вызываем search_repositories с query: '$query' для owner: $owner")
                    service.callTool(toolName, arguments)
                }
                McpToolType.GET_REPOSITORY_INFO -> {
                    val owner = params["owner"] ?: return "Не указан owner репозитория"
                    val repo = params["repo"] ?: return "Не указан repo"
                    service.callTool(toolName, mapOf("owner" to owner, "repo" to repo))
                }
                McpToolType.GET_FILE_CONTENT -> {
                    val owner = params["owner"] ?: return "Не указан owner репозитория"
                    val repo = params["repo"] ?: return "Не указан repo"
                    val path = params["path"] ?: "README.md"
                    service.callTool(toolName, mapOf("owner" to owner, "repo" to repo, "path" to path))
                }
                McpToolType.SEARCH -> {
                    val owner = params["owner"] ?: return "Не указан owner репозитория"
                    val repo = params["repo"] ?: return "Не указан repo"
                    val query = userMessage.substringAfter("найти").substringAfter("поиск")
                        .substringAfter("search").trim().takeIf { it.isNotEmpty() } ?: "function"
                    service.callTool(toolName, mapOf("owner" to owner, "repo" to repo, "query" to query))
                }
                McpToolType.LIST_ISSUES -> {
                    val owner = params["owner"] ?: return "Не указан owner репозитория"
                    val repo = params["repo"] ?: return "Не указан repo"
                    service.callTool(toolName, mapOf("owner" to owner, "repo" to repo, "state" to "open", "limit" to 10))
                }
            }
            
            result.fold(
                onSuccess = { toolResult ->
                    toolResult.content
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }
                },
                onFailure = { "Ошибка выполнения MCP инструмента: ${it.message}" }
            )
        } catch (e: Exception) {
            Log.e("ChatRepository", "Ошибка выполнения MCP инструмента", e)
            "Ошибка выполнения MCP инструмента: ${e.message}"
        }
    }
}
