@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddParser
import com.deadliner.domain.quickadd.QuickAddProposal
import com.deadliner.domain.quickadd.inference.QuickAddModelOutput
import com.deadliner.domain.quickadd.inference.QuickAddOutputTensor
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Offline benchmark adapter: executes production rules and codec, never a duplicate parser. */
class TrainingBenchmarkAdapterTest {
    @Test fun emitProductionProposals() {
        val path = getenv("QUICK_ADD_BENCHMARK_INPUT")?.toKString() ?: return
        val parent = path.substringBeforeLast('/')
        val reader = createQuickAddModelFileStore(parent).reader(path.substringAfterLast('/'))
        val bytes = mutableListOf<Byte>()
        try {
            val buffer = ByteArray(65536)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                repeat(count) { bytes += buffer[it] }
            }
        } finally { reader.close() }
        val input = Json.parseToJsonElement(bytes.toByteArray().decodeToString()).jsonObject
        val codec = input["manifest"]?.let {
            val manifest = QuickAddModelManifest.parse(it.toString())
            QuickAddCharacterCodec(manifest.modelId, manifest.codec)
        }
        val parser = QuickAddParser()
        for (element in input.getValue("rows").jsonArray) {
            val row = element.jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            row["inputIds"]?.let { expected ->
                val encoded = requireNotNull(codec).encode(text, row.getValue("preferredKind").jsonPrimitive.content)
                assertEquals(expected.jsonArray.map { it.jsonPrimitive.long }, encoded.tensors[0].integers.toList(), text)
                assertEquals(row.getValue("attentionMask").jsonArray.map { it.jsonPrimitive.long }, encoded.tensors[1].integers.toList(), text)
            }
            val kind = row.getValue("preferredKind").jsonPrimitive.content
            val now = Instant.parse(row.getValue("now").jsonPrimitive.content)
            val zone = TimeZone.of(row.getValue("timezone").jsonPrimitive.content)
            val ruleStart = TimeSource.Monotonic.markNow()
            val rules = parser.parse(text, kind, now, zone)
            val ruleMs = ruleStart.elapsedNow().inWholeNanoseconds / 1e6
            val modelStart = TimeSource.Monotonic.markNow()
            val model: QuickAddProposal? = if (codec != null && row["intent"] != null) runCatching {
                val output = QuickAddModelOutput(listOf(
                    QuickAddOutputTensor("intent_logits", longArrayOf(1, 3), row.getValue("intent").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()),
                    QuickAddOutputTensor("slot_logits", longArrayOf(1, 128, 9), row.getValue("slots").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()),
                ))
                codec.decode(output, text, kind, now, zone)
            }.getOrNull() else null
            val modelMs = modelStart.elapsedNow().inWholeNanoseconds / 1e6 + (row["inferenceMs"]?.jsonPrimitive?.double ?: 0.0)
            // An available model rejection never falls through to rules, including encoding limits.
            val hybrid = if (codec != null) QuickAddRecognitionPolicy().decoded(model, text, kind)
                else QuickAddRecognitionPolicy().fallback(text, kind, now, zone)
            val result = buildJsonObject {
                put("id", row.getValue("id"))
                put("rules", Json.parseToJsonElement(rules.toJson()))
                put("model", model?.let { Json.parseToJsonElement(it.toJson()) } ?: JsonNull)
                put("hybrid", Json.parseToJsonElement(hybrid.toJson()))
                put("rulesMs", ruleMs); put("modelMs", modelMs); put("hybridMs", ruleMs + modelMs)
            }
            println("QUICK_ADD_BENCHMARK $result")
        }
    }
}
