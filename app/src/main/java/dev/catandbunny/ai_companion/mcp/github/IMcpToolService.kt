package dev.catandbunny.ai_companion.mcp.github

import dev.catandbunny.ai_companion.mcp.model.CallToolResult
import dev.catandbunny.ai_companion.mcp.model.McpTool
/**
 * Общий интерфейс для одного или двух MCP-сервисов (VPS и/или локальный).
 * Используется в ChatRepository для вызова инструментов и получения списка.
 */
interface IMcpToolService {
    suspend fun getAvailableTools(): Result<List<McpTool>>
    suspend fun callTool(toolName: String, arguments: Map<String, Any>? = null): Result<CallToolResult>
    fun isConnected(): Boolean
}
