package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class QuickAddModelState { RULES_ONLY, PREPARING, READY }
@Serializable
data class QuickAddModelStatus(val state: QuickAddModelState, val modelId: String = "", val reason: String = "",
    val phase: String = "UNCONFIGURED", val downloadedBytes: Long = 0, val totalBytes: Long = 0) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(this)
}

/** prepare() runs in a background lifecycle job; input processing never waits for downloads. */
class QuickAddDownloadedModel internal constructor(
    private val repository: QuickAddModelRepository,
    private val engine: QuickAddInferenceEngine,
    private val prepareFile: (String, QuickAddModelFormat) -> String = ::prepareQuickAddModelFile,
) {
    constructor(cacheDirectory: String) : this(QuickAddModelRepository(cacheDirectory), QuickAddInferenceEngine())
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(QuickAddModelStatus(QuickAddModelState.RULES_ONLY))
    val status: StateFlow<QuickAddModelStatus> = mutableStatus.asStateFlow()

    @Throws(Exception::class)
    suspend fun prepare(source: QuickAddModelSource?): QuickAddModelStatus = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (source == null || !engine.runtimeAvailable) {
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.RULES_ONLY,
                    reason = if (source == null) "Model is not configured" else "Inference runtime is not packaged",
                    phase = if (source == null) "UNCONFIGURED" else "FAILED")
                return@withLock mutableStatus.value
            }
            mutableStatus.value = QuickAddModelStatus(QuickAddModelState.PREPARING, source.modelId, phase = "CHECKING")
            try {
                val downloaded = repository.download(source) { bytes, total ->
                    mutableStatus.value = QuickAddModelStatus(QuickAddModelState.PREPARING, source.modelId,
                        phase = "DOWNLOADING", downloadedBytes = bytes, totalBytes = total)
                }
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.PREPARING, source.modelId, phase = "LOADING")
                currentCoroutineContext().ensureActive()
                val prepared = prepareFile(downloaded, source.format)
                currentCoroutineContext().ensureActive()
                engine.load(prepared)
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.READY, source.modelId, phase = "READY")
            } catch (cancelled: CancellationException) {
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, reason = "Model preparation cancelled", phase = "CANCELLED")
                throw cancelled
            } catch (failure: Exception) {
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.RULES_ONLY,
                    reason = failure.message ?: "Model preparation failed", phase = "FAILED")
            }
            mutableStatus.value
        }
    }

    /** A busy download/compile immediately yields to rules instead of holding up live typing. */
    internal suspend fun inferIfReady(input: QuickAddModelInput, modelId: String): QuickAddModelOutput? {
        if (!mutex.tryLock()) return null
        try {
            if (mutableStatus.value.state != QuickAddModelState.READY || mutableStatus.value.modelId != modelId) return null
            return try { engine.infer(input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableStatus.value = QuickAddModelStatus(QuickAddModelState.RULES_ONLY,
                    reason = failure.message ?: "Model inference failed", phase = "FAILED")
                null
            }
        } finally { mutex.unlock() }
    }

    /** Cancel/join prepare jobs before closing this owner. */
    @Throws(Exception::class)
    suspend fun close() = mutex.withLock {
        mutableStatus.value = QuickAddModelStatus(QuickAddModelState.RULES_ONLY)
        engine.close()
        repository.close()
    }
}
