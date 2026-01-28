package dev.catandbunny.ai_companion.mcp.github

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Детектор для определения нужного MCP инструмента на основе запроса пользователя
 */
object McpToolDetector {
    
    /**
     * Определяет нужен ли MCP инструмент для запроса
     */
    fun needsMcpTool(userMessage: String): Boolean {
        val lowerMessage = userMessage.lowercase()
        
        // Ключевые слова для GitHub операций
        val githubKeywords = listOf(
            "github", "репозиторий", "repository", "repo", "репозитории", "repositories",
            "коммит", "commit", "файл", "file", "код", "code",
            "issue", "проблема", "pull request", "pr",
            "пользователь", "user", "owner", "владелец",
            "список репозиториев", "list repositories",
            "показать репозитории", "show repositories",
            "мои репозитории", "my repositories",
            "список", "list", "покажи", "show", "показать"
        )
        
        // Проверяем наличие ключевых слов GitHub
        val hasGithubKeyword = githubKeywords.any { keyword -> lowerMessage.contains(keyword) }
        
        // Дополнительная проверка: если есть "репозиторий" или "repository" в любом контексте
        val hasRepositoryKeyword = lowerMessage.contains("репозитор") || lowerMessage.contains("repositor")
        
        return hasGithubKeyword || hasRepositoryKeyword
    }
    
    /**
     * Определяет какой инструмент нужен
     */
    fun detectTool(userMessage: String): McpToolType? {
        val lowerMessage = userMessage.lowercase()
        
        return when {
            // Список репозиториев
            lowerMessage.contains("список") && lowerMessage.contains("репозитор") ||
            lowerMessage.contains("list") && lowerMessage.contains("repositor") ||
            lowerMessage.contains("показать") && lowerMessage.contains("репозитор") ||
            lowerMessage.contains("show") && lowerMessage.contains("repositor") ||
            lowerMessage.contains("мои репозитории") ||
            lowerMessage.contains("my repositories") -> McpToolType.LIST_REPOSITORIES
            
            // Информация о репозитории
            lowerMessage.contains("информация") && lowerMessage.contains("репозитор") ||
            lowerMessage.contains("info") && lowerMessage.contains("repositor") ||
            lowerMessage.contains("детали") && lowerMessage.contains("репозитор") -> McpToolType.GET_REPOSITORY_INFO
            
            // Содержимое файла
            lowerMessage.contains("файл") && (lowerMessage.contains("показать") || lowerMessage.contains("прочитать") || lowerMessage.contains("содержимое")) ||
            lowerMessage.contains("file") && (lowerMessage.contains("show") || lowerMessage.contains("read") || lowerMessage.contains("content")) ||
            lowerMessage.contains("readme") ||
            lowerMessage.contains("код") && lowerMessage.contains("файл") -> McpToolType.GET_FILE_CONTENT
            
            // Поиск
            lowerMessage.contains("найти") || lowerMessage.contains("поиск") ||
            lowerMessage.contains("search") || lowerMessage.contains("find") -> McpToolType.SEARCH
            
            // Issues
            lowerMessage.contains("issue") || lowerMessage.contains("проблема") ||
            lowerMessage.contains("баг") || lowerMessage.contains("bug") -> McpToolType.LIST_ISSUES
            
            else -> null
        }
    }
    
    /**
     * Извлекает параметры из запроса (owner, repo, path и т.д.)
     */
    fun extractParameters(userMessage: String, toolType: McpToolType): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        // Простой парсинг - можно улучшить
        when (toolType) {
            McpToolType.LIST_REPOSITORIES -> {
                // Пытаемся найти owner в сообщении
                // Варианты: "пользователя USERNAME", "user USERNAME", "владельца USERNAME"
                val ownerPatterns = listOf(
                    Regex("(?:пользователя|пользователь|user|владельца|владелец|owner)\\s+([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE),
                    Regex("(?:@|github\\.com/)([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE),
                    Regex("([a-zA-Z0-9_-]+)\\s+(?:репозиториев|репозитории)", RegexOption.IGNORE_CASE)
                )
                
                ownerPatterns.firstOrNull { pattern ->
                    pattern.find(userMessage)?.let {
                        params["owner"] = it.groupValues[1]
                        true
                    } ?: false
                }
            }
            McpToolType.GET_REPOSITORY_INFO,
            McpToolType.GET_FILE_CONTENT,
            McpToolType.SEARCH,
            McpToolType.LIST_ISSUES -> {
                // Пытаемся найти owner/repo в формате owner/repo
                val repoPattern = Regex("(\\w+)/(\\w+)")
                repoPattern.find(userMessage)?.let {
                    params["owner"] = it.groupValues[1]
                    params["repo"] = it.groupValues[2]
                }
                
                // Пытаемся найти path для файлов
                if (toolType == McpToolType.GET_FILE_CONTENT) {
                    val pathPattern = Regex("(?:path|путь|файл)\\s*[:=]?\\s*([\\w./-]+)", RegexOption.IGNORE_CASE)
                    pathPattern.find(userMessage)?.let {
                        params["path"] = it.groupValues[1]
                    }
                }
            }
        }
        
        return params
    }
}

enum class McpToolType {
    LIST_REPOSITORIES,
    GET_REPOSITORY_INFO,
    GET_FILE_CONTENT,
    SEARCH,
    LIST_ISSUES
}
