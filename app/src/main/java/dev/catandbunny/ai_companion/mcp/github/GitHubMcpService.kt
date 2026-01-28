package dev.catandbunny.ai_companion.mcp.github

import android.util.Log
import dev.catandbunny.ai_companion.mcp.model.CallToolResult
import dev.catandbunny.ai_companion.mcp.model.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сервис для работы с GitHub через MCP
 */
class GitHubMcpService(private val mcpClient: McpClient) {
    
    companion object {
        private const val TAG = "GitHubMcpService"
    }

    /**
     * Инициализация подключения к MCP серверу
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Подключаемся к серверу
            val connectResult = mcpClient.connect()
            if (connectResult.isFailure) {
                return@withContext connectResult
            }

            // Инициализируем MCP протокол
            val initResult = mcpClient.initialize()
            if (initResult.isFailure) {
                mcpClient.disconnect()
                return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("Ошибка инициализации"))
            }

            Log.d(TAG, "GitHub MCP сервис инициализирован")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации сервиса", e)
            Result.failure(e)
        }
    }

    /**
     * Получить список доступных инструментов GitHub
     */
    suspend fun getAvailableTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        mcpClient.listTools()
    }

    /**
     * Получить список репозиториев пользователя
     */
    suspend fun listRepositories(
        owner: String? = null,
        limit: Int = 10
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val arguments = mutableMapOf<String, Any>()
            if (owner != null) {
                arguments["owner"] = owner
            }
            arguments["limit"] = limit

            val result = mcpClient.callTool("github_list_repositories", arguments)
            result.map { extractTextContent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения списка репозиториев", e)
            Result.failure(e)
        }
    }

    /**
     * Получить информацию о репозитории
     */
    suspend fun getRepositoryInfo(
        owner: String,
        repo: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val arguments = mapOf(
                "owner" to owner,
                "repo" to repo
            )

            val result = mcpClient.callTool("github_get_repository", arguments)
            result.map { extractTextContent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения информации о репозитории", e)
            Result.failure(e)
        }
    }

    /**
     * Получить содержимое файла из репозитория
     */
    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val arguments = mapOf(
                "owner" to owner,
                "repo" to repo,
                "path" to path
            )

            val result = mcpClient.callTool("github_get_file_contents", arguments)
            result.map { extractTextContent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения содержимого файла", e)
            Result.failure(e)
        }
    }

    /**
     * Поиск в репозитории
     */
    suspend fun searchInRepository(
        owner: String,
        repo: String,
        query: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val arguments = mapOf(
                "owner" to owner,
                "repo" to repo,
                "query" to query
            )

            val result = mcpClient.callTool("github_search_code", arguments)
            result.map { extractTextContent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка поиска в репозитории", e)
            Result.failure(e)
        }
    }

    /**
     * Получить список issues
     */
    suspend fun listIssues(
        owner: String,
        repo: String,
        state: String = "open",
        limit: Int = 10
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val arguments = mapOf(
                "owner" to owner,
                "repo" to repo,
                "state" to state,
                "limit" to limit
            )

            val result = mcpClient.callTool("github_list_issues", arguments)
            result.map { extractTextContent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения списка issues", e)
            Result.failure(e)
        }
    }

    /**
     * Вызвать инструмент напрямую по имени
     */
    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>? = null
    ): Result<CallToolResult> = withContext(Dispatchers.IO) {
        mcpClient.callTool(toolName, arguments)
    }

    /**
     * Извлечь текстовое содержимое из результата
     */
    private fun extractTextContent(result: CallToolResult): String {
        return result.content
            .filter { it.type == "text" }
            .joinToString("\n") { it.text ?: "" }
    }

    /**
     * Закрыть подключение
     */
    fun disconnect() {
        mcpClient.disconnect()
    }

    /**
     * Проверка подключения
     */
    fun isConnected(): Boolean {
        return mcpClient.isConnected()
    }
}
