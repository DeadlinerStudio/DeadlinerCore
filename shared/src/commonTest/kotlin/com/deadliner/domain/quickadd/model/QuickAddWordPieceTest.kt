package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlin.test.*

class QuickAddWordPieceTest {
    private val vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "后", "天", "晚", "上", "8", "点", "打", "电", "话", "call", "##ing", "cafe", "!", "𠀀")
    private fun tokenizer(length: Int = 128) = QuickAddWordPiece(QuickAddWordPieceConfig(vocabulary), length)

    @Test fun chineseWhitespaceAndSupplementaryOffsetsArePreserved() {
        val pieces = tokenizer().pieces("后天晚上 8 点打电话𠀀")
        assertEquals(listOf(2L,4,5,6,7,8,9,10,11,12,17,3), pieces.map { it.id })
        assertEquals(5, pieces[5].start)
        assertEquals(11, pieces[10].start)
        assertEquals(12, pieces[10].end)
    }
    @Test fun greedyEnglishCaseAccentAndUnknownWholeWordMatchBert() {
        assertEquals(listOf(2L,13,14,15,16,1,3), tokenizer().pieces("CALLING café! unknown").map { it.id })
        assertEquals(listOf(2L,1,3), tokenizer().pieces("callunknown").map { it.id })
        assertEquals(listOf(2L,1,3), tokenizer().pieces("a".repeat(101)).map { it.id })
        assertFailsWith<IllegalArgumentException> { tokenizer(4).encode("后天晚上") }
        assertFailsWith<IllegalArgumentException> { tokenizer().encode("call\u0000ing") }
    }
    @Test fun tokenBeginningProjectsToInsideForRemainingCharactersAndSpaces() {
        val slots = FloatArray(128 * 9) { -20f }
        repeat(128) { slots[it * 9] = 20f }
        for (index in listOf(1,2,3)) { slots[index * 9] = -20f; slots[index * 9 + if (index == 1) 1 else 2] = 20f }
        val output = QuickAddModelOutput(listOf(QuickAddOutputTensor("intent_logits", longArrayOf(1,3), floatArrayOf(20f,-20f,-20f)),
            QuickAddOutputTensor("slot_logits",longArrayOf(1,128,9),slots)))
        val projected = tokenizer().project(output,"calling cafe").tensors[1].values
        val labels = (0..11).map { position -> (0..8).maxBy { projected[position * 9 + it] } }
        assertEquals(listOf(1) + List(11) { 2 }, labels)
    }
}
