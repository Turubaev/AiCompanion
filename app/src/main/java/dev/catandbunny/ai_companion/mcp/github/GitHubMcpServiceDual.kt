package dev.catandbunny.ai_companion.mcp.github

import android.util.Log
import dev.catandbunny.ai_companion.mcp.model.CallToolResult
import dev.catandbunny.ai_companion.mcp.model.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Объединяет два MCP-сервера: VPS (Tinkoff, Telegram, GitHub) и локальный (эмулятор).
 * Список инструментов — объединённый; вызов control_android_emulator идёт на локальный, остальные — на VPS.
 */
class GitHubMcpServiceDual(
    private val vpsService: GitHubMcpService,
    private val localService: GitHubMcpService?
) : IMcpToolService {
    companion object {
        private const val TAG = "McpServiceDual"
        const val LOCAL_TOOL_NAME = "control_android_emulator"
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        val vpsResult = vpsService.initialize()
        if (vpsResult.isFailure) return@withContext vpsResult
        localService?.let { local ->
            val localResult = local.initialize()
            if (localResult.isFailure) {
                Log.w(TAG, "Локальный MCP не инициализирован, control_android_emulator недоступен")
            }
        }
        Result.success(Unit)
    }

    override suspend fun getAvailableTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        val vpsTools = vpsService.getAvailableTools().getOrElse { emptyList() }
        val localTools = localService?.getAvailableTools()?.getOrElse { emptyList() } ?: emptyList()
        val merged = (vpsTools + localTools).distinctBy { it.name }
        Log.d(TAG, "getAvailableTools: VPS=${vpsTools.size}, local=${localTools.size}, merged=${merged.size}")
        Result.success(merged)
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>?
    ): Result<CallToolResult> = withContext(Dispatchers.IO) {
        if (toolName == LOCAL_TOOL_NAME && localService != null) {
            Log.d(TAG, "callTool: $toolName -> local MCP")
            localService.callTool(toolName, arguments)
        } else {
            Log.d(TAG, "callTool: $toolName -> VPS MCP")
            vpsService.callTool(toolName, arguments)
        }
    }

    fun disconnect() {
        vpsService.disconnect()
        localService?.disconnect()
    }

    /** Подключено, если хотя бы VPS доступен (локальный опционален). */
    override fun isConnected(): Boolean = vpsService.isConnected()

    // Остальные методы — только VPS (GitHub и т.д.)
    suspend fun listRepositories(owner: String? = null, limit: Int = 10): Result<String> =
        vpsService.listRepositories(owner, limit)

    suspend fun getRepositoryInfo(owner: String, repo: String): Result<String> =
        vpsService.getRepositoryInfo(owner, repo)

    suspend fun getFileContent(owner: String, repo: String, path: String): Result<String> =
        vpsService.getFileContent(owner, repo, path)

    suspend fun searchInRepository(owner: String, repo: String, query: String): Result<String> =
        vpsService.searchInRepository(owner, repo, query)

    suspend fun listIssues(owner: String, repo: String, state: String = "open", limit: Int = 10): Result<String> =
        vpsService.listIssues(owner, repo, state, limit)
}
