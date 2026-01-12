package dev.catandbunny.ai_companion.data.api

import dev.catandbunny.ai_companion.data.model.OpenAIRequest
import dev.catandbunny.ai_companion.data.model.OpenAIResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAIService {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: OpenAIRequest
    ): Response<OpenAIResponse>
}
