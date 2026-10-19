package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddProposal
import kotlin.test.*

class KivoBehaviorTest {
    private fun tokenizer() = KivoTokenizer(KivoTokenizerConfig(
        listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "明", "天", "开", "会"), emptyMap()))

    @Test fun completeLowConfidenceDraftOffersBothAddAndAI() {
        val draft = KivoDraft(QuickAddProposal(title = "开会", dueAt = "2026-10-20T16:00"),
            emptyList(), emptyList(), FloatArray(1515), 0.0, true)
        val result = KivoDecoder(tokenizer()).gate(draft, 0f, KivoCalibration(1.0, 0.0), .9)
        assertTrue(result.ready)
        assertTrue(result.needsAI)
        assertEquals("READY_WITH_AI", result.recognitionState)
    }

    @Test fun confidenceNeverOverridesDeletedSourceContent() {
        val draft = KivoDraft(QuickAddProposal(title = "开会"), emptyList(), listOf("source-content-deleted"),
            FloatArray(1515), 0.0, false)
        val result = KivoDecoder(tokenizer()).gate(draft, 100f, KivoCalibration(1.0, 0.0), .9)
        assertFalse(result.ready)
        assertTrue(result.needsAI)
    }

    @Test fun unavailableScoreOrThresholdKeepsAIOnACompleteDraft() {
        val draft = KivoDraft(QuickAddProposal(title = "开会"), emptyList(), emptyList(), FloatArray(1515), 0.0, true)
        val decoder = KivoDecoder(tokenizer())
        for ((score, threshold) in listOf(null to .9, Float.NaN to .9, 100f to null)) {
            val result = decoder.gate(draft, score, KivoCalibration(1.0, 0.0), threshold)
            assertTrue(result.ready)
            assertTrue(result.needsAI)
        }
    }

    @Test fun coverageRejectsMissingCountNegationAndLocationTail() {
        for (text in listOf("每周跑步三次", "每周跑步不要", "每周跑步在逸夫楼203")) {
            val spans = listOf(KivoSpan("GOAL_WINDOW", 0, 2, 1, 2, 1f), KivoSpan("TITLE", 2, 4, 3, 4, 1f))
            assertEquals(listOf("source-content-deleted"), KivoCoverage.reasons(text, spans, "HABIT"), text)
        }
    }

    @Test fun removedJoinerStillKeepsOriginalCodePointOffsets() {
        val pieces = tokenizer().pieces("明天👩‍💻开会")
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 2, 2 to 5, 5 to 6, 6 to 7, 0 to 0),
            pieces.map { it.start to it.end })
    }

    @Test fun oversizedInputAndLiteralSpecialTokensAreNotTruncatedIntoAcceptance() {
        assertFailsWith<IllegalArgumentException> { tokenizer().encode("明".repeat(127), "TASK") }
        assertFailsWith<IllegalArgumentException> { tokenizer().encode("明天[CLS]开会", "TASK") }
    }
}
