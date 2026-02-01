package dev.catandbunny.ai_companion.config

import dev.catandbunny.ai_companion.BuildConfig

object McpConfig {
    /** VPS: Tinkoff, Telegram, GitHub и т.д. */
    val MCP_SERVER_HOST: String = BuildConfig.MCP_SERVER_HOST
    val MCP_SERVER_PORT: Int = BuildConfig.MCP_SERVER_PORT

    /** Локальный MCP (эмулятор): если заданы — подключаемся к обоим серверам, control_android_emulator идёт на локальный. */
    val MCP_SERVER_HOST_LOCAL: String = BuildConfig.MCP_SERVER_HOST_LOCAL
    val MCP_SERVER_PORT_LOCAL: Int = BuildConfig.MCP_SERVER_PORT_LOCAL

    fun isLocalMcpConfigured(): Boolean =
        MCP_SERVER_HOST_LOCAL.isNotBlank() && MCP_SERVER_PORT_LOCAL > 0

    // Таймауты (send_telegram_message вызывает Telegram API — может занимать до минуты)
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 90L
    const val WRITE_TIMEOUT_SECONDS = 30L
}
