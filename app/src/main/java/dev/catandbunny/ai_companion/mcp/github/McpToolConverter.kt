package dev.catandbunny.ai_companion.mcp.github

import dev.catandbunny.ai_companion.data.model.Function
import dev.catandbunny.ai_companion.data.model.FunctionParameters
import dev.catandbunny.ai_companion.data.model.PropertySchema
import dev.catandbunny.ai_companion.data.model.Tool
import dev.catandbunny.ai_companion.mcp.model.McpTool

/**
 * Конвертер MCP инструментов в формат OpenAI Function Calling
 */
object McpToolConverter {
    
    /**
     * Конвертирует список MCP инструментов в формат OpenAI Tools
     */
    fun convertToOpenAITools(mcpTools: List<McpTool>): List<Tool> {
        return mcpTools.map { mcpTool ->
            Tool(
                type = "function",
                function = Function(
                    name = mcpTool.name,
                    description = mcpTool.description,
                    parameters = convertInputSchema(mcpTool.inputSchema)
                )
            )
        }
    }
    
    /**
     * Конвертирует MCP input schema в OpenAI function parameters
     */
    private fun convertInputSchema(mcpSchema: dev.catandbunny.ai_companion.mcp.model.ToolInputSchema): FunctionParameters {
        val properties = mcpSchema.properties?.mapNotNull { (key, propSchema) ->
            convertPropertySchema(propSchema)?.let { key to it }
        }?.toMap() ?: emptyMap()
        
        return FunctionParameters(
            type = mcpSchema.type,
            properties = properties,
            required = mcpSchema.required
        )
    }
    
    /**
     * Конвертирует MCP PropertySchema в OpenAI PropertySchema
     */
    private fun convertPropertySchema(mcpProp: dev.catandbunny.ai_companion.mcp.model.PropertySchema): PropertySchema? {
        // Если это объект с properties (но без type или type == "object")
        if ((mcpProp.type == null || mcpProp.type == "object") && mcpProp.properties != null) {
            // Это вложенный объект в items или обычный объект
            val properties = mcpProp.properties.mapNotNull { (key, prop) ->
                convertPropertySchema(prop)?.let { key to it }
            }.toMap()
            
            return PropertySchema(
                type = "object",
                description = mcpProp.description,
                properties = properties,
                required = mcpProp.required
            )
        }
        
        // Проверяем что type не null для обычных свойств
        val type = mcpProp.type ?: return null
        
        // Для массивов - обязательно конвертируем items
        val items = if (type == "array") {
            // Для массива items обязателен
            mcpProp.items?.let { convertPropertySchema(it) }
                ?: PropertySchema(type = "string", description = null) // Fallback если items не указан
        } else {
            // Для не-массивов items не нужен
            null
        }
        
        return PropertySchema(
            type = type,
            description = mcpProp.description,
            items = items,
            properties = if (type == "object" && mcpProp.properties != null) {
                mcpProp.properties.mapNotNull { (key, prop) ->
                    convertPropertySchema(prop)?.let { key to it }
                }.toMap()
            } else {
                null
            },
            required = if (type == "object") mcpProp.required else null
        )
    }
}
