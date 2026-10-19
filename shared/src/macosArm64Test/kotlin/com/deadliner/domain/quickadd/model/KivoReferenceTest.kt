@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.math.abs
import kotlin.test.*

/** Frozen, already exposed fixture parity. No device or independent accuracy claim. */
class KivoReferenceTest {
    @Test fun actualFrozenEncoderOutputsMatchCoreProposalsAndQualityFeatures() {
        val path = getenv("KIVO_REFERENCE_INPUT")?.toKString() ?: return
        val files = createQuickAddModelFileStore(path.substringBeforeLast('/'))
        val reader = files.reader(path.substringAfterLast('/'))
        val bytes = mutableListOf<Byte>()
        try {
            val buffer = ByteArray(65536)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                repeat(count) { bytes += buffer[it] }
            }
        } finally { reader.close() }
        val root = Json.parseToJsonElement(bytes.toByteArray().decodeToString()).jsonObject
        val config = Json.decodeFromJsonElement<KivoTokenizerConfig>(root.getValue("tokenizer"))
        config.validate()
        val tokenizer = KivoTokenizer(config)
        for (item in root.getValue("tokenizerCases").jsonArray) {
            val row = item.jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            val kind = row.getValue("preferredKind").jsonPrimitive.content
            val encoded = tokenizer.encode(text, kind)
            for (index in 0..1) assertEquals(row.getValue(encoded.tensors[index].name).jsonArray.map { it.jsonPrimitive.long },
                encoded.tensors[index].integers.toList(), row.getValue("id").toString())
            val offsets = tokenizer.pieces(text).map { listOf(it.start, it.end) }.toMutableList()
            while (offsets.size < 96) offsets += listOf(0, 0)
            assertEquals(row.getValue("offsets").jsonArray.map { a -> a.jsonArray.map { it.jsonPrimitive.int } }, offsets,
                "Offsets: " + row.getValue("id"))
        }
        val decoder = KivoDecoder(tokenizer)
        val outputs = mutableListOf<JsonElement>()
        for (item in root.getValue("cases").jsonArray) {
            val row = item.jsonObject
            val input = row.getValue("input").jsonObject
            val text = input.getValue("text").jsonPrimitive.content
            val output = QuickAddModelOutput(row.getValue("encoderOutputs").jsonArray.map {
                val tensor = it.jsonObject
                QuickAddOutputTensor(tensor.getValue("name").jsonPrimitive.content,
                    tensor.getValue("shape").jsonArray.map { n -> n.jsonPrimitive.long }.toLongArray(),
                    tensor.getValue("values").jsonArray.map { v -> v.jsonPrimitive.float }.toFloatArray())
            })
            val draft = decoder.select(output, text, input.getValue("preferredKind").jsonPrimitive.content,
                Instant.parse(input.getValue("now").jsonPrimitive.content), TimeZone.of(input.getValue("timezone").jsonPrimitive.content))
            val expected = row.getValue("result").jsonObject
            val features = draft.qualityFeature()
            val reference = row.getValue("qualityFeature").jsonArray.map { it.jsonPrimitive.float }
            val maximum = features.indices.maxOf { abs(features[it] - reference[it]) }
            assertTrue(maximum < 0.0005f, "$text: feature max error $maximum")
            assertEquals(expected.getValue("hardReasons").jsonArray.map { it.jsonPrimitive.content }, draft.hardReasons, text)
            val headKind = draft.proposal.kind
            val calibration = root.getValue("calibration").jsonObject.getValue(headKind).jsonObject
            val threshold = root.getValue("thresholds").jsonObject.getValue(headKind).jsonObject.getValue("threshold").jsonPrimitive.doubleOrNull
            val proposal = decoder.gate(draft, row.getValue("rawQualityLogit").jsonPrimitive.float,
                KivoCalibration(calibration.getValue("slope").jsonPrimitive.double, calibration.getValue("bias").jsonPrimitive.double), threshold)
            val actual = Json.parseToJsonElement(proposal.toJson()).jsonObject
            val target = expected.getValue("proposal").jsonObject
            for (key in listOf("kind", "title", "dueAt", "note", "period", "targetCount", "weekdaysMask", "reminderTime", "ready", "needsAI"))
                assertEquals(target[key], actual[key], "$text: $key")
            outputs += buildJsonObject {
                put("id", input.getValue("id")); put("proposal", actual)
                put("qualityFeature", JsonArray(features.map { JsonPrimitive(it) }))
            }
        }
        val outputPath = getenv("KIVO_REFERENCE_OUTPUT")?.toKString()
        if (outputPath != null) {
            val store = createQuickAddModelFileStore(outputPath.substringBeforeLast('/'))
            val writer = store.writer(outputPath.substringAfterLast('/'))
            val data = JsonArray(outputs).toString().encodeToByteArray()
            try { writer.write(data, data.size) } finally { writer.close() }
        }
        println("KIVO_PARITY: " + outputs.size + " complete proposals and " + root.getValue("tokenizerCases").jsonArray.size + " tokenizer cases")
    }
}
