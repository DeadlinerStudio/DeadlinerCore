package com.deadliner.domain.ai.port

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val tool: String,
    val payload: String,
)
