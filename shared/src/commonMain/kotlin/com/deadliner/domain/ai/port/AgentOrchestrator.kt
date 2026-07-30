package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.MixedResult

fun interface AgentOrchestrator {
    suspend fun processMessage(text: String): MixedResult
}
