package com.deadliner.lifi.llm

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.ChatRequest
import com.deadliner.domain.ai.model.ChatResponse
import com.deadliner.domain.ai.port.LlmGateway
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual class KtorLlmClient actual constructor(
    private val config: AIConfig,
) : LlmGateway {
    private val client = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    actual override suspend fun chat(request: ChatRequest): ChatResponse {
        return client.post(normalizeOpenAiChatCompletionsUrl(config.baseUrl)) {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            setBody(request)
        }.body()
    }
}
