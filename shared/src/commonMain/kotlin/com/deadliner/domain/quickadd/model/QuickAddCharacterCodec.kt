package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddProposal
import com.deadliner.domain.quickadd.inference.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.math.exp

/** v1: character IDs + mask, TASK/HABIT/OTHER intent, O/B/I TITLE/TIME/REPEAT/NOTE slots. */
class QuickAddCharacterCodec(override val modelId: String, private val config: QuickAddCharacterConfig) : QuickAddSemanticCodec {
    init { config.validate() }
    private val vocabulary = config.vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val wordPiece = config.wordPiece?.let { QuickAddWordPiece(it, config.maxLength) }

    private fun tokens(text: String): List<String> {
        val tokens = unicodeCharacters(text)
        require(tokens.isNotEmpty() && tokens.size <= config.maxLength) { "Text exceeds model context" }
        require(tokens.count { it !in vocabulary }.toDouble() / tokens.size <= config.maxUnknownRatio) { "Too many unknown characters" }
        return tokens
    }

    override fun encode(text: String, preferredKind: String): QuickAddModelInput {
        val tokens = tokens(text)
        wordPiece?.let { return it.encode(text) }
        val ids = LongArray(config.maxLength) { index -> tokens.getOrNull(index)?.let { vocabulary[it] ?: 1L } ?: 0L }
        val mask = LongArray(config.maxLength) { if (it < tokens.size) 1L else 0L }
        val shape = longArrayOf(1, config.maxLength.toLong())
        return QuickAddModelInput(listOf(
            QuickAddTensor("input_ids", shape, QuickAddTensorType.INT32, integers = ids),
            QuickAddTensor("attention_mask", shape, QuickAddTensorType.INT32, integers = mask),
        ), listOf("intent_logits", "slot_logits"))
    }

    override fun decode(output: QuickAddModelOutput, text: String, preferredKind: String, now: Instant, timezone: TimeZone): QuickAddProposal? {
        return decodeCharacters(wordPiece?.project(output, text) ?: output, text, preferredKind, now, timezone)
    }

    private fun decodeCharacters(output: QuickAddModelOutput, text: String, preferredKind: String, now: Instant, timezone: TimeZone): QuickAddProposal? {
        val tokens = tokens(text)
        if (output.tensors.map { it.name } != listOf("intent_logits", "slot_logits")) return null
        val intent = output.tensors[0]
        val slots = output.tensors[1]
        if (!intent.shape.contentEquals(longArrayOf(1, 3)) || intent.values.size != 3 ||
            !slots.shape.contentEquals(longArrayOf(1, config.maxLength.toLong(), 9)) || slots.values.size != config.maxLength * 9 ||
            output.tensors.any { tensor -> tensor.values.any { !it.isFinite() } }) return null
        val intentProbabilities = probabilities(intent.values, 0, 3)
        val order = intentProbabilities.indices.sortedByDescending { intentProbabilities[it] }
        val winner = order[0]
        if (intentProbabilities[winner] < config.intentThreshold ||
            intentProbabilities[winner] - intentProbabilities[order[1]] < config.intentMargin) return null
        if (winner == 2) return QuickAddProposal(kind = preferredKind, title = text.trim(), needsAI = true,
            source = "MODEL", message = "未识别为明确的添加请求，请确认或展开")

        val spans = List(4) { mutableListOf<String>() }
        var previous = -1
        val unassigned = mutableListOf<String>()
        var gap = ""
        for (index in tokens.indices) {
            val probabilities = probabilities(slots.values, index * 9, 9)
            val label = probabilities.indices.maxBy { probabilities[it] }
            if (probabilities[label] < config.slotThreshold) return null
            if (label == 0) { previous = -1; gap += tokens[index]; continue }
            if (gap.isNotEmpty()) { unassigned += gap; gap = "" }
            val group = (label - 1) / 2
            val beginning = label % 2 == 1
            if (beginning) spans[group] += tokens[index]
            else {
                if (previous != group || spans[group].isEmpty()) return null // Reject malformed BIO.
                spans[group][spans[group].lastIndex] += tokens[index]
            }
            previous = group
        }
        if (gap.isNotEmpty()) unassigned += gap
        if (unassigned.any { !neutralGap(it) }) return null // Never silently discard a qualifier such as 后/after/not.
        if (spans[0].size != 1 || spans[1].size > 2 || spans[2].size > 1 || spans[3].size > 1) return null // One proposal; a date and clock may be separated by the title.
        val rawTitle = spans[0].single().trim()
        // A separator comma at the edge of a scheduled title is presentation, not task content.
        // Internal commas and commas inside quotations remain intact.
        val title = if (spans[1].isNotEmpty() || spans[2].isNotEmpty()) rawTitle.trim(',', '，').trim() else rawTitle
        if (title.isEmpty()) return null
        val time = spans[1].joinToString(" ")
        val repeat = spans[2].singleOrNull().orEmpty()
        val kind = if (winner == 0) "TASK" else "HABIT"
        if (preferredKind == "HABIT" && kind == "TASK") return null
        if ((kind == "HABIT" && repeat.isBlank()) || (kind == "TASK" && repeat.isNotBlank())) return null
        // Normalize only extracted fields; never reparse the semantic title as a date/schedule.
        val normalized = QuickAddTemporalNormalizer.normalize(time, repeat, kind, config.locale, now, timezone) ?: return null
        return normalized.copy(title = title, note = spans[3].singleOrNull().orEmpty().trim(), source = "MODEL")
    }

    private fun neutralGap(raw: String): Boolean {
        val text = raw.trim().trim(',', '，', ':', '：', '.', '。', '!', '！').trim()
        if (text.isEmpty()) return true
        return when (config.locale) {
            "zh" -> Regex("(?:请|帮我|我要|记得|提醒我|添加任务|添加习惯|创建任务|创建习惯|记录|养成|的习惯|截止到|截止于|备注|安排到|安排在|定在|在|于|把|给我)+").matches(text.replace(Regex("\\s+"), ""))
            "en" -> Regex("(?:(?:please|remind me to|remember to|add|create|a task to|a habit to|at|on|by|can you|could you|to)\\s*)+").matches(text.lowercase().replace(Regex("\\s+"), " "))
            else -> false
        }
    }

    private fun probabilities(values: FloatArray, start: Int, count: Int): DoubleArray {
        val maximum = (start until start + count).maxOf { values[it].toDouble() }
        val result = DoubleArray(count) { exp(values[start + it].toDouble() - maximum) }
        val sum = result.sum()
        return DoubleArray(count) { result[it] / sum }
    }
}
