package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.ChatRequest
import com.deadliner.domain.ai.model.ChatResponse

fun interface LlmGateway {
    suspend fun chat(request: ChatRequest): ChatResponse
}
