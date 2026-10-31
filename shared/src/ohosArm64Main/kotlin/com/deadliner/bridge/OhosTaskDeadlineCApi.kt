@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString

/** Additive export. Cache the mutation response across NAPI's size probe and buffer fill. */
@CName("deadliner_persistence_task_deadline_json")
fun deadlinerPersistenceTaskDeadlineJson(
    payload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val request = payload?.toKString() ?: return 0
    return writeStateMutationJson("task_deadline", request, output, outputCapacity) {
        runCatching {
            val database = checkNotNull(PersistenceRuntime.database) { "Persistence not initialized" }
            TaskDeadlineJsonBridge(database.tasks).execute(request)
        }.getOrNull()
    }
}
