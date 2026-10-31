package com.deadliner.domain.ai.agent

import kotlinx.serialization.json.*

/** Argument identity is independent of object key order, but preserves array order. */
fun canonicalAgentJson(value: String): String = canonical(Json.parseToJsonElement(value)).toString()

private fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
}
