package dev.catandbunny.ai_companion.mcp.model

import com.google.gson.annotations.SerializedName

/**
 * Базовый класс для MCP JSON-RPC запросов
 */
data class McpRequest(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int? = null,
    
    @SerializedName("method")
    val method: String,
    
    @SerializedName("params")
    val params: Map<String, Any>? = null
)

/**
 * Запрос на инициализацию MCP сервера
 */
data class InitializeRequest(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int = 1,
    
    @SerializedName("method")
    val method: String = "initialize",
    
    @SerializedName("params")
    val params: InitializeParams
)

data class InitializeParams(
    @SerializedName("protocolVersion")
    val protocolVersion: String = "2024-11-05",
    
    @SerializedName("capabilities")
    val capabilities: Map<String, Any> = emptyMap(),
    
    @SerializedName("clientInfo")
    val clientInfo: ClientInfo
)

data class ClientInfo(
    @SerializedName("name")
    val name: String = "ai-companion-android",
    
    @SerializedName("version")
    val version: String = "1.0.0"
)

/**
 * Запрос на вызов инструмента (tool)
 */
data class CallToolRequest(
    @SerializedName("jsonrpc")
    val jsonrpc: String = "2.0",
    
    @SerializedName("id")
    val id: Int,
    
    @SerializedName("method")
    val method: String = "tools/call",
    
    @SerializedName("params")
    val params: CallToolParams
)

data class CallToolParams(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("arguments")
    val arguments: Map<String, Any>? = null
)
