package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddParser
import com.deadliner.domain.quickadd.QuickAddProposal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** Runtime availability and semantic rejection are separate decisions. */
class QuickAddRecognitionPolicy {
    fun pending(text: String, kind: String): QuickAddProposal = rejected(text, kind).copy(
        recognitionState = "PENDING", message = "正在识别…")

    fun rejected(text: String, kind: String): QuickAddProposal = QuickAddProposal(
        kind = kind, title = text.trim(), needsAI = text.isNotBlank(), source = "MODEL",
        recognitionState = "REJECTED", message = if (text.isBlank()) "" else "请补充明确的任务和时间，或使用 AI / 展开添加")

    fun decoded(result: QuickAddProposal?, text: String, kind: String): QuickAddProposal =
        result?.copy(source = "MODEL", recognitionState = if (result.ready && !result.needsAI) "ACCEPTED" else "REJECTED")
            ?: rejected(text, kind)

    fun fallback(text: String, kind: String, now: Instant = Clock.System.now(),
        timezone: TimeZone = TimeZone.currentSystemDefault()): QuickAddProposal {
        val rules = QuickAddParser().parse(text, kind, now, timezone)
        // Unsupported qualifiers must not become a title attached to an invented deadline.
        val risky = unsupported.containsMatchIn(text)
        return if (!risky) rules.copy(recognitionState = "UNAVAILABLE") else QuickAddProposal(
            kind = kind, title = text.trim(), needsAI = text.isNotBlank(), source = "LOCAL",
            recognitionState = "UNAVAILABLE", message = "模型不可用，此表达需要确认，请使用 AI 或展开添加")
    }

    fun fallbackJson(text: String, kind: String, timezone: String): String =
        fallback(text, kind, timezone = TimeZone.of(timezone)).toJson()

    private val unsupported = Regex(
        "不|别|取消|无需|不用|如果|假如|或者|然后|之后|以后|之前|转钟|轉鐘|明儿|明兒|今儿|晌午|下晌|差一刻|差十分|礼拜|禮拜|天天|今晚|明早|明晚|下班|睡前|天亮|擦黑|[《》‘’“”\\\"]|24[:：]00|\\b(?:not|no|never|cancel|stop|unless|if|after|before|or|then|midnight|noon|midday|tonight|quarter|half|am|pm|at|on|by|next|every|once|twice|daily|weekly|weekdays|weekends|monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|today|eod|cob)\\b",
        RegexOption.IGNORE_CASE)
}
