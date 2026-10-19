package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*

/** BertNormalizer + WordPiece with original code-point offsets, never UTF-16 slicing. */
internal class KivoTokenizer(private val config: KivoTokenizerConfig) {
    private val ids = config.vocabulary.withIndex().associate { it.value to it.index.toLong() }
    internal data class Piece(val id: Long, val start: Int, val end: Int)
    private data class UnitChar(val value: String, val position: Int)

    fun pieces(text: String): List<Piece> {
        val characters = unicodeCharacters(text)
        require(characters.size <= 126) { "Kivo text context exceeded" }
        require(listOf("[CLS]", "[SEP]", "[MASK]", "[PAD]", "[UNK]").none { it in text }) {
            "Literal special tokens require confirmation"
        }
        val result = mutableListOf(Piece(ids.getValue("[CLS]"), 0, 0))
        val word = mutableListOf<UnitChar>()
        fun flush() {
            if (word.isEmpty()) return
            val found = mutableListOf<Piece>()
            var start = 0
            while (start < word.size && word.size <= 100) {
                var end = word.size
                var token: Long? = null
                while (end > start) {
                    val value = (if (start > 0) "##" else "") + word.subList(start, end).joinToString("") { it.value }
                    token = ids[value]
                    if (token != null) break
                    end--
                }
                if (token == null) break
                found += Piece(token, word[start].position, word[end - 1].position + 1)
                start = end
            }
            if (start != word.size) result += Piece(ids.getValue("[UNK]"), word.first().position, word.last().position + 1)
            else result += found
            word.clear()
        }
        characters.forEachIndexed { position, char ->
            val control = char.length == 1 && (char[0].code == 0 || char[0].code == 0xfffd ||
                char[0].category in setOf(CharCategory.CONTROL, CharCategory.FORMAT) && char !in listOf("\t", "\n", "\r"))
            when {
                control -> Unit
                char in listOf(" ", "\t", "\n", "\r") || char.length == 1 && char[0].category == CharCategory.SPACE_SEPARATOR -> flush()
                else -> {
                    val normalized = config.normalization[char] ?: char
                    val parts = unicodeCharacters(normalized)
                    if (parts.isNotEmpty() && (cjk(char) || punctuation(char))) {
                        flush()
                        parts.forEach { word += UnitChar(it, position) }
                        flush()
                    } else parts.forEach { word += UnitChar(it, position) }
                }
            }
        }
        flush()
        result += Piece(ids.getValue("[SEP]"), 0, 0)
        require(result.size <= 96) { "Kivo token context exceeded; never truncate" }
        return result
    }

    fun encode(text: String, preferredKind: String): QuickAddModelInput {
        require(preferredKind in listOf("TASK", "HABIT"))
        val pieces = pieces(text)
        return QuickAddModelInput(listOf(
            QuickAddTensor("input_ids", longArrayOf(1, 96), QuickAddTensorType.INT32,
                integers = LongArray(96) { pieces.getOrNull(it)?.id ?: ids.getValue("[PAD]") }),
            QuickAddTensor("attention_mask", longArrayOf(1, 96), QuickAddTensorType.INT32,
                integers = LongArray(96) { if (it < pieces.size) 1 else 0 }),
            QuickAddTensor("preferred_kind", longArrayOf(1), QuickAddTensorType.INT32,
                integers = longArrayOf(if (preferredKind == "HABIT") 1 else 0)),
        ), listOf("intent_logits", "span_logits", "token_features"))
    }

    private fun punctuation(char: String): Boolean = char.length == 1 &&
        (char[0].code in 33..47 || char[0].code in 58..64 || char[0].code in 91..96 || char[0].code in 123..126 ||
            char[0].category in setOf(CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
                CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION,
                CharCategory.FINAL_QUOTE_PUNCTUATION, CharCategory.OTHER_PUNCTUATION))

    private fun cjk(char: String): Boolean {
        val code = if (char.length == 2) 0x10000 + ((char[0].code - 0xd800) shl 10) + char[1].code - 0xdc00 else char[0].code
        return code in 0x4e00..0x9fff || code in 0x3400..0x4dbf || code in 0x20000..0x2a6df ||
            code in 0x2a700..0x2b73f || code in 0x2b740..0x2b81f || code in 0x2b820..0x2ceaf ||
            code in 0xf900..0xfaff || code in 0x2f800..0x2fa1f
    }
}
