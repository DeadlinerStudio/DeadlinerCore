package com.deadliner.domain.quickadd.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Platform execution only; tokenization and semantic decoding belong in commonMain. */
internal interface QuickAddInferenceBackend {
    val name: String
    val available: Boolean get() = true
    fun load(modelPath: String)
    fun infer(input: QuickAddModelInput): QuickAddModelOutput
    fun close()
}
internal expect fun createQuickAddInferenceBackend(): QuickAddInferenceBackend

enum class QuickAddInferenceError { MODEL_NOT_CONFIGURED, MODEL_LOAD_FAILED, INVALID_INPUT, INFERENCE_FAILED, INVALID_OUTPUT }
class QuickAddInferenceException(val code: QuickAddInferenceError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Own one engine per semantic service. All native calls are serialized off the UI thread. */
class QuickAddInferenceEngine internal constructor(private val backend: QuickAddInferenceBackend) {
    constructor() : this(createQuickAddInferenceBackend())
    private val mutex = Mutex()
    private var loaded = false
    val backendName: String get() = backend.name
    val runtimeAvailable: Boolean get() = backend.available

    @Throws(Exception::class)
    suspend fun load(modelPath: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (modelPath.isBlank()) throw QuickAddInferenceException(
                QuickAddInferenceError.MODEL_NOT_CONFIGURED, "Quick-add model is not configured")
            guarded(QuickAddInferenceError.MODEL_LOAD_FAILED) { backend.load(modelPath) }
            loaded = true
        }
    }

    @Throws(Exception::class)
    suspend fun infer(input: QuickAddModelInput): QuickAddModelOutput {
        val snapshot = input.snapshot()
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!loaded) throw QuickAddInferenceException(
                    QuickAddInferenceError.MODEL_NOT_CONFIGURED, "Load a quick-add model before inference")
                guarded(QuickAddInferenceError.INVALID_INPUT) { snapshot.validate() }
                val output = guarded(QuickAddInferenceError.INFERENCE_FAILED) { backend.infer(snapshot) }
                guarded(QuickAddInferenceError.INVALID_OUTPUT) { output.validate(snapshot.outputNames) }
                output
            }
        }
    }

    @Throws(Exception::class)
    suspend fun isLoaded(): Boolean = mutex.withLock { loaded }

    /** Idempotent; load() may be called again after close(). */
    @Throws(Exception::class)
    suspend fun close() = withContext(Dispatchers.Default) {
        mutex.withLock {
            backend.close()
            loaded = false
        }
    }

    private inline fun <T> guarded(code: QuickAddInferenceError, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw QuickAddInferenceException(code, failure.message ?: code.name, failure)
    }
}
