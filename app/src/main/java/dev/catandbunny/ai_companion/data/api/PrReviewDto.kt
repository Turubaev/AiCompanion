package dev.catandbunny.ai_companion.data.api

import com.google.gson.annotations.SerializedName

data class PrReviewResponse(
    @SerializedName("reviews") val reviews: List<PrReviewItem> = emptyList()
)

data class PrReviewItem(
    @SerializedName("id") val id: Int,
    @SerializedName("repo") val repo: String,
    @SerializedName("pr_number") val prNumber: Any,
    @SerializedName("pr_title") val prTitle: String?,
    @SerializedName("reviewText") val reviewText: String,
    @SerializedName("createdAt") val createdAt: String?
)
