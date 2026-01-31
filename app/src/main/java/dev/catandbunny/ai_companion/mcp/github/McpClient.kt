package dev.catandbunny.ai_companion.mcp.github

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import dev.catandbunny.ai_companion.config.McpConfig
import dev.catandbunny.ai_companion.mcp.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клиент для подключения к MCP серверу через TCP
 */
class McpClient(
    private val host: String = McpConfig.MCP_SERVER_HOST,
    private val port: Int = McpConfig.MCP_SERVER_PORT
) {
    private val gson = Gson()
    private val requestIdCounter = AtomicInteger(1)
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "McpClient"
    }

    /**
     * Подключение к MCP серверу
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Подключение к MCP серверу $host:$port")
            socket = Socket(host, port).apply {
                soTimeout = (McpConfig.READ_TIMEOUT_SECONDS * 1000).toInt()
            }
            writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket!!.getOutputStream())), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            
            Log.d(TAG, "Подключение установлено")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения", e)
            Result.failure(e)
        }
    }

    /**
     * Инициализация MCP сервера
     */
    suspend fun initialize(): Result<InitializeResult> = withContext(Dispatchers.IO) {
        try {
            val requestId = requestIdCounter.getAndIncrement()
            val request = InitializeRequest(
                id = requestId,
                params = InitializeParams(
                    clientInfo = ClientInfo()
                )
            )
            Log.d(TAG, "initialize: requestId=$requestId")
            val response = sendRequest(request, InitializeResponse::class.java, expectedId = requestId)
            
            if (response.isSuccess) {
                isInitialized = true
                Log.d(TAG, "MCP сервер инициализирован: ${response.getOrNull()?.result?.serverInfo?.name}")
            }
            
            response.map { it.result }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации", e)
            Result.failure(e)
        }
    }

    /**
     * Получить список доступных инструментов
     */
    suspend fun listTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        try {
            val requestId = requestIdCounter.getAndIncrement()
            val request = McpRequest(
                id = requestId,
                method = "tools/list"
            )
            Log.d(TAG, "listTools: requestId=$requestId")
            val response = sendRequest(request, ListToolsResponse::class.java, expectedId = requestId)
            response.fold(
                onSuccess = { resp ->
                    val tools = resp.result?.tools
                    Log.d(TAG, "listTools: получено инструментов=${tools?.size ?: 0}, имена=${tools?.map { it.name }}")
                    tools?.let { Result.success(it) }
                        ?: Result.failure(IOException("MCP tools/list: некорректный ответ (result=null)"))
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения списка инструментов", e)
            Result.failure(e)
        }
    }

    /**
     * Вызвать инструмент
     */
    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>? = null
    ): Result<CallToolResult> = withContext(Dispatchers.IO) {
        try {
            val requestId = requestIdCounter.getAndIncrement()
            val request = CallToolRequest(
                id = requestId,
                params = CallToolParams(
                    name = toolName,
                    arguments = arguments
                )
            )
            
            val response = sendRequest(request, CallToolResponse::class.java, expectedId = requestId)
            response.map { it.result }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вызова инструмента $toolName", e)
            Result.failure(e)
        }
    }

    /**
     * Отправка запроса и получение ответа.
     * @param expectedId если задан, читаем строки до ответа с этим id (игнорируем ответы с другим id)
     */
    private suspend fun <T> sendRequest(request: Any, responseType: Class<T>, expectedId: Int? = null): Result<T> = withContext(Dispatchers.IO) {
        try {
            val requestJson = gson.toJson(request)
            Log.d(TAG, "Отправка запроса: $requestJson")
            
            writer?.println(requestJson)
            writer?.flush()
            
            var responseLine: String?
            do {
                responseLine = reader?.readLine()
                if (responseLine == null) {
                    return@withContext Result.failure(IOException("Пустой ответ от сервера"))
                }
                val trimmed = responseLine.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                    Log.d(TAG, "Пропуск: ${if (trimmed.length > 60) trimmed.take(60) + "..." else trimmed}")
                    continue
                }
                if (!trimmed.contains("\"result\"") && !trimmed.contains("\"error\"") && !trimmed.contains("\"method\"")) {
                    Log.d(TAG, "Пропуск: ${if (trimmed.length > 60) trimmed.take(60) + "..." else trimmed}")
                    continue
                }
                if (expectedId != null) {
                    val base = gson.fromJson(responseLine, McpResponse::class.java)
                    val responseId = base.id
                    if (responseId != expectedId) {
                        Log.d(TAG, "Пропуск ответа id=$responseId (ожидаем id=$expectedId)")
                        continue
                    }
                }
                break
            } while (true)
            
            Log.d(TAG, "Получен ответ: ${if (responseLine.length > 250) responseLine.take(250) + "..." else responseLine}")
            
            val baseResponse = gson.fromJson(responseLine, McpResponse::class.java)
            if (baseResponse.error != null) {
                return@withContext Result.failure(
                    Exception("MCP ошибка: ${baseResponse.error.message} (код: ${baseResponse.error.code})")
                )
            }
            
            val typedResponse = gson.fromJson(responseLine, responseType)
            Result.success(typedResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки запроса", e)
            Result.failure(e)
        }
    }

    /**
     * Закрытие подключения
     */
    fun disconnect() {
        try {
            reader?.close()
            writer?.close()
            socket?.close()
            isInitialized = false
            Log.d(TAG, "Подключение закрыто")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка закрытия подключения", e)
        }
    }

    /**
     * Проверка подключения
     */
    fun isConnected(): Boolean {
        return socket?.isConnected == true && !socket!!.isClosed
    }
}
