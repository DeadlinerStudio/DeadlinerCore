package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.inference.*
import kotlin.math.exp
import kotlin.math.ln

/** Basic/WordPiece offsets are Unicode character indices, including supplementary CJK. */
internal class QuickAddWordPiece(private val config: QuickAddWordPieceConfig, private val length: Int) {
    private val vocabulary = config.vocabulary.withIndex().associate { it.value to it.index.toLong() }
    internal data class Piece(val id: Long, val start: Int, val end: Int)

    internal fun pieces(text: String): List<Piece> {
        val chars = unicodeCharacters(text)
        val result = mutableListOf(Piece(vocabulary.getValue("[CLS]"), 0, 0))
        var index = 0
        while (index < chars.size) {
            val char = chars[index]
            if (char.isBlank()) { index++; continue }
            require(!control(char) && char != "\uFFFD") { "Unsupported normalization character" }
            val start = index
            if (cjk(char) || punctuation(char)) index++
            else {
                index++
                while (index < chars.size && !chars[index].isBlank() && !control(chars[index]) &&
                    !cjk(chars[index]) && !punctuation(chars[index])) index++
            }
            val normalized = (start until index).map { normalize(chars[it]) }
            if (normalized.size > 100) {
                result += Piece(vocabulary.getValue("[UNK]"), start, index)
                continue
            }
            val segment = mutableListOf<Piece>()
            var position = 0
            while (position < normalized.size) {
                var end = normalized.size
                var id: Long? = null
                while (position < end) {
                    val token = (if (position > 0) "##" else "") + normalized.subList(position, end).joinToString("")
                    id = vocabulary[token]
                    if (id != null) break
                    end--
                }
                if (id == null) { segment.clear(); segment += Piece(vocabulary.getValue("[UNK]"), start, index); break }
                segment += Piece(id, start + position, start + end)
                position = end
            }
            result += segment
        }
        result += Piece(vocabulary.getValue("[SEP]"), 0, 0)
        require(result.size <= length) { "Text exceeds WordPiece context; never truncate a task" }
        return result
    }

    fun encode(text: String): QuickAddModelInput {
        val pieces = pieces(text)
        val shape = longArrayOf(1, length.toLong())
        return QuickAddModelInput(listOf(
            QuickAddTensor("input_ids", shape, QuickAddTensorType.INT32,
                integers = LongArray(length) { pieces.getOrNull(it)?.id ?: vocabulary.getValue("[PAD]") }),
            QuickAddTensor("attention_mask", shape, QuickAddTensorType.INT32,
                integers = LongArray(length) { if (it < pieces.size) 1 else 0 }),
        ), listOf("intent_logits", "slot_logits"))
    }

    fun project(output: QuickAddModelOutput, text: String): QuickAddModelOutput {
        require(output.tensors.map { it.name } == listOf("intent_logits", "slot_logits"))
        val tensor = output.tensors[1]
        require(tensor.shape.contentEquals(longArrayOf(1, length.toLong(), 9)) && tensor.values.size == length * 9)
        require(output.tensors.all { it.values.all(Float::isFinite) })
        val chars = unicodeCharacters(text)
        val values = FloatArray(length * 9) { if (it % 9 == 0) 0f else -30f }
        fun copySlot(target: Int, source: FloatArray, offset: Int, inside: Boolean) {
            repeat(9) { values[target * 9 + it] = source[offset + it] }
            if (inside) for (begin in listOf(1, 3, 5, 7)) {
                val a = source[offset + begin]; val b = source[offset + begin + 1]
                val maximum = maxOf(a, b)
                values[target * 9 + begin + 1] = maximum + ln(exp(a - maximum) + exp(b - maximum))
                values[target * 9 + begin] = -30f
            }
        }
        pieces(text).forEachIndexed { index, piece ->
            for (position in piece.start until piece.end) copySlot(position, tensor.values, index * 9, position > piece.start)
        }
        fun label(position: Int) = (0..8).maxBy { values[position * 9 + it] }
        for (index in chars.indices) if (chars[index].isBlank() && index > 0 && index < chars.lastIndex) {
            val left = label(index - 1); val right = label(index + 1)
            if (left > 0 && right > 0 && (left - 1) / 2 == (right - 1) / 2) {
                val source = values.copyOfRange((index - 1) * 9, index * 9)
                copySlot(index, source, 0, true)
            }
        }
        return QuickAddModelOutput(listOf(output.tensors[0], QuickAddOutputTensor("slot_logits", tensor.shape, values)))
    }

    private fun punctuation(text: String): Boolean = text.length == 1 &&
        (text[0].code in 33..47 || text[0].code in 58..64 || text[0].code in 91..96 || text[0].code in 123..126 ||
            text[0].category in setOf(CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
                CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION,
                CharCategory.FINAL_QUOTE_PUNCTUATION, CharCategory.OTHER_PUNCTUATION))
    private fun control(text: String) = text.length == 1 && text[0].category in setOf(CharCategory.CONTROL, CharCategory.FORMAT)
    private fun cjk(text: String): Boolean {
        val code = if (text.length == 2) 0x10000 + ((text[0].code - 0xd800) shl 10) + text[1].code - 0xdc00 else text[0].code
        return code in 0x4e00..0x9fff || code in 0x3400..0x4dbf || code in 0x20000..0x2a6df ||
            code in 0x2a700..0x2b73f || code in 0x2b740..0x2b81f || code in 0x2b820..0x2ceaf ||
            code in 0xf900..0xfaff || code in 0x2f800..0x2fa1f
    }
    private fun normalize(text: String): String {
        if (!config.lowercase) return text
        config.normalizationOverrides[text]?.let { return it }
        require(text.none { it.code in 0xac00..0xd7a3 }) { "Unsupported normalization expansion" }
        if (text == "İ") return "i"
        val lower = text.lowercase()
        require(unicodeCharacters(lower).size == 1 && text.none { it.category == CharCategory.NON_SPACING_MARK }) {
            "Unsupported normalization expansion"
        }
        val accents = mapOf("àáâãäå" to "a", "èéêë" to "e", "ìíîïİ" to "i", "òóôõö" to "o", "ùúûü" to "u", "ç" to "c", "ñ" to "n", "ýÿ" to "y")
        return accents.entries.firstOrNull { text.length == 1 && text.lowercase() in unicodeCharacters(it.key) }?.value ?: lower
    }
}
