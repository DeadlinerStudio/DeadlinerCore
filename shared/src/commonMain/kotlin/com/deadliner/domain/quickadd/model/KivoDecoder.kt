package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddProposal
import com.deadliner.domain.quickadd.inference.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.math.exp

internal data class KivoSpan(val role: String, val start: Int, val end: Int,
    val tokenStart: Int, val tokenEnd: Int, val score: Float)

internal data class KivoDraft(val proposal: QuickAddProposal, val spans: List<KivoSpan>,
    val hardReasons: List<String>, val feature: FloatArray, val score: Double,
    val valid: Boolean, val margin: Float = 0f, val canConfirm: Boolean = valid) {
    fun qualityFeature(): FloatArray = KivoFeatures.quality(this)
}

/** Finite beam search and actual Core business normalization; no title dictionaries. */
internal class KivoDecoder(private val tokenizer: KivoTokenizer) {
    companion object {
        val roles = listOf("TITLE", "TIME", "LOCATION", "NOTE", "CONNECTOR", "DAYS", "GOAL_WINDOW", "COUNT")
        val fields = listOf("TITLE", "TIME", "LOCATION", "NOTE", "DAYS", "GOAL_WINDOW", "COUNT")
        private val kinds = listOf("TASK", "HABIT", "CONFIRM")
    }
    private data class Beam(val score: Double, val spans: List<KivoSpan>)

    fun select(output: QuickAddModelOutput, text: String, preferredKind: String,
        now: Instant, zone: TimeZone): KivoDraft {
        require(output.tensors.map { it.name } == listOf("intent_logits", "span_logits", "token_features"))
        val shapes = listOf(longArrayOf(1, 3), longArrayOf(1, 8, 96, 96), longArrayOf(1, 96, 128))
        output.tensors.zip(shapes).forEach { (tensor, shape) ->
            require(tensor.shape.contentEquals(shape) && tensor.values.size == shape.fold(1L) { a, b -> a * b }.toInt()
                && tensor.values.all { it.isFinite() })
        }
        val intent = output.tensors[0].values
        val maximum = intent.maxOrNull()!!.toDouble()
        val weights = intent.map { exp(it.toDouble() - maximum) }
        val probabilities = weights.map { (it / weights.sum()).toFloat() }
        val kind = kinds[intent.indices.maxBy { intent[it] }]
        val hidden = output.tensors[2].values
        if (kind == "CONFIRM") return draft(text, preferredKind, preferredKind, emptyList(), probabilities,
            hidden, now, zone, listOf("request-needs-confirmation"))
        val pieces = tokenizer.pieces(text)
        val all = mutableListOf<KivoSpan>()
        for ((roleIndex, role) in roles.withIndex()) {
            val pool = mutableListOf<KivoSpan>()
            for (a in pieces.indices) for (b in a until minOf(pieces.size, a + 64)) {
                if (pieces[a].end <= pieces[a].start || pieces[b].end <= pieces[b].start) continue
                val score = output.tensors[1].values[(roleIndex * 96 + a) * 96 + b]
                if (score >= -2f) pool += KivoSpan(role, pieces[a].start, pieces[b].end, a, b, score)
            }
            all += pool.sortedByDescending { it.score }.take(12)
        }
        var beam = listOf(Beam(0.0, emptyList()))
        for (role in if (kind == "HABIT") fields else fields.take(4)) {
            val pool = all.filter { it.role == role }.sortedByDescending { it.score }.take(4)
            val options = mutableListOf<List<KivoSpan>>()
            if (role != "TITLE") options += emptyList<KivoSpan>()
            options += pool.map { listOf(it) }
            if (role == "TIME") for (a in pool.indices) for (b in a + 1 until pool.size)
                if (!overlaps(pool[a], pool[b])) options += listOf(pool[a], pool[b]).sortedBy { it.start }
            val next = mutableListOf<Beam>()
            for (value in beam) for (option in options) {
                if (option.any { a -> value.spans.any { b -> overlaps(a, b) } }) continue
                next += Beam(value.score + option.sumOf { it.score.toDouble() }, value.spans + option)
            }
            beam = next.sortedByDescending { it.score }.take(32)
            if (beam.isEmpty()) break
        }
        val drafts = (beam.map { draft(text, kind, preferredKind, it.spans, probabilities, hidden, now, zone) }
            .ifEmpty { listOf(draft(text, kind, preferredKind, emptyList(), probabilities, hidden, now, zone)) })
            .sortedByDescending { it.score.toFloat() }
        val margin = if (drafts.size > 1) drafts[0].score.toFloat() - drafts[1].score.toFloat() else 0f
        return drafts[0].copy(margin = margin)
    }

    private fun draft(text: String, kind: String, preferredKind: String, inputSpans: List<KivoSpan>,
        probabilities: List<Float>, hidden: FloatArray, now: Instant, zone: TimeZone,
        extra: List<String> = emptyList()): KivoDraft {
        val spans = inputSpans.sortedBy { it.start }
        val chars = unicodeCharacters(text)
        fun values(role: String) = spans.filter { it.role == role }.map { chars.subList(it.start, it.end).joinToString("") }
        val title = values("TITLE")
        val time = values("TIME")
        val days = values("DAYS")
        val window = values("GOAL_WINDOW")
        val count = values("COUNT")
        val errors = (KivoCoverage.reasons(text, spans, kind) + extra).toMutableList()
        if (title.size != 1) errors += "event-not-established"
        if (kind == "TASK" && time.isEmpty()) errors += "task-time-missing"
        if (kind == "TASK" && preferredKind == "HABIT") errors += "explicit-habit-entry-conflict"
        if (kind == "HABIT" && days.isEmpty() && window.isEmpty() && preferredKind != "HABIT")
            errors += "habit-schedule-not-established"
        var note = values("LOCATION").firstOrNull()?.let { "地点：$it" }.orEmpty()
        val noteField = values("NOTE").firstOrNull().orEmpty()
        if (noteField.isNotEmpty()) note += (if (note.isNotEmpty()) "；" else "") + noteField
        var proposal = QuickAddProposal(kind = kind, title = title.firstOrNull() ?: text.trim(), note = note,
            needsAI = true, source = "MODEL")
        val normalized = if (kind == "HABIT") QuickAddTemporalNormalizer.normalizeHabitFields(
            time.joinToString(" "), days.joinToString(""), window.joinToString(""), count.joinToString(""), "zh", now, zone)
            else if (days.isEmpty() && window.isEmpty() && count.isEmpty())
                QuickAddTemporalNormalizer.normalize(time.joinToString(" "), "", "TASK", "zh", now, zone) else null
        if (normalized == null || normalized.title != "模型标题占位" || !normalized.ready || normalized.needsAI)
            errors += "fields-not-normalized"
        else proposal = proposal.copy(dueAt = normalized.dueAt, period = normalized.period,
            targetCount = normalized.targetCount, weekdaysMask = normalized.weekdaysMask, reminderTime = normalized.reminderTime)
        val reasons = errors.distinct()
        return KivoDraft(proposal, spans, reasons,
            KivoFeatures.base(text, kind, preferredKind, spans, probabilities, hidden),
            spans.sumOf { it.score.toDouble() }, reasons.isEmpty(),
            canConfirm = reasons.all { it == "source-content-deleted" })
    }

    fun gate(draft: KivoDraft, logit: Float?, calibration: KivoCalibration, threshold: Double?): QuickAddProposal {
        val probability = if (logit != null && logit.isFinite())
            1.0 / (1.0 + exp(-(calibration.slope * logit + calibration.bias).coerceIn(-700.0, 700.0))) else null
        val trusted = draft.valid && probability != null && probability.isFinite() && threshold != null && probability >= threshold
        return draft.proposal.copy(ready = draft.canConfirm, needsAI = !trusted,
            recognitionState = if (trusted) "ACCEPTED" else if (draft.canConfirm) "READY_WITH_AI" else "KIVO_PARTIAL",
            message = if (trusted) "" else if (draft.canConfirm) "可确认添加，也可使用 AI 完善" else "请确认原文，或使用 AI / 展开添加")
    }

    private fun overlaps(a: KivoSpan, b: KivoSpan) = a.start < b.end && b.start < a.end
}
