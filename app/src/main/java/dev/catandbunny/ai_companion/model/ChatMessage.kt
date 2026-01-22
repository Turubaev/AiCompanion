package dev.catandbunny.ai_companion.model

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val responseMetadata: ResponseMetadata? = null,
    val manualTokenCount: Int? = null, // Ручной подсчет токенов для сообщений пользователя
    val apiTokenCount: Int? = null // Количество токенов запроса пользователя от API
)

data class ResponseMetadata(
    val questionText: String,        // Текст вопроса пользователя
    val answerText: String,          // Текст ответа бота
    val language: String,            // Язык ответа (ISO 639-1 код: ru, en, de, fr, es, zh, ja, ko, it, pt, ar, hi, mixed, unknown)
    val timestamp: Long,             // Дата сообщения (timestamp)
    val responseTimeMs: Long,       // Время потраченное на выдачу ответа в миллисекундах
    val tokensUsed: Int,            // Количество потраченных токенов
    val status: String,             // Статус сообщения (success, partial, error, etc.)
    val botTimestamp: Long? = null, // Timestamp от бота (время генерации ответа на стороне бота)
    val answerLength: Int? = null,  // Длина ответа в символах
    val category: String? = null,   // Категория/тема вопроса (если определена)
    val confidence: Double? = null, // Уровень уверенности ответа (0.0-1.0, если применимо)
    val requirements: String? = null, // Техническое задание (для финального JSON ответа)
    val recommendations: String? = null, // Рекомендации (для финального JSON ответа)
    val isRequirementsResponse: Boolean = false, // Флаг, указывающий, является ли ответ финальным ТЗ (JSON) или текстовым (сбор требований)
    val manualTokenCount: Int? = null, // Ручной подсчет токенов для ответа бота
    val costFormatted: String? = null, // Отформатированная стоимость для отображения
    val promptTokens: Int? = null // Количество токенов промпта от API (включая системный промпт, историю и текущий запрос)
)
