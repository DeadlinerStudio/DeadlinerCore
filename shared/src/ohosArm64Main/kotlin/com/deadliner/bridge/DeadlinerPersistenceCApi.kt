@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.OhosDatabaseDriverFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small C ABI for the HarmonyOS NAPI module.
 *
 * Return values are intentionally primitive so this boundary stays stable while snapshot import
 * and repository APIs are introduced in later migration phases.
 */
private object PersistenceRuntime {
    var database: DeadlinerDatabase? = null
    private var pendingLegacyV2Merge: LegacyV2MergeCache? = null

    fun initialize(directory: String, installationId: String): Int = try {
        require(directory.startsWith('/')) { "database directory must be absolute" }
        require(installationId.isNotBlank()) { "installation ID must not be blank" }
        database?.close()
        database = DeadlinerDatabase.create(OhosDatabaseDriverFactory(directory, installationId))
        pendingLegacyV2Merge = null
        1
    } catch (_: Throwable) {
        database = null
        0
    }

    fun close(): Int {
        database?.close()
        database = null
        pendingLegacyV2Merge = null
        return 1
    }

    fun importLegacyV2(taskPayload: String, habitPayload: String): Int = try {
        val activeDatabase = database ?: return -1
        val result = activeDatabase.legacyV2Sync.mergeRemotePayloads(
            taskPayload.takeIf(String::isNotBlank),
            habitPayload.takeIf(String::isNotBlank),
            null,
        )
        if (result.failureMessage == null) result.remoteApplied else -1
    } catch (_: Throwable) {
        -1
    }

    fun resolveLegacyV2PayloadsJson(taskPayload: String, habitPayload: String): String? {
        val pending = pendingLegacyV2Merge
        if (pending != null && pending.taskPayload == taskPayload && pending.habitPayload == habitPayload) {
            return pending.responsePayload
        }
        val response = try {
            val activeDatabase = database ?: return null
        val result = activeDatabase.legacyV2Sync.mergeRemotePayloads(
            taskPayload.takeIf(String::isNotBlank),
            habitPayload.takeIf(String::isNotBlank),
            null,
        )
        if (result.failureMessage == null) bridgeJson.encodeToString(result) else null
        } catch (_: Throwable) {
            null
        }
        if (response != null) {
            pendingLegacyV2Merge = LegacyV2MergeCache(taskPayload, habitPayload, response)
        }
        return response
    }

    fun clearPendingLegacyV2Merge() {
        pendingLegacyV2Merge = null
    }

    fun diagnosticsJson(): String {
        val activeDatabase = database
        if (activeDatabase == null) {
            return "{\"bridge_version\":4,\"ready\":false}"
        }
        return "{\"bridge_version\":4,\"ready\":true,\"categories\":" +
            activeDatabase.categories.list().size +
            ",\"tasks\":" + activeDatabase.tasks.list().size +
            ",\"habits\":" + activeDatabase.habits.list().size +
            ",\"captures\":" + activeDatabase.captures.list().size +
            ",\"memory_fragments\":" + activeDatabase.memory.list().size +
            ",\"user_profiles\":" + activeDatabase.profiles.list().size + "}"
    }

    private val bridgeJson = Json { encodeDefaults = true }

    private data class LegacyV2MergeCache(
        val taskPayload: String,
        val habitPayload: String,
        val responsePayload: String,
    )
}

private fun writeCallerOwnedJson(
    payload: String?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    if (payload == null) return 0
    val bytes = payload.encodeToByteArray()
    val requiredCapacity = bytes.size + 1
    if (output == null || outputCapacity < requiredCapacity) return requiredCapacity
    bytes.forEachIndexed { index, value -> output[index] = value }
    output[bytes.size] = 0
    return requiredCapacity
}

@CName("deadliner_persistence_initialize")
fun deadlinerPersistenceInitialize(
    directory: CPointer<ByteVar>?,
    installationId: CPointer<ByteVar>?,
): Int {
    val resolvedDirectory = directory?.toKString() ?: return 0
    val resolvedInstallationId = installationId?.toKString() ?: return 0
    return PersistenceRuntime.initialize(resolvedDirectory, resolvedInstallationId)
}

@CName("deadliner_persistence_close")
fun deadlinerPersistenceClose(): Int = PersistenceRuntime.close()

@CName("deadliner_persistence_health")
fun deadlinerPersistenceHealth(): Int = if (PersistenceRuntime.database == null) 0 else 1

@CName("deadliner_persistence_import_legacy_v2")
fun deadlinerPersistenceImportLegacyV2(
    taskPayload: CPointer<ByteVar>?,
    habitPayload: CPointer<ByteVar>?,
): Int {
    val resolvedTaskPayload = taskPayload?.toKString() ?: return -1
    val resolvedHabitPayload = habitPayload?.toKString() ?: return -1
    return PersistenceRuntime.importLegacyV2(resolvedTaskPayload, resolvedHabitPayload)
}

@CName("deadliner_persistence_merge_legacy_v2_json")
fun deadlinerPersistenceMergeLegacyV2Json(
    taskPayload: CPointer<ByteVar>?,
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedTaskPayload = taskPayload?.toKString() ?: return 0
    val resolvedHabitPayload = habitPayload?.toKString() ?: return 0
    val writeResult = writeCallerOwnedJson(
        PersistenceRuntime.resolveLegacyV2PayloadsJson(resolvedTaskPayload, resolvedHabitPayload),
        output,
        outputCapacity,
    )
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) {
        PersistenceRuntime.clearPendingLegacyV2Merge()
    }
    return writeResult
}

/**
 * Writes a null-terminated diagnostics JSON document into caller-owned memory.
 * The return value is the required buffer capacity (including the terminator), which lets the
 * NAPI layer allocate without relying on Kotlin/Native-owned pointers.
 */
@CName("deadliner_persistence_diagnostics_json")
fun deadlinerPersistenceDiagnosticsJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    return writeCallerOwnedJson(PersistenceRuntime.diagnosticsJson(), output, outputCapacity)
}

@CName("deadliner_persistence_bridge_version")
fun deadlinerPersistenceBridgeVersion(): Int = 4
