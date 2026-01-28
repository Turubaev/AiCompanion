package dev.catandbunny.ai_companion.mcp.model

import com.google.gson.annotations.SerializedName

/**
 * Базовый класс для MCP JSON-RPC ответов
 */
data class McpResponse(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int? = null,
    
    @SerializedName("result")
    val result: Any? = null,
    
    @SerializedName("error")
    val error: McpError? = null
)

data class McpError(
    @SerializedName("code")
    val code: Int,
    
    @SerializedName("message")
    val message: String,
    
    @SerializedName("data")
    val data: Any? = null
)

/**
 * Ответ на инициализацию
 */
data class InitializeResponse(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int,
    
    @SerializedName("result")
    val result: InitializeResult
)

data class InitializeResult(
    @SerializedName("protocolVersion")
    val protocolVersion: String,
    
    @SerializedName("capabilities")
    val capabilities: Map<String, Any>? = null,
    
    @SerializedName("serverInfo")
    val serverInfo: ServerInfo
)

data class ServerInfo(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("version")
    val version: String
)

/**
 * Ответ на вызов инструмента
 */
data class CallToolResponse(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int,
    
    @SerializedName("result")
    val result: CallToolResult
)

data class CallToolResult(
    @SerializedName("content")
    val content: List<ToolContent>,
    
    @SerializedName("isError")
    val isError: Boolean = false
)

data class ToolContent(
    @SerializedName("type")
    val type: String, // "text" или "image"
    
    @SerializedName("text")
    val text: String? = null,
    
    @SerializedName("data")
    val data: String? = null,
    
    @SerializedName("mimeType")
    val mimeType: String? = null
)

/**
 * Список доступных инструментов
 */
data class ListToolsResponse(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int,
    
    @SerializedName("result")
    val result: ListToolsResult
)

data class ListToolsResult(
    @SerializedName("tools")
    val tools: List<McpTool>
)

data class McpTool(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("description")
    val description: String,
    
    @SerializedName("inputSchema")
    val inputSchema: ToolInputSchema
)

data class ToolInputSchema(
    @SerializedName("type")
    val type: String = "object",
    
    @SerializedName("properties")
    val properties: Map<String, PropertySchema>? = null,
    
    @SerializedName("required")
    val required: List<String>? = null
)

data class PropertySchema(
    @SerializedName("type")
    val type: String,
    
    @SerializedName("description")
    val description: String? = null
)
