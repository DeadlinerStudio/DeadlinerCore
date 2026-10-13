package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlin.test.*

class QuickAddModelRepositoryTest {
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun source() = QuickAddModelSource("v1", "https://models.example/quick-add.onnx", hash, 3, QuickAddModelFormat.ONNX)

    @Test fun sha256KnownVectorsAndStreamingBoundaries() {
        fun digest(text: String) = ModelSha256().apply { update(text.encodeToByteArray()) }.hex()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(""))
        assertEquals(hash, digest("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        val repeated = ByteArray(1000) { 'a'.code.toByte() }
        val streaming = ModelSha256()
        repeat(1000) { streaming.update(repeated) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", streaming.hex())
    }

    @Test fun downloadsOnceThenLoadsVerifiedCacheOffline() = runBlocking {
        val files = MemoryFiles()
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("abc") })
        val repo = QuickAddModelRepository(files, client)
        try {
            val progress = mutableListOf<Pair<Long, Long>>()
            val first = repo.download(source()) { bytes, total -> progress += bytes to total }
            assertEquals(0L to 3L, progress.first())
            assertEquals(3L to 3L, progress.last())
            client.close()
            assertEquals(first, repo.download(source()))
            assertEquals(1, requests)
            assertEquals(setOf(source().cacheName), files.entries.keys)
        } finally { repo.close(); client.close() }
    }

    @Test fun corruptOrOversizedDownloadsNeverBecomeCachedModels() = runBlocking {
        for (body in listOf("bad", "abcdef")) {
            val files = MemoryFiles()
            val client = HttpClient(MockEngine { respond(body) })
            try {
                val repo = QuickAddModelRepository(files, client)
                assertFailsWith<IllegalStateException> { repo.download(source()) }
                assertTrue(files.entries.isEmpty())
            } finally { client.close() }
        }
    }

    @Test fun revalidatesAndReplacesCorruptCache() = runBlocking {
        val files = MemoryFiles().apply { entries[source().cacheName] = "bad".encodeToByteArray() }
        val client = HttpClient(MockEngine { respond("abc") })
        try {
            QuickAddModelRepository(files, client).download(source())
            assertEquals("abc", files.entries[source().cacheName]!!.decodeToString())
        } finally { client.close() }
    }

    @Test fun failedHttpAndLoadUseRules() = runBlocking {
        for (httpFailure in listOf(true, false)) {
            val client = HttpClient(MockEngine { respond("abc", if (httpFailure) HttpStatusCode.NotFound else HttpStatusCode.OK) })
            val backend = TestBackend().apply { failLoad = !httpFailure }
            val model = QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(backend)) { path, _ -> path }
            try {
                assertEquals(QuickAddModelState.RULES_ONLY, model.prepare(source()).state)
                val result = QuickAddLocalRecognizer(model).recognize("明天下午六点交报告")
                assertTrue(result.ready)
                assertEquals("LOCAL", result.source)
                assertEquals("交报告", result.title)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun unavailableRuntimeDoesNotDownload() = runBlocking {
        val client = HttpClient(MockEngine { error("Must not request a model") })
        val backend = TestBackend().apply { enabled = false }
        val model = QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(backend)) { path, _ -> path }
        try {
            assertEquals(QuickAddModelState.RULES_ONLY, model.prepare(source()).state)
            assertEquals(QuickAddModelState.RULES_ONLY, model.prepare(null).state)
        } finally { model.close(); client.close() }
    }

    @Test fun liveRecognitionDoesNotWaitForDownloadAndCancellationPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { started.complete(Unit); release.await(); respond("abc") })
        val model = QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(TestBackend())) { path, _ -> path }
        val job = async { model.prepare(source()) }
        try {
            started.await()
            assertEquals("DOWNLOADING", model.status.value.phase)
            assertEquals(0L, model.status.value.downloadedBytes)
            assertEquals(3L, model.status.value.totalBytes)
            val result = withTimeout(1000) { QuickAddLocalRecognizer(model, Codec()).recognize("买牛奶") }
            assertEquals("LOCAL", result.source)
            assertNull(withTimeout(1000) { model.inferIfReady(Codec().encode("买牛奶", "TASK"), "v1") })
            job.cancel()
            release.complete(Unit)
            withTimeout(1000) { job.join() }
            assertEquals(QuickAddModelState.RULES_ONLY, model.status.value.state)
            assertTrue(job.isCancelled)
            assertEquals("CANCELLED", model.status.value.phase)
        } finally { release.complete(Unit); job.cancelAndJoin(); model.close(); client.close() }
    }

    @Test fun readyModelUsesCodecAndInferenceFailureFallsBack() = runBlocking {
        val client = HttpClient(MockEngine { respond("abc") })
        val backend = TestBackend()
        val model = QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(backend)) { path, _ -> path }
        try {
            assertEquals(QuickAddModelState.READY, model.prepare(source()).state)
            val recognizer = QuickAddLocalRecognizer(model, Codec())
            assertEquals("MODEL", recognizer.recognize("买牛奶").source)
            assertNull(model.inferIfReady(Codec().encode("买牛奶", "TASK"), "wrong-version"))
            backend.failInfer = true
            assertEquals("LOCAL", recognizer.recognize("买牛奶").source)
            assertEquals(QuickAddModelState.RULES_ONLY, model.status.value.state)
        } finally { model.close(); client.close() }
    }

    @Test fun modelRejectAndOutOfVocabularyNeverFallBackToReadyRuleDraft() = runBlocking {
        val client = HttpClient(MockEngine { respond("abc") })
        val model = QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(TestBackend())) { path, _ -> path }
        try {
            model.prepare(source())
            for (codec in listOf(Codec(reject = true), Codec(invalidInput = true))) {
                val result = QuickAddLocalRecognizer(model, codec).recognize("明天下午3点交报告")
                assertEquals("REJECTED", result.recognitionState)
                assertEquals("MODEL", result.source)
                assertFalse(result.ready)
                assertNull(result.dueAt)
            }
        } finally { model.close(); client.close() }
    }

    private class Codec(val reject: Boolean = false, val invalidInput: Boolean = false) : QuickAddSemanticCodec {
        override val modelId = "v1"
        override fun encode(text: String, preferredKind: String): QuickAddModelInput {
            require(!invalidInput)
            return QuickAddModelInput(
            listOf(QuickAddTensor("ids", longArrayOf(1), QuickAddTensorType.INT32, longArrayOf(1))), listOf("logits"))
        }
        override fun decode(output: QuickAddModelOutput, text: String, preferredKind: String,
            now: kotlinx.datetime.Instant, timezone: kotlinx.datetime.TimeZone) = if (reject) null else com.deadliner.domain.quickadd.QuickAddProposal(title = text, ready = true)
    }
    private class TestBackend : QuickAddInferenceBackend {
        override val name = "TEST"
        var enabled = true
        override val available get() = enabled
        var failLoad = false
        var failInfer = false
        override fun load(modelPath: String) { check(!failLoad) }
        override fun infer(input: QuickAddModelInput): QuickAddModelOutput {
            check(!failInfer)
            return QuickAddModelOutput(listOf(QuickAddOutputTensor("logits", longArrayOf(1), floatArrayOf(1f))))
        }
        override fun close() = Unit
    }
    private class MemoryFiles : QuickAddModelFileStore {
        val entries = mutableMapOf<String, ByteArray>()
        override fun path(name: String) = "/cache/" + name
        override fun exists(name: String) = entries.containsKey(name)
        override fun reader(name: String): QuickAddModelReader {
            val bytes = entries.getValue(name); var position = 0
            return object : QuickAddModelReader {
                override fun read(buffer: ByteArray): Int {
                    if (position == bytes.size) return -1
                    val count = minOf(buffer.size, bytes.size - position)
                    bytes.copyInto(buffer, 0, position, position + count); position += count; return count
                }
                override fun close() = Unit
            }
        }
        override fun writer(name: String): QuickAddModelWriter {
            entries[name] = byteArrayOf()
            return object : QuickAddModelWriter {
                override fun write(buffer: ByteArray, count: Int) { entries[name] = entries.getValue(name) + buffer.copyOf(count) }
                override fun close() = Unit
            }
        }
        override fun promote(temporary: String, destination: String) { entries[destination] = entries.remove(temporary)!! }
        override fun delete(name: String) { entries.remove(name) }
    }
}
