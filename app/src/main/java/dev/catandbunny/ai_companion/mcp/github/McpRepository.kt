package dev.catandbunny.ai_companion.mcp.github

import android.util.Log
import dev.catandbunny.ai_companion.config.McpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с MCP GitHub сервисом
 * Управляет жизненным циклом подключения
 */
class McpRepository {
    
    private var mcpClient: McpClient? = null
    private var gitHubService: GitHubMcpService? = null
    
    companion object {
        private const val TAG = "McpRepository"
    }

    /**
     * Инициализация и подключение к MCP серверу.
     * При первой неудаче (например, "пустой ответ") делается одна повторная попытка через 2 сек.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (gitHubService?.isConnected() == true) {
                Log.d(TAG, "Уже подключено к MCP серверу")
                return@withContext Result.success(Unit)
            }

            suspend fun tryConnect(): Result<Unit> {
                mcpClient = McpClient(
                    host = McpConfig.MCP_SERVER_HOST,
                    port = McpConfig.MCP_SERVER_PORT
                )
                gitHubService = GitHubMcpService(mcpClient!!)
                return gitHubService!!.initialize()
            }

            var result = tryConnect()
            if (result.isFailure) {
                Log.w(TAG, "Первая попытка MCP не удалась, повтор через 2 сек...")
                cleanup()
                delay(2000)
                result = tryConnect()
            }
            if (result.isFailure) {
                cleanup()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации репозитория", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Получить список репозиториев
     */
    suspend fun getRepositories(owner: String? = null, limit: Int = 10): Result<String> {
        return gitHubService?.listRepositories(owner, limit)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /**
     * Получить информацию о репозитории
     */
    suspend fun getRepositoryInfo(owner: String, repo: String): Result<String> {
        return gitHubService?.getRepositoryInfo(owner, repo)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /**
     * Получить содержимое файла
     */
    suspend fun getFileContent(owner: String, repo: String, path: String): Result<String> {
        return gitHubService?.getFileContent(owner, repo, path)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /**
     * Поиск в репозитории
     */
    suspend fun searchInRepository(owner: String, repo: String, query: String): Result<String> {
        return gitHubService?.searchInRepository(owner, repo, query)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /**
     * Получить список issues
     */
    suspend fun getIssues(owner: String, repo: String, state: String = "open", limit: Int = 10): Result<String> {
        return gitHubService?.listIssues(owner, repo, state, limit)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /**
     * Получить список доступных инструментов
     */
    suspend fun getAvailableTools(): Result<List<dev.catandbunny.ai_companion.mcp.model.McpTool>> {
        return gitHubService?.getAvailableTools()
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }
    
    /**
     * Получить доступ к GitHub сервису (для прямого вызова инструментов)
     */
    fun getGitHubService(): GitHubMcpService? = gitHubService

    /**
     * Проверка подключения
     */
    fun isConnected(): Boolean {
        return gitHubService?.isConnected() == true
    }

    /**
     * Закрытие подключения
     */
    fun disconnect() {
        gitHubService?.disconnect()
        cleanup()
    }

    /**
     * Очистка ресурсов
     */
    private fun cleanup() {
        gitHubService = null
        mcpClient = null
    }
}
