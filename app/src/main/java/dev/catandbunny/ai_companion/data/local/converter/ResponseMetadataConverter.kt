package dev.catandbunny.ai_companion.data.local.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import dev.catandbunny.ai_companion.model.ResponseMetadata

class ResponseMetadataConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromResponseMetadata(metadata: ResponseMetadata?): String? {
        return if (metadata == null) null else gson.toJson(metadata)
    }

    @TypeConverter
    fun toResponseMetadata(json: String?): ResponseMetadata? {
        return if (json == null) null else {
            try {
                gson.fromJson(json, ResponseMetadata::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
