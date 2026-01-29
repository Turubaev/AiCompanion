package dev.catandbunny.ai_companion.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Планировщик периодического запроса курса USD/RUB через MCP и пуш-уведомлений.
 */
object CurrencyScheduler {
    const val UNIQUE_WORK_NAME = "currency_rate_notification"
    const val WORK_TAG = "currency_rate"

    /**
     * Запускает задачу запроса курса через заданный интервал (в минутах).
     * При следующем срабатывании worker сам перепланирует себя.
     */
    fun schedule(context: Context, intervalMinutes: Int) {
        val interval = intervalMinutes.coerceIn(1, 60).toLong()
        val request = OneTimeWorkRequestBuilder<CurrencyWorker>()
            .setInitialDelay(interval, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Отменяет все запланированные задачи запроса курса.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
