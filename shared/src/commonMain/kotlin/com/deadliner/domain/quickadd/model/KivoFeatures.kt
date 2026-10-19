package com.deadliner.domain.quickadd.model

import kotlin.math.ln

internal object KivoFeatures {
    fun base(text: String, kind: String, preferredKind: String, selected: List<KivoSpan>,
        probabilities: List<Float>, hidden: FloatArray): FloatArray {
        val output = FloatArray(1515)
        hidden.copyInto(output, 0, 0, 128)
        var position = 128
        val metadata = mutableListOf<Float>()
        metadata += probabilities
        metadata += if (kind == "TASK") 1f else 0f
        metadata += if (kind == "HABIT") 1f else 0f
        metadata += if (preferredKind == "HABIT") 1f else 0f
        for (role in KivoDecoder.fields) {
            val spans = selected.filter { it.role == role }
            for (span in spans) {
                val tokens = span.tokenEnd - span.tokenStart + 1
                for (h in 0 until 128) {
                    var sum = 0f
                    for (token in span.tokenStart..span.tokenEnd) sum += hidden[token * 128 + h]
                    output[position + h] += sum / tokens
                }
                val edge = listOf(maxOf(0, span.tokenStart - 1), span.tokenStart, span.tokenEnd, minOf(95, span.tokenEnd + 1))
                edge.forEachIndexed { i, token ->
                    for (h in 0 until 16) output[position + 128 + i * 16 + h] += hidden[token * 128 + h]
                }
            }
            if (spans.isNotEmpty()) for (i in position until position + 192) output[i] /= spans.size
            position += 192
            metadata += if (spans.isEmpty()) 0f else 1f
            metadata += ((spans.maxOfOrNull { it.score } ?: 0f).toDouble() / 10).toFloat()
            metadata += (spans.sumOf { it.end - it.start }.toDouble() / 96).toFloat()
            metadata += ((spans.minOfOrNull { it.start } ?: 0).toDouble() / 96).toFloat()
            metadata += ((spans.maxOfOrNull { it.end } ?: 0).toDouble() / 96).toFloat()
        }
        metadata += (selected.sumOf { it.end - it.start }.toDouble() / maxOf(1, unicodeCharacters(text).size)).toFloat()
        metadata += (selected.sumOf { it.score.toDouble() } / 50).toFloat()
        require(metadata.size == 43 && position == 1472)
        metadata.toFloatArray().copyInto(output, position)
        return output
    }

    fun quality(draft: KivoDraft): FloatArray {
        val result = draft.feature.copyOf(1530)
        val proposal = draft.proposal
        val metadata = mutableListOf(if (draft.valid) 1f else 0f,
            if (proposal.period == "DAILY") 1f else 0f, if (proposal.period == "WEEKLY") 1f else 0f)
        for (i in 0..6) metadata += if (proposal.weekdaysMask and (1 shl i) != 0) 1f else 0f
        metadata += (ln(1.0 + maxOf(0, proposal.targetCount)) / 10).toFloat()
        metadata += if (proposal.dueAt != null) 1f else 0f
        metadata += if (proposal.reminderTime != null) 1f else 0f
        metadata += (draft.hardReasons.size.toDouble() / 10).toFloat()
        metadata += (draft.margin.toDouble() / 10).toFloat()
        metadata.toFloatArray().copyInto(result, 1515)
        return result
    }
}
