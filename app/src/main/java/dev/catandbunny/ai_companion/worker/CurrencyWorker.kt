package dev.catandbunny.ai_companion.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.catandbunny.ai_companion.config.McpConfig
import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.mcp.github.McpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CurrencyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val settings = db.settingsDao().getSettingsSync() ?: return@withContext Result.success()
            if (!settings.currencyNotificationEnabled) {
                Log.d(TAG, "Уведомления о курсе выключены, пропуск")
                return@withContext Result.success()
            }

            val client = McpClient(
                host = McpConfig.MCP_SERVER_HOST,
                port = McpConfig.MCP_SERVER_PORT
            )
            try {
                val connectResult = client.connect()
                if (connectResult.isFailure) {
                    Log.e(TAG, "Не удалось подключиться к MCP серверу", connectResult.exceptionOrNull())
                    showErrorNotification("Ошибка подключения к MCP серверу")
                    scheduleNext(settings.currencyIntervalMinutes)
                    return@withContext Result.success()
                }
                val initResult = client.initialize()
                if (initResult.isFailure) {
                    Log.e(TAG, "Ошибка инициализации MCP", initResult.exceptionOrNull())
                    showErrorNotification("Ошибка инициализации MCP")
                    scheduleNext(settings.currencyIntervalMinutes)
                    return@withContext Result.success()
                }
                val callResult = client.callTool("get_currency_rate", null)
                if (callResult.isFailure) {
                    Log.e(TAG, "Ошибка вызова get_currency_rate", callResult.exceptionOrNull())
                    showErrorNotification("Не удалось получить курс (MCP)")
                    scheduleNext(settings.currencyIntervalMinutes)
                    return@withContext Result.success()
                }
                val result = callResult.getOrNull()!!
                val text = result.content
                    .filter { it.type == "text" && it.text != null }
                    .joinToString("\n") { it.text!! }
                if (result.isError || text.isBlank()) {
                    showErrorNotification("Ошибка ответа MCP")
                } else {
                    CurrencyNotificationHelper.showRateNotification(
                        applicationContext,
                        "Курс USD/RUB",
                        text.trim().lines().firstOrNull() ?: text.trim()
                    )
                }
            } finally {
                client.disconnect()
            }

            scheduleNext(settings.currencyIntervalMinutes)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в CurrencyWorker", e)
            showErrorNotification("Ошибка: ${e.message}")
            Result.retry()
        }
    }

    private fun showErrorNotification(message: String) {
        CurrencyNotificationHelper.showRateNotification(
            applicationContext,
            "Курс валют",
            message
        )
    }

    private fun scheduleNext(intervalMinutes: Int) {
        val interval = intervalMinutes.coerceIn(1, 60).toLong()
        val request = OneTimeWorkRequestBuilder<CurrencyWorker>()
            .setInitialDelay(interval, TimeUnit.MINUTES)
            .addTag(CurrencyScheduler.WORK_TAG)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CurrencyScheduler.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.d(TAG, "Следующий запрос курса через $interval мин")
    }

    companion object {
        private const val TAG = "CurrencyWorker"
    }
}
