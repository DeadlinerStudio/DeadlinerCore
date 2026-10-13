package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*

class QuickAddCharacterCodecTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private val zone = TimeZone.of("Asia/Shanghai")
    private fun codec(text: String, length: Int = 32) = QuickAddCharacterCodec("test-v1",
        QuickAddCharacterConfig(listOf("[PAD]", "[UNK]") + unicodeCharacters(text).distinct(), maxLength = length))
    private fun output(labels: List<Int>, intent: Int = 0, length: Int = 32): QuickAddModelOutput {
        val intentValues = FloatArray(3) { if (it == intent) 10f else -10f }
        val slots = FloatArray(length * 9) { -10f }
        repeat(length) { slots[it * 9 + (labels.getOrNull(it) ?: 0)] = 10f }
        return QuickAddModelOutput(listOf(QuickAddOutputTensor("intent_logits", longArrayOf(1, 3), intentValues),
            QuickAddOutputTensor("slot_logits", longArrayOf(1, length.toLong(), 9), slots)))
    }
    @Test fun semanticTitleIsPreservedWhileOnlyTimeSlotIsNormalized() {
        val text = "明天交明天的报告"
        val codec = codec(text)
        val result = codec.decode(output(listOf(3, 4, 1, 2, 2, 2, 2, 2)), text, "TASK", now, zone)
        assertNotNull(result)
        assertEquals("交明天的报告", result.title)
        assertTrue(result.dueAt!!.startsWith("2026-10-11"))
        assertEquals("MODEL", result.source)
        assertTrue(result.ready)
    }
    @Test fun habitRecurrenceAndReminderAreNormalizedFromIndependentSpans() {
        val text = "每天晚上8点读书"
        val result = codec(text).decode(output(listOf(5, 6, 3, 4, 4, 4, 1, 2), intent = 1), text, "TASK", now, zone)
        assertNotNull(result)
        assertEquals("HABIT", result.kind)
        assertEquals("读书", result.title)
        assertEquals("20:00", result.reminderTime)
    }
    @Test fun uncertaintyOtherIntentMalformedBioAndMultipleTasksYieldToRules() {
        val text = "读书"
        val codec = codec(text)
        val other = codec.decode(output(listOf(1, 2), intent = 2), text, "TASK", now, zone)
        assertNotNull(other)
        assertFalse(other.ready)
        assertTrue(other.needsAI)
        assertNull(codec.decode(output(listOf(2, 2)), text, "TASK", now, zone))
        assertNull(codec.decode(output(listOf(1, 1)), text, "TASK", now, zone))
        val ambiguous = output(listOf(1, 2))
        ambiguous.tensors[0].values.fill(0f)
        assertNull(codec.decode(ambiguous, text, "TASK", now, zone))
        val uncertainSlot = output(listOf(1, 2))
        repeat(9) { uncertainSlot.tensors[1].values[it] = 0f }
        assertNull(codec.decode(uncertainSlot, text, "TASK", now, zone))
    }
    @Test fun longUnknownAndSupplementaryCharactersAreHandledWithoutTruncation() {
        val codec = codec("读📖书", 8)
        val input = codec.encode("读📖书", "TASK")
        assertEquals(listOf(1L, 1L, 1L, 0L, 0L, 0L, 0L, 0L), input.tensors[1].integers.toList())
        assertFailsWith<IllegalArgumentException> { codec.encode("读书".repeat(5), "TASK") }
        assertFailsWith<IllegalArgumentException> { codec.encode("未知", "TASK") }
        assertEquals("读📖书", codec.decode(output(listOf(1, 2, 2), length = 8), "读📖书", "TASK", now, zone)?.title)
    }
    @Test fun wrongOutputShapeAndUnsupportedTimeYieldToRules() {
        val codec = codec("稍后读书")
        val wrongShape = output(listOf(3, 4, 1, 2)).let { it.copy(tensors = listOf(it.tensors[0], it.tensors[1].copy(shape = longArrayOf(1, 9, 32)))) }
        assertNull(codec.decode(wrongShape, "稍后读书", "TASK", now, zone))
        assertNull(codec.decode(output(listOf(3, 4, 1, 2)), "稍后读书", "TASK", now, zone))
    }
    @Test fun missingOrMalformedConfigurationStillReturnsRulePreview() = runBlocking {
        for (config in listOf("", "{}", "not json")) {
            val session = QuickAddModelSession("/tmp", "android", config)
            assertFalse(session.prepare())
            val result = session.recognize("明天交报告", "TASK", "Asia/Shanghai", now)
            assertEquals("交报告", result.title)
            assertTrue(result.ready)
            session.close()
        }
    }
    @Test fun englishLocalePreservesTitleWordsAndNormalizesExtractedMidnight() {
        val time = "tonight at midnight"
        val title = "edit tomorrow's presentation"
        val text = "$title $time"
        val config = QuickAddCharacterConfig(listOf("[PAD]", "[UNK]") + unicodeCharacters(text).distinct(), maxLength = 128, locale = "en")
        val labels = List(title.length) { if (it == 0) 1 else 2 } + listOf(0) + List(time.length) { if (it == 0) 3 else 4 }
        val result = QuickAddCharacterCodec("en-test", config).decode(output(labels, length = 128), text, "TASK", now, zone)
        assertNotNull(result)
        assertEquals(title, result.title)
        assertEquals("2026-10-11T00:00", result.dueAt)
        assertTrue(result.ready)
        assertFailsWith<IllegalArgumentException> { QuickAddCharacterCodec("unsupported", config.copy(locale = "unknown")) }
    }
    @Test fun separatedDateAndClockAndBoundaryCommaProduceOneSemanticTitle() {
        val text = "明天交报告，下午三点"
        val labels = listOf(3, 4, 1, 2, 2, 2, 3, 4, 4, 4)
        val result = codec(text).decode(output(labels), text, "TASK", now, zone)
        assertNotNull(result)
        assertEquals("交报告", result.title)
        assertEquals("2026-10-11T15:00", result.dueAt)
        val conflicting = "明天交报告下午三点晚上八点"
        val competingClocks = listOf(3, 4, 1, 2, 2, 3, 4, 4, 4, 3, 4, 4, 4)
        assertNull(codec(conflicting).decode(output(competingClocks), conflicting, "TASK", now, zone))
    }
    @Test fun droppedTemporalQualifierNeverBecomesAnExactMidnightDeadline() {
        val text = "转钟后交报告"
        assertNull(codec(text).decode(output(listOf(3, 4, 0, 1, 2, 2)), text, "TASK", now, zone))
        val precise = "转钟交报告"
        assertEquals("2026-10-11T00:00", codec(precise).decode(output(listOf(3, 4, 1, 2, 2)), precise, "TASK", now, zone)?.dueAt)
    }
    @Test fun manifestRequiresSafeSourceAndKnownSchemaWithMatchingPlatformFormat() {
        val config = QuickAddCharacterConfig(listOf("[PAD]", "[UNK]", "读", "书"))
        val good = QuickAddModelArtifact("https://models.example/model", "a".repeat(64), 123)
        val manifest = QuickAddModelManifest(1, "zh-v1", mapOf("android" to good, "ios" to good, "ohos" to good), config)
        assertEquals(QuickAddModelFormat.ONNX, manifest.source("android")?.format)
        assertEquals(QuickAddModelFormat.CORE_ML, manifest.source("ios")?.format)
        assertEquals(QuickAddModelFormat.MINDSPORE, manifest.source("ohos")?.format)
        assertNull(manifest.copy(artifacts = emptyMap()).source("android"))
        assertFailsWith<IllegalArgumentException> { manifest.copy(schemaVersion = 2).source("android") }
        assertFailsWith<IllegalArgumentException> { manifest.copy(artifacts = mapOf("android" to good.copy(url = "http://unsafe/model"))).source("android") }
        assertFailsWith<IllegalArgumentException> { manifest.copy(codec = config.copy(vocabulary = listOf("[PAD]", "[UNK]", "重复"))).source("android") }
    }
}
