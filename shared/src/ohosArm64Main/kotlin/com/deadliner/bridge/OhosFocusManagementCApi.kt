@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.db.repository.focus.FocusManagementRepository
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.management.FocusManagementHistoryPage
import com.deadliner.domain.focus.management.FocusManagementSnapshot
import kotlinx.cinterop.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.native.concurrent.ThreadLocal

@Serializable
private data class ManagementRequest(val limit: Int = 50, val target: FocusTargetRef? = null, val timeZoneId: String? = null)

@Serializable
private data class ManagementResponse(
    val schemaVersion: Int = 1,
    val snapshot: FocusManagementSnapshot? = null,
    val history: FocusManagementHistoryPage? = null,
    val error: String? = null,
)

/** Capacity probe and copy execute on the same NAPI worker and must share one response. */
@ThreadLocal
private object ManagementResponseBuffer {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var request: String? = null
    private var response: ByteArray? = null
    fun read(payload: String): ByteArray {
        if (request == payload) response?.let { return it }
        val result = try {
            val query = json.decodeFromString<ManagementRequest>(payload)
            require(query.limit in 1..Int.MAX_VALUE)
            val database = requireNotNull(PersistenceRuntime.database) { "KMP persistence is not initialized" }
            val snapshot = FocusManagementRepository(database, timeZone = {
                query.timeZoneId?.let(kotlinx.datetime.TimeZone::of) ?: kotlinx.datetime.TimeZone.currentSystemDefault()
            }).snapshot()
            ManagementResponse(snapshot = snapshot, history = snapshot.historyPage(query.limit, query.target))
        } catch (error: Throwable) {
            ManagementResponse(error = error.message ?: "Focus management read failed")
        }
        return json.encodeToString(result).encodeToByteArray().also { request = payload; response = it }
    }
    fun clear() { request = null; response = null }
}

@CName("deadliner_persistence_focus_management_json")
fun deadlinerPersistenceFocusManagementJson(
    request: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int,
): Int {
    val payload = request?.toKString() ?: "{}"
    val bytes = ManagementResponseBuffer.read(payload)
    val required = bytes.size + 1
    if (output == null || capacity < required) return required
    bytes.forEachIndexed { index, value -> output[index] = value }
    output[bytes.size] = 0
    ManagementResponseBuffer.clear()
    return required
}
