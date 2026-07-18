package com.deadliner.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ChangeOperation {
    INSERT,
    UPDATE,
    DELETE,
}

/** A transport-safe representation of one immutable database change. */
@Serializable
data class ChangeLogEntry(
    val id: String,
    val tableName: String,
    val recordId: String,
    val operation: ChangeOperation,
    val changedFields: JsonObject?,
    val clientTimestamp: Long,
    val serverTimestamp: Long?,
    val deviceId: String,
    val synced: Boolean,
)
