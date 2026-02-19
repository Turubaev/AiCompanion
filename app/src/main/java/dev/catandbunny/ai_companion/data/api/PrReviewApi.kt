package dev.catandbunny.ai_companion.data.api

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Запрос непрочитанных ревью PR для пользователя.
 * Backend возвращает список и помечает их прочитанными.
 */
fun fetchPrReviews(baseUrl: String, githubUsername: String): List<PrReviewItem> {
    if (githubUsername.isBlank()) return emptyList()
    val url = "${baseUrl.trimEnd('/')}/reviews?github_username=${java.net.URLEncoder.encode(githubUsername.trim(), "UTF-8")}"
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder().url(url).get().build()
    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("PrReviewApi", "GET /reviews failed: ${response.code}")
                return@use emptyList<PrReviewItem>()
            }
            val json = response.body?.string() ?: return@use emptyList<PrReviewItem>()
            val parsed = Gson().fromJson(json, PrReviewResponse::class.java)
            parsed.reviews ?: emptyList()
        }
    } catch (e: Exception) {
        Log.w("PrReviewApi", "fetchPrReviews failed: ${e.message}")
        emptyList()
    }
}

/**
 * Регистрация на backend для доставки ревью PR в Telegram.
 * POST /register с github_username и telegram_chat_id.
 * @return true при успехе (200), false при ошибке.
 */
fun registerPrReviewTelegram(baseUrl: String, githubUsername: String, telegramChatId: String): Boolean {
    if (githubUsername.isBlank()) return false
    val url = "${baseUrl.trimEnd('/')}/register"
    val body = """{"github_username":${Gson().toJson(githubUsername.trim())},"telegram_chat_id":${Gson().toJson(telegramChatId.trim())}}"""
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(jsonMediaType))
        .build()
    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("PrReviewApi", "POST /register failed: ${response.code} ${response.body?.string()}")
                return@use false
            }
            true
        }
    } catch (e: Exception) {
        Log.w("PrReviewApi", "registerPrReviewTelegram failed: ${e.message}")
        false
    }
}
