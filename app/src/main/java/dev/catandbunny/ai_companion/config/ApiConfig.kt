package dev.catandbunny.ai_companion.config

import dev.catandbunny.ai_companion.BuildConfig

object ApiConfig {
    // API ключ берется из BuildConfig, который генерируется из local.properties
    val OPENAI_API_KEY: String = BuildConfig.OPENAI_API_KEY
}
