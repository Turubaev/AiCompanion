package dev.catandbunny.ai_companion.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import dev.catandbunny.ai_companion.BuildConfig
import dev.catandbunny.ai_companion.data.api.RetrofitClient
import dev.catandbunny.ai_companion.data.model.Message
import dev.catandbunny.ai_companion.data.model.OpenAIRequest
import dev.catandbunny.ai_companion.model.ChatMessage
import dev.catandbunny.ai_companion.model.ResponseMetadata
import dev.catandbunny.ai_companion.utils.CostCalculator
import dev.catandbunny.ai_companion.utils.TokenCounter
import dev.catandbunny.ai_companion.mcp.github.McpRepository
import dev.catandbunny.ai_companion.mcp.github.McpToolConverter
import dev.catandbunny.ai_companion.data.model.Tool
import dev.catandbunny.ai_companion.data.model.ToolCall
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatRepository(
    private val apiKey: String,
    private val getTelegramChatId: () -> String = { "" },
    private val getRagEnabled: () -> Boolean = { false },
    private val getRagMinScore: () -> Double = { 0.0 },
    private val getRagUseReranker: () -> Boolean = { false },
    private val getSupportUserEmail: () -> String = { "" },
    private val getAutoIncludeSupportContext: () -> Boolean = { false }
) {
    private val openAIService = RetrofitClient.openAIService
    private val mcpRepository = McpRepository()

    // Первый запрос к RAG может быть долгим: загрузка модели и индекса на сервере (15–60 с)
    // RAG-сервер с reranker может отвечать 60+ с (загрузка моделей, первый запрос, тяжёлый поиск)
    private val ragClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * @param useRagForThisRequest если true — принудительно использовать RAG для этого запроса (например для /help); null — следовать настройке getRagEnabled()
     */
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        systemPromptText: String,
        temperature: Double = 0.7,
        model: String = "gpt-3.5-turbo",
        useRagForThisRequest: Boolean? = null
    ): Result<Pair<String, ResponseMetadata>> {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Инициализируем MCP если нужно и загружаем инструменты
            var mcpTools: List<Tool>? = null
            withContext(Dispatchers.IO) {
                if (!mcpRepository.isConnected()) {
                    val initResult = mcpRepository.initialize()
                    if (initResult.isFailure) {
                        Log.w("ChatRepository", "Не удалось инициализировать MCP: ${initResult.exceptionOrNull()?.message}")
                    }
                }
                
                // Всегда пытаемся загрузить инструменты, если подключение активно
                if (mcpRepository.isConnected()) {
                    var toolsResult = mcpRepository.getAvailableTools()
                    if (toolsResult.isFailure) {
                        Log.w("ChatRepository", "Не удалось загрузить MCP инструменты: ${toolsResult.exceptionOrNull()?.message}, переподключаемся и повторяем")
                        mcpRepository.disconnect()
                        delay(1000)
                        val reInit = mcpRepository.initialize()
                        if (reInit.isSuccess) {
                            toolsResult = mcpRepository.getAvailableTools()
                        }
                    }
                    if (toolsResult.isSuccess) {
                        val mcpToolList = toolsResult.getOrNull() ?: emptyList()
                        mcpTools = McpToolConverter.convertToOpenAITools(mcpToolList)
                        Log.d("ChatRepository", "Загружено ${mcpTools?.size} MCP инструментов для Function Calling: ${mcpTools?.map { it.function.name }}")
                    } else {
                        Log.w("ChatRepository", "Не удалось загрузить MCP инструменты после retry: ${toolsResult.exceptionOrNull()?.message}")
                    }
                } else {
                    Log.w("ChatRepository", "MCP сервер не подключен, инструменты недоступны")
                }
            }

            // RAG: поиск релевантных чанков; контекст добавляем отдельным сообщением прямо перед вопросом
            var ragContextMessage: String? = null
            var ragSourceForFallback: String? = null
            val useRag = useRagForThisRequest == true || (useRagForThisRequest != false && getRagEnabled())
            if (useRag) {
                val ragChunks = withContext<List<RagChunk>>(Dispatchers.IO) {
                    fetchRagChunks(userMessage)
                }
                if (ragChunks.isNotEmpty()) {
                    val sourceCounts = ragChunks.mapNotNull { it.source?.takeIf { it.isNotBlank() } }.groupingBy { it }.eachCount()
                    ragSourceForFallback = sourceCounts.maxWithOrNull(
                        compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                    )?.key ?: "база знаний"
                    val contextBlock = ragChunks.joinToString("\n\n---\n\n") { chunk ->
                        val source = chunk.source?.takeIf { it.isNotBlank() } ?: "база знаний"
                        "[Источник: $source]\n${chunk.text}"
                    }
                    ragContextMessage = """
                        ВНИМАНИЕ: Ниже — фрагменты из базы знаний с указанием источника для каждого. Отвечай в первую очередь на основе этих фрагментов; не подменяй их общими знаниями из обучения.
                        ОБЯЗАТЕЛЬНО: в начале ответа или после первого предложения укажи источник в формате [Источник: имя_документа]. Для утверждений из контекста — имя файла (например Test_sample.pdf). Если утверждение не опирается на фрагменты, а на общие знания, указывай [Источник: Интернет]. Без указания источника ответ считается неполным.

                        $contextBlock
                    """.trimIndent()
                    Log.d("ChatRepository", "RAG: добавлено ${ragChunks.size} чанков в контекст")
                } else {
                    ragSourceForFallback = "Интернет"
                    ragContextMessage = """
                        В базе знаний нет релевантных фрагментов для этого запроса. Отвечай на основе общих знаний.
                        ОБЯЗАТЕЛЬНО: указывай источник в формате [Источник: Интернет].
                    """.trimIndent()
                    Log.d("ChatRepository", "RAG: чанки не получены (сервис недоступен или пустой ответ) — источник: Интернет")
                }
            }
            
            // Контекст поддержки (тикеты, история) — если включён и указан email
            var supportContextMessage: String? = null
            val supportEmail = getSupportUserEmail().trim()
            if (getAutoIncludeSupportContext() && supportEmail.isNotBlank()) {
                val ctx = withContext(Dispatchers.IO) { getSupportContext(supportEmail) }
                if (ctx != null) {
                    supportContextMessage = buildSupportContextText(ctx)
                    Log.d("ChatRepository", "Support context added for $supportEmail")
                }
            }

            // Системный промпт (без RAG — RAG идёт отдельным сообщением перед вопросом)
            val systemPrompt = Message(
                role = "system",
                content = systemPromptText
            )
            
            // Формируем историю сообщений для контекста
            Log.d("ChatRepository", "=== sendMessage ===")
            Log.d("ChatRepository", "conversationHistory.size: ${conversationHistory.size}")
            
            val summaryMessages = conversationHistory.filter { it.isSummary }
            val regularMessages = conversationHistory.filter { !it.isSummary }
            
            var messages = buildList {
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
                // Подсказка для send_telegram_message, если пользователь просил рекомендации в Telegram
                if (mcpTools?.any { it.function.name == "send_telegram_message" } == true) {
                    add(Message(
                        role = "system",
                        content = """
                        Если пользователь просит отправить рекомендации по инвестициям в Telegram:
                        1. СНАЧАЛА вызови get_instruments_for_budget (budget_rub=сумма пользователя), чтобы получить реальные бумаги (акции, облигации, ETF).
                        2. Сформируй полноценные рекомендации на основе данных (тикеры, цены, сколько лотов купить, как распределить бюджет).
                        3. Вызови send_telegram_message с этим ПОЛНЫМ текстом рекомендаций (а не фразой «рекомендации отправлены»).
                        4. В чате напиши только короткое подтверждение: «Рекомендации отправлены в Telegram», без дублирования текста рекомендаций.
                        """.trimIndent()
                    ))
                }
                // Подсказка для control_android_emulator: при запросе демо на эмуляторе — action=simulate_user_flow
                if (mcpTools?.any { it.function.name == "control_android_emulator" } == true) {
                    add(Message(
                        role = "system",
                        content = """
                        Для демо на эмуляторе (открыть приложение, отправить сообщение про инвестиции, записать экран) вызывай control_android_emulator с action="simulate_user_flow". Другие action: start_emulator, record_screen, stop_recording, get_recording_path. Не используй test_message или recording_start как action — это параметры, не действия.
                        """.trimIndent()
                    ))
                }
                // Контекст поддержки пользователя (тикеты, история)
                if (supportContextMessage != null) {
                    add(Message(role = "system", content = supportContextMessage))
                }
                
                addAll(regularMessages.map { chatMessage ->
                    Message(
                        role = if (chatMessage.isFromUser) "user" else "assistant",
                        content = chatMessage.text
                    )
                })
                // RAG: контекст из базы знаний сразу перед вопросом — модель сильнее опирается на него
                if (ragContextMessage != null) {
                    add(Message(role = "system", content = ragContextMessage))
                }
                add(Message(role = "user", content = userMessage))
            }
            
            Log.d("ChatRepository", "=== Формируем messages для API ===")
            Log.d("ChatRepository", "Всего messages: ${messages.size}")
            Log.d("ChatRepository", "MCP tools: ${mcpTools?.size ?: 0}")

            // Цикл вызова функций (может быть несколько итераций)
            var maxIterations = 5
            var finalResponse: String? = null
            
            while (maxIterations > 0 && finalResponse == null) {
                val request = OpenAIRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = 2000,
                    tools = mcpTools
                )

                val response = openAIService.createChatCompletion(
                    authorization = "Bearer $apiKey",
                    request = request
                )

                if (response.isSuccessful && response.body() != null) {
                    val responseBody = response.body()!!
                    val choice = responseBody.choices.firstOrNull()
                    val assistantMessage = choice?.message
                    
                    // Проверяем есть ли tool_calls
                    val toolCalls = assistantMessage?.toolCalls
                    
                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        Log.d("ChatRepository", "OpenAI запросил вызов ${toolCalls.size} инструментов")
                        
                        // Добавляем сообщение ассистента с tool_calls
                        messages = messages + assistantMessage
                        
                        // Вызываем все запрошенные инструменты
                        val toolResults = withContext(Dispatchers.IO) {
                            toolCalls.map { toolCall ->
                                val toolName = toolCall.function.name
                                val argumentsJson = toolCall.function.arguments
                                
                                Log.d("ChatRepository", "Вызываем инструмент: $toolName с аргументами: $argumentsJson")
                                
                                val result = executeMcpToolByName(toolName, argumentsJson)
                                
                                Message(
                                    role = "tool",
                                    content = result,
                                    toolCallId = toolCall.id
                                )
                            }
                        }
                        
                        // Добавляем результаты инструментов
                        messages = messages + toolResults
                        
                        maxIterations--
                        continue // Повторяем запрос с результатами инструментов
                    } else {
                        // Нет tool_calls - это финальный ответ
                        finalResponse = assistantMessage?.content ?: "Извините, не удалось получить ответ."
                        
                        val responseTime = System.currentTimeMillis() - startTime
                        val tokensUsed = responseBody.usage.totalTokens
                        val promptTokens = responseBody.usage.promptTokens
                        val completionTokens = responseBody.usage.completionTokens
                        val timestamp = System.currentTimeMillis()
                        
                        // Ручной подсчет токенов для ответа бота
                        val manualTokenCount = TokenCounter.countTokens(finalResponse)
                        
                        // Рассчитываем стоимость
                        val (costUSD, costRUB) = CostCalculator.calculateCost(
                            model = model,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens
                        )
                        
                        // При включённом RAG показываем один основной источник: убираем все [Источник: ...] из ответа модели и дописываем один верный (по чанкам)
                        val responseToParse = if (ragSourceForFallback != null) {
                            val withoutCitations = Regex("\\[Источник:\\s*[^\\]]*\\]").replace(finalResponse, "").trim()
                            "$withoutCitations\n\n[Источник: $ragSourceForFallback]"
                        } else {
                            finalResponse
                        }
                        
                        // Пытаемся определить, является ли ответ JSON (финальный ТЗ) или текстовым (сбор требований)
                        Log.d("ChatRepository", "=== Парсинг ответа от бота ===")
                        Log.d("ChatRepository", "botResponse length: ${responseToParse.length}")
                        Log.d("ChatRepository", "botResponse preview: ${responseToParse.take(200)}...")
                        
                        val parseResult = parseResponse(responseToParse, userMessage)
                        
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
                        return Result.success(Pair(parseResult.displayText, metadata))
                    }
                } else {
                    val errorMessage = response.errorBody()?.string() 
                        ?: "Ошибка при отправке запроса: ${response.code()}"
                    return Result.failure(Exception(errorMessage))
                }
            }
            
            // Если вышли из цикла без ответа
            Result.failure(Exception("Превышено максимальное количество итераций вызова функций"))
        } catch (e: Exception) {
            Log.e("ChatRepository", "Ошибка в sendMessage", e)
            Result.failure(e)
        }
    }
    
    /**
     * Выполняет MCP инструмент по имени с JSON аргументами
     */
    private suspend fun executeMcpToolByName(toolName: String, argumentsJson: String): String {
        return try {
            val service = mcpRepository.getGitHubService()
                ?: return "Ошибка: MCP сервис не инициализирован"
            
            // Парсим JSON аргументы
            val gson = com.google.gson.Gson()
            val argumentsMap = gson.fromJson(argumentsJson, Map::class.java) as? Map<String, Any>
                ?: emptyMap()
            
            // Обрабатываем специальные случаи
            val arguments = when (toolName) {
                "search_repositories" -> {
                    val query = argumentsMap["query"]?.toString() ?: ""
                    if (query.contains("user:username") || query == "user:username") {
                        return "Для получения списка репозиториев необходимо указать реальный GitHub username. " +
                               "Пожалуйста, укажите ваш GitHub username, например: 'Покажи репозитории пользователя octocat' " +
                               "или 'Список репозиториев @username'"
                    }
                    argumentsMap
                }
                "send_telegram_message" -> {
                    val chatId = getTelegramChatId().trim()
                    if (chatId.isEmpty()) {
                        return "Telegram Chat ID не настроен. Пользователь может указать его в Настройках приложения (секция «Telegram для рекомендаций»)."
                    }
                    (argumentsMap.toMutableMap()).apply { put("chat_id", chatId) }
                }
                else -> argumentsMap
            }
            
            Log.d("ChatRepository", "Выполняем инструмент $toolName с аргументами: ${if (toolName == "send_telegram_message") "chat_id=***, text.length=${(arguments["text"] as? String)?.length}" else arguments}")
            
            val result = service.callTool(toolName, arguments)
            val toolResultStr = result.fold(
                onSuccess = { toolResult ->
                    val contentList = toolResult.content ?: emptyList()
                    val content = contentList
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }
                    
                    if (toolName == "send_telegram_message") {
                        Log.d("ChatRepository", "send_telegram_message: isError=${toolResult.isError}, content=$content")
                    }
                    
                    // Если результат пустой или содержит ошибку валидации, возвращаем понятное сообщение
                    if (content.contains("Validation Failed") || content.contains("cannot be searched")) {
                        "Не удалось найти репозитории. Возможно, указан неверный username или у вас нет доступа. " +
                        "Пожалуйста, укажите правильный GitHub username."
                    } else when (toolName) {
                        "send_telegram_message" -> {
                            if (toolResult.isError || content.lowercase().contains("error") || content.lowercase().contains("ошибка")) {
                                "Ошибка отправки в Telegram: $content"
                            } else {
                                "Сообщение успешно отправлено в Telegram. В чате напиши только короткое подтверждение пользователю (например: «Рекомендации отправлены в Telegram») и не дублируй текст рекомендаций."
                            }
                        }
                        else -> content
                    }
                },
                onFailure = { 
                    val errorMsg = it.message ?: "Неизвестная ошибка"
                    if (toolName == "send_telegram_message") {
                        Log.e("ChatRepository", "send_telegram_message ОШИБКА: $errorMsg", it)
                    }
                    if (toolName == "send_telegram_message") {
                        "Ошибка отправки в Telegram: $errorMsg. Сообщи пользователю, что не удалось отправить сообщение в Telegram."
                    } else if (errorMsg.contains("Validation Failed") || errorMsg.contains("cannot be searched")) {
                        "Не удалось найти репозитории. Пожалуйста, укажите правильный GitHub username, например: 'Покажи репозитории пользователя octocat'"
                    } else {
                        "Ошибка выполнения инструмента $toolName: $errorMsg"
                    }
                }
            )
            if (toolName == "send_telegram_message") {
                Log.d("ChatRepository", "send_telegram_message результат для модели: ${toolResultStr.take(80)}...")
            }
            toolResultStr
        } catch (e: Exception) {
            Log.e("ChatRepository", "Ошибка выполнения MCP инструмента $toolName", e)
            "Ошибка выполнения инструмента $toolName: ${e.message}"
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
     * Запрос к RAG-сервису: поиск релевантных чанков по вопросу пользователя.
     * Возвращает пустой список при ошибке или недоступности сервиса.
     */
    private fun fetchRagChunks(query: String): List<RagChunk> {
        if (query.isBlank()) return emptyList()
        val baseUrl = BuildConfig.RAG_SERVICE_URL.trimEnd('/')
        val url = "$baseUrl/search"
        // Больше чанков — выше шанс захватить нужный фрагмент (в диссертациях/PDF много имён, семантика размазана)
        val minScore = getRagMinScore().coerceIn(0.0, 1.0)
        val useReranker = getRagUseReranker()
        Log.d("ChatRepository", "RAG request: min_score=$minScore, use_reranker=$useReranker")
        val body = """{"query":${gson.toJson(query)},"top_k":10,"min_score":$minScore,"use_reranker":$useReranker}"""
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            ragClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ChatRepository", "RAG service error: ${response.code} ${response.message}")
                    return@use emptyList<RagChunk>()
                }
                val json = response.body?.string() ?: return@use emptyList<RagChunk>()
                val parsed = gson.fromJson(json, RagSearchResponse::class.java)
                val chunks = parsed.chunks ?: emptyList()
                val sources = chunks.mapNotNull { it.source?.takeIf { it.isNotBlank() } }.distinct()
                Log.d("ChatRepository", "RAG response: ${chunks.size} chunks (min_score=$minScore), sources=$sources")
                chunks
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "RAG fetch failed: ${e.message}")
            emptyList()
        }
    }

    private data class RagChunk(
        val text: String,
        val score: Double = 0.0,
        val source: String? = null  // имя документа для цитирования (например Test_sample.pdf)
    )
    private data class RagSearchResponse(val chunks: List<RagChunk>?)

    /**
     * Получить контекст поддержки пользователя по email (тикеты, история) через MCP get_user_support_context.
     * Возвращает null при ошибке или недоступности MCP.
     */
    suspend fun getSupportContext(userEmail: String): SupportContext? {
        if (userEmail.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val service = mcpRepository.getGitHubService() ?: return@withContext null
                val args = mapOf(
                    "user_email" to userEmail,
                    "include_tickets" to true,
                    "include_history" to true
                )
                val result = service.callTool("get_user_support_context", args)
                result.fold(
                    onSuccess = { toolResult ->
                        val contentList = toolResult.content ?: emptyList()
                        val text = contentList
                            .filter { it.type == "text" }
                            .joinToString("\n") { it.text ?: "" }
                        if (text.isBlank()) return@fold null
                        parseSupportContextJson(text)
                    },
                    onFailure = {
                        Log.w("ChatRepository", "get_user_support_context failed", it)
                        null
                    }
                )
            } catch (e: Exception) {
                Log.e("ChatRepository", "getSupportContext error", e)
                null
            }
        }
    }

    private fun parseSupportContextJson(jsonText: String): SupportContext? {
        return try {
            val json = JSONObject(jsonText)
            val userInfoObj = json.optJSONObject("user_info") ?: return null
            val userInfo = UserInfo(
                email = userInfoObj.optString("email", ""),
                name = userInfoObj.optString("name", ""),
                plan = userInfoObj.optString("plan", ""),
                created_at = userInfoObj.optString("created_at", "")
            )
            val ticketsArray = json.optJSONArray("open_tickets") ?: JSONArray()
            val openTickets = (0 until ticketsArray.length()).map { i ->
                val o = ticketsArray.getJSONObject(i)
                Ticket(
                    id = o.optString("id", ""),
                    subject = o.optString("subject", ""),
                    status = o.optString("status", ""),
                    created_at = o.optString("created_at", ""),
                    last_message = o.optString("last_message", "")
                )
            }
            val interactionsArray = json.optJSONArray("recent_interactions") ?: JSONArray()
            val recentInteractions = (0 until interactionsArray.length()).map { i ->
                val o = interactionsArray.getJSONObject(i)
                Interaction(
                    type = o.optString("type", ""),
                    timestamp = o.optString("timestamp", ""),
                    summary = o.optString("summary", "")
                )
            }
            SupportContext(user_info = userInfo, open_tickets = openTickets, recent_interactions = recentInteractions)
        } catch (e: Exception) {
            Log.w("ChatRepository", "parseSupportContextJson failed", e)
            null
        }
    }

    private fun buildSupportContextText(ctx: SupportContext): String {
        val ticketsBlock = ctx.open_tickets.joinToString("\n") { t ->
            "- #${t.id} ${t.subject} (${t.status}): ${t.last_message}"
        }
        val historyBlock = ctx.recent_interactions.joinToString("\n") { i ->
            "- ${i.type} (${i.timestamp}): ${i.summary}"
        }
        return """
            Контекст поддержки пользователя:
            Email: ${ctx.user_info.email}
            Имя: ${ctx.user_info.name}
            План: ${ctx.user_info.plan}
            
            Открытые тикеты:
            ${if (ticketsBlock.isBlank()) "Нет открытых тикетов" else ticketsBlock}
            
            Недавние обращения:
            ${if (historyBlock.isBlank()) "Нет данных" else historyBlock}
        """.trimIndent()
    }
}

// Data classes для контекста поддержки (CRM MCP)
data class SupportContext(
    val user_info: UserInfo,
    val open_tickets: List<Ticket>,
    val recent_interactions: List<Interaction>
)

data class UserInfo(
    val email: String,
    val name: String,
    val plan: String,
    val created_at: String
)

data class Ticket(
    val id: String,
    val subject: String,
    val status: String,
    val created_at: String,
    val last_message: String
)

data class Interaction(
    val type: String,
    val timestamp: String,
    val summary: String
)
