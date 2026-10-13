@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package com.deadliner.bridge

import com.deadliner.domain.quickadd.model.QuickAddModelSession
import com.deadliner.domain.quickadd.model.QuickAddModelStatus
import com.deadliner.domain.quickadd.model.QuickAddModelState
import kotlin.concurrent.Volatile
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val sessionMutex = Mutex()
@Volatile private var modelSession: QuickAddModelSession? = null

@Serializable
private data class ModelRecognitionRequest(val text: String, val kind: String = "TASK", val timezone: String = "Asia/Shanghai", val now: String? = null)

/** Called only on a NAPI async worker. Recognition uses a separate worker while this downloads. */
@CName("deadliner_quick_add_model_prepare")
fun deadlinerQuickAddModelPrepare(directory: CPointer<ByteVar>?, manifest: CPointer<ByteVar>?): Int = runCatching {
    val cache = directory?.toKString() ?: return 0
    val config = manifest?.toKString().orEmpty()
    runBlocking {
        val session = sessionMutex.withLock {
            modelSession ?: QuickAddModelSession(cache, "ohos", config).also { modelSession = it }
        }
        if (session.prepare()) 1 else 0
    }
}.getOrDefault(0)

/** One allocated result per call avoids buffer-sizing races and double inference. */
@CName("deadliner_quick_add_model_recognize")
fun deadlinerQuickAddModelRecognize(request: CPointer<ByteVar>?): CPointer<ByteVar>? = runCatching {
    val input = Json.decodeFromString<ModelRecognitionRequest>(request?.toKString() ?: return null)
    val payload = runBlocking {
        val session = sessionMutex.withLock { modelSession } ?: return@runBlocking null
        if (input.now == null) session.recognizeJson(input.text, input.kind, input.timezone)
        else session.recognize(input.text, input.kind, input.timezone, Instant.parse(input.now)).toJson()
    } ?: return null
    val bytes = payload.encodeToByteArray()
    nativeHeap.allocArray<ByteVar>(bytes.size + 1).also { pointer ->
        bytes.forEachIndexed { index, value -> pointer[index] = value }
        pointer[bytes.size] = 0
    }
}.getOrNull()

@CName("deadliner_quick_add_model_free")
fun deadlinerQuickAddModelFree(pointer: CPointer<ByteVar>?) { if (pointer != null) nativeHeap.free(pointer) }

@CName("deadliner_quick_add_model_status")
fun deadlinerQuickAddModelStatus(): CPointer<ByteVar> {
    val bytes = (modelSession?.statusJson() ?: QuickAddModelStatus(QuickAddModelState.RULES_ONLY).toJson()).encodeToByteArray()
    return nativeHeap.allocArray<ByteVar>(bytes.size + 1).also { pointer ->
        bytes.forEachIndexed { index, value -> pointer[index] = value }
        pointer[bytes.size] = 0
    }
}
