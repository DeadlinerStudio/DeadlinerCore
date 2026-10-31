@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking

@CName("deadliner_lifi_execute_approved_json")
fun deadlinerLifiExecuteApprovedJson(
    actionId: CPointer<ByteVar>?, arguments: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?, capacity: Int,
): Int {
    val id = actionId?.toKString() ?: return 0
    val args = arguments?.toKString() ?: return 0
    return try {
        val payload = runBlocking { PersistenceRuntime.executeLifiAction(id, args) }
        val bytes = payload.encodeToByteArray()
        val required = bytes.size + 1
        if (output != null && capacity >= required) {
            bytes.forEachIndexed { index, byte -> output[index] = byte }
            output[bytes.size] = 0
        }
        required
    } catch (_: Exception) { 0 }
}

@CName("deadliner_lifi_cancel")
fun deadlinerLifiCancel(): Int = try {
    runBlocking { PersistenceRuntime.cancelLifiRun() }
    1
} catch (_: Exception) { 0 }

@CName("deadliner_lifi_process_input")
fun deadlinerLifiProcessInput(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    return runBlocking { PersistenceRuntime.processLifiInput(resolvedConfig, resolvedText) }
}

@CName("deadliner_lifi_submit_tool_result")
fun deadlinerLifiSubmitToolResult(
    toolCallId: CPointer<ByteVar>?,
    tool: CPointer<ByteVar>?,
    payload: CPointer<ByteVar>?,
): Int {
    val resolvedId = toolCallId?.toKString() ?: return 0
    val resolvedTool = tool?.toKString() ?: return 0
    val resolvedPayload = payload?.toKString() ?: return 0
    return runBlocking { PersistenceRuntime.submitLifiToolResult(resolvedId, resolvedTool, resolvedPayload) }
}

@CName("deadliner_lifi_extract_tasks_json")
fun deadlinerLifiExtractTasksJson(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    val writeResult = runBlocking {
        writeCallerOwnedJson(PersistenceRuntime.extractLifiTasksJson(resolvedConfig, resolvedText), output, outputCapacity)
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
}

@CName("deadliner_lifi_extract_habits_json")
fun deadlinerLifiExtractHabitsJson(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    val writeResult = runBlocking {
        writeCallerOwnedJson(PersistenceRuntime.extractLifiHabitsJson(resolvedConfig, resolvedText), output, outputCapacity)
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
}

@CName("deadliner_lifi_generate_monthly_analysis_json")
fun deadlinerLifiGenerateMonthlyAnalysisJson(
    configPayload: CPointer<ByteVar>?,
    requestPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedRequest = requestPayload?.toKString() ?: return 0
    val writeResult = runBlocking {
        writeCallerOwnedJson(
            PersistenceRuntime.generateLifiMonthlyAnalysisJson(resolvedConfig, resolvedRequest),
            output,
            outputCapacity,
        )
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
}

@CName("deadliner_lifi_drain_events_json")
fun deadlinerLifiDrainEventsJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = PersistenceRuntime.drainLifiEventsJson()
    val required = writeCallerOwnedJson(payload, output, outputCapacity)
    if (output != null && required > 0 && outputCapacity >= required) {
        PersistenceRuntime.acknowledgeLifiEvents(payload)
    }
    return required
}

@CName("deadliner_lifi_pending_event_count")
fun deadlinerLifiPendingEventCount(): Int = PersistenceRuntime.lifiPendingEventCount()

@CName("deadliner_lifi_reset")
fun deadlinerLifiReset(): Int = PersistenceRuntime.resetLifi()


/** Generic V2 command channel. Mutating commands require an idempotent commandId. */
@CName("deadliner_lifi_v2_command_json")
fun deadlinerLifiV2CommandJson(request: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = request?.toKString() ?: return 0
    return try { runBlocking { writeCallerOwnedJson(PersistenceRuntime.lifiV2Command(payload), output, capacity) } }
    catch (_: Exception) { 0 }
}
