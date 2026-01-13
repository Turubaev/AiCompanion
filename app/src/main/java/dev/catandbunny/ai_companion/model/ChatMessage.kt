package dev.catandbunny.ai_companion.model

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val responseMetadata: ResponseMetadata? = null
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
    val confidence: Double? = null  // Уровень уверенности ответа (0.0-1.0, если применимо)
)
