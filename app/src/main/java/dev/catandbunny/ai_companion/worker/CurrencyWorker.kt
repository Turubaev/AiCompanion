package dev.catandbunny.ai_companion.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.catandbunny.ai_companion.config.ApiConfig
import dev.catandbunny.ai_companion.config.McpConfig
import dev.catandbunny.ai_companion.data.api.RetrofitClient
import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.data.model.Message
import dev.catandbunny.ai_companion.data.model.OpenAIRequest
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
                val rateText = result.content
                    .filter { it.type == "text" && it.text != null }
                    .joinToString("\n") { it.text!! }
                    .trim()
                if (result.isError || rateText.isBlank()) {
                    showErrorNotification("Ошибка ответа MCP")
                } else {
                    val rateLine = rateText.lines().firstOrNull() ?: rateText
                    val notificationBody = addJokeFromLlm(rateText, rateLine, settings.selectedModel)
                    CurrencyNotificationHelper.showRateNotification(
                        applicationContext,
                        "Курс USD/RUB",
                        notificationBody
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

    /**
     * Отправляет курс в LLM и возвращает строку для уведомления: курс + краткая шутка.
     * При ошибке или отсутствии ключа возвращает только строку с курсом.
     */
    private suspend fun addJokeFromLlm(rateText: String, rateLine: String, model: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = ApiConfig.OPENAI_API_KEY
            if (apiKey.isBlank()) {
                Log.d(TAG, "OpenAI API ключ не задан, показываем только курс")
                return@withContext rateLine
            }
            val systemPrompt = "Ты бот. Тебе дают текущий курс доллара к рублю (USD/RUB). " +
                "Ответь одной короткой смешной фразой или каламбуром про этот курс (до 100 символов). " +
                "Пиши только шутку, без повтора цифр курса."
            val request = OpenAIRequest(
                model = model,
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = rateText)
                ),
                temperature = 0.8,
                maxTokens = 120
            )
            try {
                val response = RetrofitClient.openAIService.createChatCompletion(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenAI error: ${response.code()}")
                    return@withContext rateLine
                }
                val body = response.body() ?: return@withContext rateLine
                val joke = body.choices.firstOrNull()?.message?.content?.trim()
                if (!joke.isNullOrBlank()) {
                    "$rateLine\n\n$joke"
                } else {
                    rateLine
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка вызова OpenAI", e)
                rateLine
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
