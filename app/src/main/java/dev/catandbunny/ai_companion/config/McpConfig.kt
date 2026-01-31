package dev.catandbunny.ai_companion.config

import dev.catandbunny.ai_companion.BuildConfig

object McpConfig {
    // VPS настройки - можно вынести в local.properties или настройки приложения
    val MCP_SERVER_HOST: String = BuildConfig.MCP_SERVER_HOST
    val MCP_SERVER_PORT: Int = BuildConfig.MCP_SERVER_PORT
    
    // Таймауты (send_telegram_message вызывает Telegram API — может занимать до минуты)
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 90L
    const val WRITE_TIMEOUT_SECONDS = 30L
}
