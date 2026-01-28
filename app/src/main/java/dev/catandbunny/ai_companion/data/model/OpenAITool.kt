package dev.catandbunny.ai_companion.data.model

import com.google.gson.annotations.SerializedName

/**
 * Модели для OpenAI Function Calling (Tools)
 */
data class Tool(
    val type: String = "function",
    val function: Function
)

data class Function(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertySchema>,
    val required: List<String>? = null
)

data class PropertySchema(
    val type: String,
    val description: String? = null,
    val items: PropertySchema? = null,
    val enum: List<String>? = null,
    val properties: Map<String, PropertySchema>? = null, // Для объектов - вложенные свойства
    val required: List<String>? = null // Для объектов - обязательные поля
)

/**
 * Tool call от OpenAI
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON строка
)

/**
 * Tool message для отправки результата обратно
 */
data class ToolMessage(
    val role: String = "tool",
    val content: String,
    @SerializedName("tool_call_id")
    val toolCallId: String
)
