package com.deadliner.sync.transport

/**
 * Conditional blob storage used by snapshot-style sync protocols.
 *
 * The contract intentionally contains no WebDAV, CloudKit, Android, or
 * HarmonyOS types. A provider-specific transport can implement it without
 * changing a business schema or a snapshot payload.
 */
interface SyncBlobTransport : AutoCloseable {
    suspend fun read(path: String): SyncBlob?

    suspend fun write(path: String, bytes: ByteArray, condition: SyncWriteCondition)

    suspend fun ensureCollection(path: String): Boolean

    override fun close() = Unit
}

data class SyncBlob(
    val bytes: ByteArray,
    val version: String?,
)

sealed interface SyncWriteCondition {
    /** Create only when no remote object exists. */
    data object CreateOnly : SyncWriteCondition

    /** Replace the object only if its provider version still matches. */
    data class MatchVersion(val value: String) : SyncWriteCondition

    /** No optimistic concurrency condition. Intended only for providers that need it. */
    data object Unconditional : SyncWriteCondition
}

/** Stable provider-neutral failures surfaced to platform UI and logging layers. */
sealed class SyncTransportException(message: String) : IllegalStateException(message)

class SyncTransportHttpException(
    val statusCode: Int,
    operation: String,
) : SyncTransportException("Sync transport HTTP $statusCode: $operation")

class SyncTransportPreconditionFailedException :
    SyncTransportException("Sync transport conditional write was rejected.")

class SyncTransportParentCreationException(path: String) :
    SyncTransportException("Sync transport could not create parent collection for $path")
