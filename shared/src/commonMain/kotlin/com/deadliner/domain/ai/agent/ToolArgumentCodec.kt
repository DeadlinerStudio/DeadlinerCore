package com.deadliner.domain.ai.agent

import com.deadliner.domain.ai.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Generated serializers preserve the wire contract when Android R8 renames fields. */
object ToolArgumentCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    fun encode(call: ToolCall): String = JsonObject(json.encodeToJsonElement(ToolCall.serializer(), call)
        .jsonObject.filterKeys { it !in setOf("type", "agent", "reason", "executionMode") }).toString()
}
