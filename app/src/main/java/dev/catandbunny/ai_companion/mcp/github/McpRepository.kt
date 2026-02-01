package dev.catandbunny.ai_companion.mcp.github

import android.util.Log
import dev.catandbunny.ai_companion.config.McpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с MCP: один сервер (VPS) или два (VPS + локальный для эмулятора).
 */
class McpRepository {

    private var mcpClientVps: McpClient? = null
    private var mcpClientLocal: McpClient? = null
    private var vpsService: GitHubMcpService? = null
    private var localService: GitHubMcpService? = null
    private var effectiveService: IMcpToolService? = null

    companion object {
        private const val TAG = "McpRepository"
    }

    /**
     * Инициализация: VPS всегда; локальный MCP — если заданы MCP_SERVER_HOST_LOCAL и MCP_SERVER_PORT_LOCAL.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (effectiveService?.isConnected() == true) {
                Log.d(TAG, "Уже подключено к MCP")
                return@withContext Result.success(Unit)
            }

            suspend fun tryConnect(): Result<Unit> {
                mcpClientVps = McpClient(
                    host = McpConfig.MCP_SERVER_HOST,
                    port = McpConfig.MCP_SERVER_PORT
                )
                vpsService = GitHubMcpService(mcpClientVps!!)
                val vpsResult = vpsService!!.initialize()
                if (vpsResult.isFailure) return vpsResult

                if (McpConfig.isLocalMcpConfigured()) {
                    mcpClientLocal = McpClient(
                        host = McpConfig.MCP_SERVER_HOST_LOCAL,
                        port = McpConfig.MCP_SERVER_PORT_LOCAL
                    )
                    val local = GitHubMcpService(mcpClientLocal!!)
                    val localInit = local.initialize()
                    if (localInit.isSuccess && local.isConnected()) {
                        localService = local
                        effectiveService = GitHubMcpServiceDual(vpsService!!, localService)
                        Log.d(TAG, "Dual MCP: VPS + local (control_android_emulator)")
                    } else {
                        local.disconnect()
                        mcpClientLocal = null
                        localService = null
                        effectiveService = vpsService
                        Log.w(TAG, "Local MCP недоступен, используем только VPS")
                    }
                } else {
                    effectiveService = vpsService
                    Log.d(TAG, "Single MCP: VPS only")
                }
                return Result.success(Unit)
            }

            var result = tryConnect()
            if (result.isFailure) {
                Log.w(TAG, "Первая попытка MCP не удалась, повтор через 2 сек...")
                cleanup()
                delay(2000)
                result = tryConnect()
            }
            if (result.isFailure) cleanup()
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
        return vpsService?.listRepositories(owner, limit)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    suspend fun getRepositoryInfo(owner: String, repo: String): Result<String> {
        return vpsService?.getRepositoryInfo(owner, repo)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    suspend fun getFileContent(owner: String, repo: String, path: String): Result<String> {
        return vpsService?.getFileContent(owner, repo, path)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    suspend fun searchInRepository(owner: String, repo: String, query: String): Result<String> {
        return vpsService?.searchInRepository(owner, repo, query)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    suspend fun getIssues(owner: String, repo: String, state: String = "open", limit: Int = 10): Result<String> {
        return vpsService?.listIssues(owner, repo, state, limit)
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    suspend fun getAvailableTools(): Result<List<dev.catandbunny.ai_companion.mcp.model.McpTool>> {
        return effectiveService?.getAvailableTools()
            ?: Result.failure(IllegalStateException("MCP сервис не инициализирован"))
    }

    /** Для вызова инструментов из чата (VPS и/или локальный по имени инструмента). */
    fun getGitHubService(): IMcpToolService? = effectiveService

    fun isConnected(): Boolean = effectiveService?.isConnected() == true

    fun disconnect() {
        (effectiveService as? GitHubMcpServiceDual)?.disconnect()
            ?: vpsService?.disconnect()
        cleanup()
    }

    private fun cleanup() {
        effectiveService = null
        localService = null
        vpsService = null
        mcpClientLocal = null
        mcpClientVps = null
    }
}
