package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddParser
import com.deadliner.domain.quickadd.QuickAddProposal
import kotlinx.datetime.*

/** Only model-extracted TIME/REPEAT fields enter here; semantic titles are never parsed. */
internal object QuickAddTemporalNormalizer {
    private const val marker = "模型标题占位"

    /** Model-predicted fields only; execution days and goal window remain independent. */
    fun normalizeHabitFields(time: String, days: String, window: String, count: String,
        locale: String, now: Instant, zone: TimeZone): QuickAddProposal? {
        if (locale != "zh") return null
        val dayText = when (days.trim()) {
            "工作日" -> "每个工作日"
            "周末" -> "每个周末"
            "" -> "每天"
            else -> days.trim()
        }
        val base = normalize(time, dayText, "HABIT", locale, now, zone) ?: return null
        if (base.period != "DAILY" || base.targetCount != 1 || base.dueAt != null) return null
        val goal = when (window.trim()) {
            "", "每天", "每日", "每个执行日", "每计划日" -> "DAILY"
            "每周", "每星期", "每个星期" -> "WEEKLY"
            else -> return null
        }
        val countText = count.trim()
        val target = if (countText.isEmpty()) 1 else {
            if (!Regex("[0-9零一二三四五六七八九十两]+次").matches(countText)) return null
            val parsed = QuickAddParser().parse("每天 " + countText + " " + marker, "HABIT", now, zone)
            if (!parsed.ready || parsed.needsAI || parsed.title != marker || parsed.kind != "HABIT" ||
                parsed.period != "DAILY" || parsed.reminderTime != null) return null
            parsed.targetCount
        }
        return base.copy(period = goal, targetCount = target)
    }

    fun normalize(time: String, repeat: String, kind: String, locale: String, now: Instant, zone: TimeZone): QuickAddProposal? {
        val fields = when (locale) {
            "zh" -> ChineseQuickAddFields.normalize(time, repeat)
            "en" -> EnglishQuickAddFields.normalize(time, repeat, kind, now.toLocalDateTime(zone).date)
            else -> null
        } ?: return null
        val result = QuickAddParser().parse(listOf(fields.repeat, fields.time, marker).filter { it.isNotBlank() }.joinToString(" "), kind, now, zone)
        if (!result.ready || result.needsAI || result.title != marker || result.kind != kind) return null
        if (kind == "HABIT" && fields.dayOffset != 0 && result.reminderTime != "00:00") return null
        val due = result.dueAt?.let { value ->
            val dateTime = LocalDateTime.parse(value)
            LocalDateTime(dateTime.date.plus(DatePeriod(days = fields.dayOffset)), dateTime.time).toString()
        }
        val mask = if (kind == "HABIT" && fields.dayOffset == 1)
            ((result.weekdaysMask shl 1) and 127) or (result.weekdaysMask shr 6) else result.weekdaysMask
        return result.copy(dueAt = due, weekdaysMask = mask)
    }
}

internal data class QuickAddNormalizedFields(val time: String, val repeat: String, val dayOffset: Int = 0)

internal object ChineseQuickAddFields {
    private val aliases = listOf("轉鐘" to "转钟", "今晚" to "今天晚上", "明早" to "明天早上", "明晚" to "明天晚上",
        "後天" to "后天", "明兒" to "明儿", "今兒" to "今儿", "明儿" to "明天", "今儿" to "今天", "后儿" to "后天",
        "晌午" to "中午", "下晌" to "下午", "禮拜" to "星期", "礼拜" to "星期")
    private val midnight = Regex("(?:晚上|夜里|半夜)(?:12|十二)点(?:整|钟)?")
    private val endOfDay = Regex("(?<![0-9])24\\s*[:：]\\s*00(?![0-9])")
    private val before = Regex("(凌晨|早上|上午|中午|下午|晚上|傍晚)?差(一刻|[0-9一二三四五六七八九十两]+分)([0-9一二三四五六七八九十两]+)点")
    private val after = Regex("(凌晨|早上|上午|中午|下午|晚上|傍晚)?([0-9一二三四五六七八九十两]+)点差(一刻|[0-9一二三四五六七八九十两]+分)")

    fun normalize(rawTime: String, rawRepeat: String): QuickAddNormalizedFields? {
        var time = rawTime.trim()
        var repeat = rawRepeat.trim().replace("天天", "每天").replace("每个礼拜", "每周").replace("每个星期", "每周")
        for ((from, to) in aliases) { time = time.replace(from, to); repeat = repeat.replace(from, to) }
        if (Regex("转钟后|转钟以后|天亮|擦黑|一早|下班后|睡前|饭后").containsMatchIn(time)) return null
        var offset = 0
        if (time.contains("转钟")) { offset = 1; time = time.replace("转钟", "00:00") }
        if (endOfDay.containsMatchIn(time)) { if (offset != 0) return null; offset = 1; time = time.replace(endOfDay, "00:00") }
        if (midnight.containsMatchIn(time)) { if (offset != 0) return null; offset = 1; time = time.replace(midnight, "00:00") }
        fun subtract(part: String, hour: String, amount: String): String? {
            val h = number(hour) ?: return null
            val minutes = if (amount == "一刻") 15 else number(amount.removeSuffix("分")) ?: return null
            if (h !in 1..24 || minutes !in 1..59) return null
            val absoluteHour = when {
                part == "晚上" && h == 12 -> 24
                part in listOf("下午", "晚上", "傍晚", "中午") && h in 1..11 -> h + 12
                part == "凌晨" && h == 12 -> 0
                else -> h
            }
            if (absoluteHour < 1) return null
            return "${absoluteHour - 1}点${60 - minutes}分"
        }
        before.find(time)?.let { match ->
            val clock = subtract(match.groupValues[1], match.groupValues[3], match.groupValues[2]) ?: return null
            time = time.replaceRange(match.range, clock)
        }
        after.find(time)?.let { match ->
            val clock = subtract(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: return null
            time = time.replaceRange(match.range, clock)
        }
        time = time.replace("点钟", "点").replace("点整", "点").replace("零点", "0点")
        return QuickAddNormalizedFields(time, repeat, offset)
    }

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        if (value == "两") return 2
        if (value == "十") return 10
        val digits = "零一二三四五六七八九"
        if (value.length == 1) return digits.indexOf(value).takeIf { it >= 0 }
        val parts = value.split('十')
        if (parts.size == 2) return (if (parts[0].isEmpty()) 1 else number(parts[0]) ?: return null) * 10 +
            (if (parts[1].isEmpty()) 0 else number(parts[1]) ?: return null)
        return null
    }
}
