@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Opt-in release checks run with actual exported logits, never fabricated tensors. */
class ReleasedModelAcceptanceTest {
    private fun directory(): String? = getenv("QUICK_ADD_RELEASE_CHECK_DIR")?.toKString()

    private fun read(directory: String, name: String): String {
        val reader = createQuickAddModelFileStore(directory).reader(name)
        val bytes = mutableListOf<Byte>()
        try {
            val buffer = ByteArray(65536)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                repeat(count) { bytes += buffer[it] }
            }
        } finally { reader.close() }
        return bytes.toByteArray().decodeToString()
    }

    @Test fun realExportsDecodeWithProductionManifest() {
        val directory = directory() ?: return // Release acceptance is run separately with actual artifacts.
        val manifest = QuickAddModelManifest.parse(read(directory, "quick-add-model.json"))
        val codec = QuickAddCharacterCodec(manifest.modelId, manifest.codec)
        val fixtures = Json.parseToJsonElement(read(directory, "decoder-fixtures.json")).jsonArray
        for (element in fixtures) {
            val row = element.jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            val output = QuickAddModelOutput(listOf(
                QuickAddOutputTensor("intent_logits", longArrayOf(1, 3), row.getValue("intent").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()),
                QuickAddOutputTensor("slot_logits", longArrayOf(1, 128, 9), row.getValue("slots").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()),
            ))
            val proposal = codec.decode(output, text, "TASK", Instant.parse("2026-10-10T04:00:00Z"), TimeZone.of("Asia/Shanghai"))
            val label = row.getValue("backend").jsonPrimitive.content + ": " + text
            when (row.getValue("expected").jsonPrimitive.content) {
                "OTHER" -> { assertNotNull(proposal, label); assertFalse(proposal.ready, label); assertTrue(proposal.needsAI, label) }
                else -> {
                    if (row.getValue("expected").jsonPrimitive.content == "READY_OR_DEFER" && proposal == null) {
                        println("Release acceptance $label -> DEFER")
                        continue
                    }
                    assertNotNull(proposal, label)
                    assertTrue(proposal.ready, label)
                    assertEquals(row.getValue("title").jsonPrimitive.content, proposal.title, label)
                    assertEquals(row.getValue("kind").jsonPrimitive.content, proposal.kind, label)
                    row["reminderTime"]?.jsonPrimitive?.contentOrNull?.let { assertEquals(it, proposal.reminderTime, label) }
                    assertEquals("MODEL", proposal.source, label)
                }
            }
            println("Release acceptance $label -> ${proposal?.toJson() ?: "DEFER"}")
        }
    }

    @Test fun publicReleaseDownloadsThroughProductionRepository() = runBlocking {
        val directory = directory() ?: return@runBlocking
        val manifest = QuickAddModelManifest.parse(read(directory, "quick-add-model.json"))
        createQuickAddModelFileStore(directory + "/download-verification")
        val repository = QuickAddModelRepository(directory + "/download-verification")
        try {
            for (platform in listOf("android", "ios", "ohos")) {
                val source = checkNotNull(manifest.source(platform))
                val events = mutableListOf<Long>()
                val path = repository.download(source) { bytes, _ -> events += bytes }
                assertTrue(path.isNotBlank())
                if (events.isNotEmpty()) assertEquals(source.sizeBytes, events.last())
                assertEquals(path, repository.download(source) { _, _ -> fail("Valid cache downloaded again") })
            }
        } finally { repository.close() }
    }
}
