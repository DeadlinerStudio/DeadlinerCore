package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    val tools: List<ChatToolDefinition>? = null,
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>,
)

@Serializable
data class ChatToolCall(val id: String, val function: ChatFunctionCall, val type: String = "function")

@Serializable
data class ChatFunctionCall(val name: String, val arguments: String)

@Serializable
data class ChatToolDefinition(val function: ChatFunctionDefinition, val type: String = "function")

@Serializable
data class ChatFunctionDefinition(val name: String, val description: String, val parameters: JsonElement)
