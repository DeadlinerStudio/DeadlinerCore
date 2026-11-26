package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import kotlin.test.*

class KivoSessionTest {
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun manifest() = KivoModelManifest(2, "kivo-test", "kivo-joint-v1", true, false,
        mapOf("android" to listOf("encoder", "TASK", "HABIT").associateWith {
            QuickAddModelArtifact("https://models.example/$it", hash, 3)
        }), KivoTokenizerConfig(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]"), emptyMap()),
        listOf("TASK", "HABIT").associateWith { KivoCalibration(1.0, 0.0) }, mapOf("TASK" to .9, "HABIT" to .9))

    @Test fun failedSecondGraphClosesPartialRuntimeAndRetryLoadsAllThree() = runBlocking {
        val files = MemoryFiles()
        val client = HttpClient(MockEngine { respond("abc") })
        val backends = mutableListOf<Backend>()
        var failTask = true
        val session = KivoModelSession("unused", "android", manifest()) { role ->
            val backend = Backend(role, fail = role == "TASK" && failTask).also { backends += it }
            QuickAddDownloadedModel(QuickAddModelRepository(files, client), QuickAddInferenceEngine(backend)) { path, _ -> path }
        }
        try {
            assertFalse(session.prepare())
            assertTrue(backends.all { it.closed })
            val rejected = session.recognize("明天下午4点开会", "TASK", Instant.parse("2026-10-19T04:00:00Z"), TimeZone.UTC)
            assertFalse(rejected.ready)
            assertTrue(rejected.needsAI)
            failTask = false
            assertTrue(session.prepare())
            assertEquals(3, backends.count { it.loaded && !it.closed })
            assertEquals("READY", Json.parseToJsonElement(session.statusJson()).jsonObject.getValue("phase").jsonPrimitive.content)
        } finally { session.close(); client.close() }
        assertTrue(backends.all { it.closed })
    }

    @Test fun typingDoesNotWaitForThreeGraphDownloadAndCancellationCleansUp() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { started.complete(Unit); release.await(); respond("abc") })
        val backends = mutableListOf<Backend>()
        val session = KivoModelSession("unused", "android", manifest()) { role ->
            val backend = Backend(role).also { backends += it }
            QuickAddDownloadedModel(QuickAddModelRepository(MemoryFiles(), client), QuickAddInferenceEngine(backend)) { path, _ -> path }
        }
        val job = async { session.prepare() }
        try {
            started.await()
            val pending = withTimeout(1000) { session.recognize("明天下午4点开会", "TASK",
                Instant.parse("2026-10-19T04:00:00Z"), TimeZone.UTC) }
            assertFalse(pending.ready)
            assertTrue(pending.needsAI)
            job.cancelAndJoin()
            assertTrue(backends.all { it.closed })
            assertEquals("CANCELLED", Json.parseToJsonElement(session.statusJson()).jsonObject.getValue("phase").jsonPrimitive.content)
        } finally { release.complete(Unit); job.cancelAndJoin(); session.close(); client.close() }
    }

    private class Backend(val role: String, val fail: Boolean = false) : QuickAddInferenceBackend {
        override val name = "TEST_KIVO"
        var loaded = false
        var closed = false
        override fun load(modelPath: String) { check(!fail) { "$role load failed" }; loaded = true }
        override fun infer(input: QuickAddModelInput): QuickAddModelOutput = error("No inference before all graphs are ready")
        override fun close() { closed = true }
    }

    private class MemoryFiles : QuickAddModelFileStore {
        val data = mutableMapOf<String, ByteArray>()
        override fun path(name: String) = name
        override fun exists(name: String) = name in data
        override fun reader(name: String) = object : QuickAddModelReader {
            private val bytes = data.getValue(name)
            private var offset = 0
            override fun read(buffer: ByteArray): Int {
                if (offset == bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + count); offset += count
                return count
            }
            override fun close() = Unit
        }
        override fun writer(name: String) = object : QuickAddModelWriter {
            override fun write(buffer: ByteArray, count: Int) { data[name] = (data[name] ?: byteArrayOf()) + buffer.copyOf(count) }
            override fun close() = Unit
        }
        override fun promote(temporary: String, destination: String) { data[destination] = data.remove(temporary)!! }
        override fun delete(name: String) { data.remove(name) }
    }
}
