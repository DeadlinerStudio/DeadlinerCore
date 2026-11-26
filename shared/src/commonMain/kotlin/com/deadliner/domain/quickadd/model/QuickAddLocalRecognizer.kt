package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.*
import com.deadliner.domain.quickadd.inference.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** TODO(model): implement with the delivered vocabulary, labels and calibrated confidence. */
interface QuickAddSemanticCodec {
    val modelId: String
    fun encode(text: String, preferredKind: String): QuickAddModelInput
    fun decode(output: QuickAddModelOutput, text: String, preferredKind: String, now: Instant, timezone: TimeZone): QuickAddProposal?
}

/** Rules only for unavailable execution; semantic rejection remains a pending draft. */
class QuickAddLocalRecognizer(
    private val model: QuickAddDownloadedModel,
    private val codec: QuickAddSemanticCodec? = null,
) {
    private val policy = QuickAddRecognitionPolicy()

    @Throws(Exception::class)
    suspend fun recognize(
        text: String,
        preferredKind: String = "TASK",
        now: Instant = Clock.System.now(),
        timezone: TimeZone = TimeZone.currentSystemDefault(),
    ): QuickAddProposal = withContext(Dispatchers.Default) {
        val decoder = codec
        if (text.isBlank() || decoder == null || model.status.value.state != QuickAddModelState.READY)
            return@withContext policy.fallback(text, preferredKind, now, timezone)
        val input = try { decoder.encode(text, preferredKind) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { return@withContext policy.rejected(text, preferredKind) }
        catch (_: Exception) { return@withContext policy.fallback(text, preferredKind, now, timezone) }
        val output = model.inferIfReady(input, decoder.modelId) ?: return@withContext (if (model.status.value.state == QuickAddModelState.READY) policy.pending(text, preferredKind)
            else policy.fallback(text, preferredKind, now, timezone))
        try {
            policy.decoded(decoder.decode(output, text, preferredKind, now, timezone), text, preferredKind)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { policy.rejected(text, preferredKind) }
    }
}
