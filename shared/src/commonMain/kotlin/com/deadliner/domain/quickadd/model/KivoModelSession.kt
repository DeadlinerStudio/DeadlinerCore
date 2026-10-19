package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddProposal
import com.deadliner.domain.quickadd.inference.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** All three frozen graphs must be ready before publishing a usable runtime. */
internal class KivoModelSession(private val cacheDirectory: String, platform: String,
    private val manifest: KivoModelManifest,
    private val modelFactory: (String) -> QuickAddDownloadedModel = { QuickAddDownloadedModel(cacheDirectory) }) {
    private val sources = manifest.sources(platform)
    private val tokenizer = KivoTokenizer(manifest.tokenizer)
    private val decoder = KivoDecoder(tokenizer)
    private val mutex = Mutex()
    @Volatile private var models: Map<String, QuickAddDownloadedModel> = emptyMap()
    @Volatile private var ready = false
    @Volatile private var savedStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, manifest.modelId)
    private val policy = QuickAddRecognitionPolicy()

    suspend fun prepare(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (ready) return@withLock true
            models.values.forEach { it.close() }
            models = sources.mapValues { (role, _) -> modelFactory(role) }
            savedStatus = QuickAddModelStatus(QuickAddModelState.PREPARING, manifest.modelId, phase = "CHECKING")
            try {
                for ((role, model) in models) {
                    val status = model.prepare(sources.getValue(role))
                    if (status.state != QuickAddModelState.READY) {
                        savedStatus = status.copy(modelId = manifest.modelId)
                        models.values.forEach { it.close() }
                        models = emptyMap()
                        return@withLock false
                    }
                }
                ready = true
                savedStatus = QuickAddModelStatus(QuickAddModelState.READY, manifest.modelId, phase = "READY")
                true
            } catch (cancelled: CancellationException) {
                savedStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, manifest.modelId, phase = "CANCELLED")
                withContext(NonCancellable) { models.values.forEach { it.close() }; models = emptyMap() }
                throw cancelled
            }
        }
    }

    fun statusJson(): String {
        if (ready || models.isEmpty()) return savedStatus.toJson()
        val current = models.values.map { it.status.value }.firstOrNull { it.state == QuickAddModelState.PREPARING }
            ?: return savedStatus.toJson()
        val downloaded = models.entries.sumOf { (role, model) ->
            if (model.status.value.state == QuickAddModelState.READY) sources.getValue(role).sizeBytes
            else model.status.value.downloadedBytes
        }
        return current.copy(modelId = manifest.modelId, downloadedBytes = downloaded,
            totalBytes = sources.values.sumOf { it.sizeBytes }).toJson()
    }

    suspend fun recognize(text: String, kind: String, now: Instant, zone: TimeZone): QuickAddProposal {
        if (text.isBlank()) return QuickAddProposal(kind = kind, source = "MODEL")
        if (!mutex.tryLock()) return policy.pending(text, kind)
        try {
            if (!ready) return rejected(text, kind)
            val encoded = try { tokenizer.encode(text, kind) } catch (_: IllegalArgumentException) {
                return rejected(text, kind)
            }
            val output = models.getValue("encoder").inferIfReady(encoded, sources.getValue("encoder").modelId)
                ?: return fail(text, kind)
            val draft = decoder.select(output, text, kind, now, zone)
            val headKind = draft.proposal.kind
            val input = QuickAddModelInput(listOf(QuickAddTensor("features", longArrayOf(1, 1530),
                QuickAddTensorType.FLOAT32, floats = draft.qualityFeature())), listOf("score"))
            val quality = models.getValue(headKind).inferIfReady(input, sources.getValue(headKind).modelId)
                ?: return fail(text, kind)
            require(quality.tensors.size == 1 && quality.tensors[0].shape.contentEquals(longArrayOf(1)) &&
                quality.tensors[0].values.size == 1)
            return decoder.gate(draft, quality.tensors[0].values[0], manifest.calibration.getValue(headKind),
                manifest.thresholds.getValue(headKind))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return fail(text, kind) }
        finally { mutex.unlock() }
    }

    private fun rejected(text: String, kind: String) = policy.rejected(text, kind).copy(recognitionState = "KIVO_PARTIAL")

    private fun fail(text: String, kind: String): QuickAddProposal {
        ready = false
        savedStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, manifest.modelId,
            phase = "FAILED", reason = "Kivo execution failed; keep input for confirmation")
        return rejected(text, kind)
    }

    suspend fun close() = mutex.withLock {
        ready = false
        models.values.forEach { it.close() }
        models = emptyMap()
        savedStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, manifest.modelId)
    }
}
