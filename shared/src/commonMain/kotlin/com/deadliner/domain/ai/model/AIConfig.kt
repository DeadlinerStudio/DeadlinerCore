package com.deadliner.domain.ai.model

data class AIConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val platform: String,
    val timezone: String,
    val temperature: Double? = null,
    /** Explicit protocol selection; network failures never switch protocols. */
    val nativeToolCalling: Boolean = false,
    val preferredLanguage: String = "zh-CN",
)
