package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolMetadata(
    val name: String,
    val description: String,
    val domain: AgentRole,
    val capabilities: List<ToolCapability> = emptyList(),
    val supportedPlatforms: List<String> = emptyList(),
    val requiresUiConfirmation: Boolean = false,
    val parameters: JsonElement,
)
