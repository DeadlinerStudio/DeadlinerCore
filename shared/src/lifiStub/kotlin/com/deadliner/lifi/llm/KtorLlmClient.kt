@file:Suppress("UNUSED_PARAMETER")
package com.deadliner.lifi.llm

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.ChatRequest
import com.deadliner.domain.ai.model.ChatResponse
import com.deadliner.domain.ai.port.LlmGateway
import com.deadliner.interfaces.LifiUnavailableException

class KtorLlmClient(config: AIConfig) : LlmGateway {
    override suspend fun chat(request: ChatRequest): ChatResponse = throw LifiUnavailableException()
    override fun close() {}
}
