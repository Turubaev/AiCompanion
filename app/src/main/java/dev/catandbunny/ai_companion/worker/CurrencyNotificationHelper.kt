package dev.catandbunny.ai_companion.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.catandbunny.ai_companion.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ключ для передачи текста уведомления при клике (открытие чата с сообщением от бота). */
const val EXTRA_CURRENCY_NOTIFICATION_MESSAGE = "dev.catandbunny.ai_companion.EXTRA_CURRENCY_MESSAGE"

/** Ключ для передачи сообщения из adb am start (демо на эмуляторе), Base64. */
const val EXTRA_DEMO_MESSAGE_B64 = "dev.catandbunny.ai_companion.DEMO_MESSAGE_B64"

/**
 * Сообщение из пуш-уведомления о курсе, которое нужно показать в чате как сообщение бота.
 * MainActivity при получении intent с extra устанавливает этот flow; ChatScreen добавляет сообщение в чат.
 */
object PendingCurrencyNotification {
    private val _messageFlow = MutableStateFlow<String?>(null)
    val messageFlow: StateFlow<String?> = _messageFlow.asStateFlow()

    fun setPendingMessage(text: String?) {
        _messageFlow.value = text
    }
}

/**
 * Текст, переданный через ACTION_SEND (например, из adb am start для демо на эмуляторе).
 * MainActivity при получении intent с EXTRA_TEXT устанавливает этот flow; ChatScreen отправляет его как сообщение пользователя.
 */
object PendingSharedText {
    private val _textToSendFlow = MutableStateFlow<String?>(null)
    val textToSendFlow: StateFlow<String?> = _textToSendFlow.asStateFlow()

    fun setTextToSend(text: String?) {
        _textToSendFlow.value = text
    }
}

object CurrencyNotificationHelper {
    private const val CHANNEL_ID = "currency_rate"
    private const val CHANNEL_NAME = "Курс валют"
    private const val NOTIFICATION_ID = 1001
    private const val PENDING_INTENT_REQUEST_CODE = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о курсе USD/RUB"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Показывает уведомление о курсе.
     * 1) Развёрнутый текст — через BigTextStyle (можно развернуть жестом).
     * 2) По клику открывается приложение и полный текст добавляется в чат как сообщение бота.
     */
    fun showRateNotification(context: Context, title: String, fullText: String) {
        ensureChannel(context)
        val shortPreview = fullText.lines().firstOrNull()?.take(60) ?: fullText.take(60)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CURRENCY_NOTIFICATION_MESSAGE, fullText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(shortPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Нет разрешения POST_NOTIFICATIONS (API 33+)
        }
    }
}
