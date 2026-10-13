@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.quickadd.QuickAddAiResult
import com.deadliner.domain.quickadd.model.QuickAddRecognitionPolicy
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class QuickAddRequest(
    val text: String,
    val kind: String = "TASK",
    val timezone: String = "Asia/Shanghai",
    val now: String? = null,
)

private fun writeQuickAddJson(payload: String?, output: CPointer<ByteVar>?, capacity: Int): Int {
    if (payload == null) return 0
    val bytes = payload.encodeToByteArray()
    val required = bytes.size + 1
    if (output == null || capacity < required) return required
    bytes.forEachIndexed { index, value -> output[index] = value }
    output[bytes.size] = 0
    return required
}

@CName("deadliner_quick_add_parse_json")
fun deadlinerQuickAddParseJson(request: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = runCatching {
        val input = Json.decodeFromString<QuickAddRequest>(request?.toKString() ?: return 0)
        QuickAddRecognitionPolicy().fallback(input.text, input.kind, input.now?.let(Instant::parse) ?: Clock.System.now(),
            TimeZone.of(input.timezone)).toJson()
    }.getOrNull()
    return writeQuickAddJson(payload, output, capacity)
}

@CName("deadliner_quick_add_extract_json")
fun deadlinerQuickAddExtractJson(config: CPointer<ByteVar>?, request: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = runCatching {
        val input = Json.decodeFromString<QuickAddRequest>(request?.toKString() ?: return 0)
        val configPayload = config?.toKString() ?: return 0
        // Existing utility bridge caches responses between buffer sizing and writing.
        runBlocking {
            if (input.kind == "HABIT") {
                val raw = PersistenceRuntime.extractLifiHabitsJson(configPayload, input.text) ?: return@runBlocking null
                QuickAddAiResult.habits(Json { ignoreUnknownKeys = true }.decodeFromString<List<AiHabitProposal>>(raw)).toJson()
            } else {
                val raw = PersistenceRuntime.extractLifiTasksJson(configPayload, input.text) ?: return@runBlocking null
                QuickAddAiResult.tasks(Json.decodeFromString<List<AiTaskProposal>>(raw)).toJson()
            }
        }
    }.getOrNull()
    val result = writeQuickAddJson(payload, output, capacity)
    if (output != null && result > 0 && capacity >= result) PersistenceRuntime.clearPendingLifiUtility()
    return result
}
