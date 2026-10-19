package com.deadliner.domain.quickadd.model

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.deadliner.domain.quickadd.QuickAddParser
import com.deadliner.domain.quickadd.QuickAddProposal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** One process-lifetime owner per platform; configuration is supplied by the app release. */
class QuickAddModelSession(private val cacheDirectory: String, private val platform: String, private val manifestJson: String) {
    private val mutex = Mutex()
    @Volatile private var model: QuickAddDownloadedModel? = null
    @Volatile private var configurationStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY)
    private var recognizer: QuickAddLocalRecognizer? = null
    private var attempted = false
    @Volatile private var kivo: KivoModelSession? = null

    @Throws(Exception::class)
    suspend fun prepare(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            kivo?.let { return@withLock it.prepare() }
            if (attempted && (model == null || model?.status?.value?.state == QuickAddModelState.READY))
                return@withLock model?.status?.value?.state == QuickAddModelState.READY
            try {
                if (manifestJson.isBlank()) { attempted = true; return@withLock false }
                if (Json.parseToJsonElement(manifestJson).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull == 2) {
                    val session = KivoModelSession(cacheDirectory, platform, KivoModelManifest.parse(manifestJson))
                    kivo = session
                    return@withLock session.prepare()
                }
                val manifest = QuickAddModelManifest.parse(manifestJson)
                val source = manifest.source(platform) ?: run { attempted = true; return@withLock false }
                val downloaded = model ?: QuickAddDownloadedModel(cacheDirectory).also { model = it }
                recognizer = QuickAddLocalRecognizer(downloaded, QuickAddCharacterCodec(manifest.modelId, manifest.codec))
                val ready = downloaded.prepare(source).state == QuickAddModelState.READY
                attempted = ready // A failed download can be retried on the next sheet opening.
                ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                configurationStatus = QuickAddModelStatus(QuickAddModelState.RULES_ONLY, phase = "FAILED", reason = failure.message.orEmpty())
                false
            }
        }
    }

    @Throws(Exception::class)
    suspend fun recognize(text: String, kind: String, timezone: String, now: Instant = Clock.System.now()): QuickAddProposal {
        kivo?.let { return it.recognize(text, kind, now, TimeZone.of(timezone)) }
        // Do not wait for the preparation mutex: downloading must not delay typing.
        val active = if (mutex.tryLock()) try { recognizer } finally { mutex.unlock() } else null
        val zone = TimeZone.of(timezone)
        return active?.recognize(text, kind, now, zone) ?: if (model?.status?.value?.state == QuickAddModelState.READY) QuickAddRecognitionPolicy().pending(text, kind)
            else QuickAddRecognitionPolicy().fallback(text, kind, now, zone)
    }

    @Throws(Exception::class)
    suspend fun recognizeJson(text: String, kind: String, timezone: String): String = recognize(text, kind, timezone).toJson()

    fun statusJson(): String = kivo?.statusJson() ?: (model?.status?.value ?: configurationStatus).toJson()

    @Throws(Exception::class)
    suspend fun close() = mutex.withLock {
        recognizer = null
        kivo?.close()
        kivo = null
        model?.close()
        model = null
        attempted = false
    }
}
