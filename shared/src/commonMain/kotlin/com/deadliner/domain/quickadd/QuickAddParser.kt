package com.deadliner.domain.quickadd

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Conservative, synchronous parser: ambiguous syntax is handed to the existing AI extractor. */
class QuickAddParser {
    fun parseJson(text: String, preferredKind: String, timezone: String): String = runCatching {
        parse(text, preferredKind, Clock.System.now(), TimeZone.of(timezone)).toJson()
    }.getOrElse {
        QuickAddProposal(kind = preferredKind, needsAI = text.isNotBlank(), message = "识别暂不可用，请使用 AI 或展开补充").toJson()
    }

    fun parse(
        text: String,
        preferredKind: String = "TASK",
        now: Instant = Clock.System.now(),
        timezone: TimeZone = TimeZone.currentSystemDefault(),
    ): QuickAddProposal {
        val input = text.trim()
        val today = now.toLocalDateTime(timezone).date
        val recurring = recurrence.find(input)
        val kind = if (recurring != null || habitCue.containsMatchIn(input)) "HABIT" else preferredKind.uppercase().takeIf { it == "HABIT" } ?: "TASK"
        if (input.isEmpty()) return QuickAddProposal(kind = kind)
        var title = input
        var mask = 127
        var period = "DAILY"
        var target = 1
        var due: String? = null
        var reminder: String? = null
        var unsupported = complex.containsMatchIn(input) || input.length > 180 ||
            (habitCue.containsMatchIn(input) && recurring == null)
        if (recurring != null) {
            val token = recurring.value.trim().lowercase()
            period = when {
                token == "每周" || token == "每星期" || token == "weekly" -> "WEEKLY"
                token == "每月" || token == "monthly" -> "MONTHLY"
                else -> "DAILY"
            }
            mask = when {
                token.contains("工作日") || token == "weekdays" -> 31
                token.contains("周末") || token == "weekends" -> 96
                token.startsWith("每周") || token.startsWith("每星期") -> {
                    val days = token.removePrefix("每星期").removePrefix("每周")
                    if (days.isEmpty()) 127 else days.fold(0) { bits, day -> bits or dayBit(day) }
                }
                else -> 127
            }
            title = title.replaceRange(recurring.range, " ")
        }
        if (kind == "HABIT") {
            count.find(title)?.let { match ->
                val parsed = number(match.groupValues[1])
                if (parsed == null || parsed !in 1..10000) unsupported = true else target = parsed
                title = title.replace(match.value, " ")
            }
        }
        val clock = time.find(title)
        var parsedTime: LocalTime? = null
        if (clock != null) {
            var hour = number(clock.groupValues[2]) ?: -1
            val minute = when (clock.groupValues[3]) {
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                "" -> 0
                else -> number(clock.groupValues[3].replace(clockWhitespace, "").removeSuffix("分")) ?: -1
            }
            val part = clock.groupValues[1]
            if (part in listOf("下午", "晚上", "傍晚") && hour in 1..11) hour += 12
            if (part == "中午" && hour in 1..10) hour += 12
            if (part == "凌晨" && hour == 12) hour = 0
            parsedTime = runCatching { LocalTime(hour, minute) }.getOrNull()
            if (parsedTime == null) unsupported = true
            title = title.replace(clock.value, " ")
        }
        if (kind == "HABIT") {
            reminder = parsedTime?.toString()
            if (dateToken.containsMatchIn(title)) unsupported = true
        } else {
            val dateMatch = dateToken.find(title)
            val date = if (dateMatch != null) resolveDate(dateMatch.value, today) else today
            if (dateMatch != null) {
                if (date == null) unsupported = true
                title = title.replace(dateMatch.value, " ")
            }
            if (date != null && (dateMatch != null || parsedTime != null)) {
                due = LocalDateTime(date, parsedTime ?: LocalTime(23, 59)).toString()
            }
        }
        // Residual scheduling words imply unhandled or incomplete syntax, never silently strip them.
        if (residual.containsMatchIn(title)) unsupported = true
        title = title.replace(Regex("^(?:请|帮我|我要|记得|提醒我|添加任务|添加习惯|创建任务|创建习惯)\\s*"), "")
            .replace(Regex("\\s+"), " ").trim(' ', '，', ',', '。')
        val ready = title.any(Char::isLetter) && !unsupported
        return QuickAddProposal(kind, title, due, period = period, targetCount = target,
            weekdaysMask = mask, reminderTime = reminder, needsAI = !ready,
            ready = ready, message = if (!ready) "需要 AI 识别，或展开后补充" else "")
    }

    private fun resolveDate(token: String, today: LocalDate): LocalDate? = runCatching {
        when (token.lowercase()) {
            "今天", "今日", "today" -> today
            "明天", "明日", "tomorrow" -> today.plus(DatePeriod(days = 1))
            "后天" -> today.plus(DatePeriod(days = 2))
            "大后天" -> today.plus(DatePeriod(days = 3))
            else -> {
                val weekday = weekdayDate.matchEntire(token)
                if (weekday != null) {
                    val day = weekday.groupValues[2].first().let { dayBit(it).countTrailingZeroBits() }
                    val difference = day - today.dayOfWeek.ordinal
                    val offset = when (weekday.groupValues[1]) {
                        "下" -> difference + 7
                        "本", "这" -> difference
                        else -> if (difference < 0) difference + 7 else difference
                    }
                    today.plus(DatePeriod(days = offset))
                } else if (token.contains('-')) LocalDate.parse(token)
                else {
                    val match = chineseDate.matchEntire(token) ?: error("unsupported date")
                    val year = match.groupValues[1].toIntOrNull() ?: today.year
                    val month = match.groupValues[2].toInt()
                    val day = match.groupValues[3].toInt()
                    val result = LocalDate(year, month, day)
                    if (match.groupValues[1].isEmpty() && result < today) LocalDate(year + 1, month, day) else result
                }
            }
        }
    }.getOrNull()

    private fun dayBit(day: Char): Int = when (day) {
        '一', '1' -> 1; '二', '2' -> 2; '三', '3' -> 4; '四', '4' -> 8
        '五', '5' -> 16; '六', '6' -> 32; '日', '天', '七', '7' -> 64; else -> 0
    }

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        if (value == "两") return 2
        val digits = "零一二三四五六七八九"
        if (value.length == 1) return digits.indexOf(value).takeIf { it >= 0 }
        val parts = value.split('十')
        if (parts.size == 2) {
            val tens = if (parts[0].isEmpty()) 1 else number(parts[0]) ?: return null
            val units = if (parts[1].isEmpty()) 0 else number(parts[1]) ?: return null
            return tens * 10 + units
        }
        return null
    }

    companion object {
        private val recurrence = Regex("每(?:个)?工作日|每(?:个)?周末|每(?:天|日)|每(?:周|星期)(?:[一二三四五六日天1-7](?![0-9一二三四五六七八九十两]*[点时:：次])[,，、 ]*)*|每月|\\b(?:daily|weekly|monthly|weekdays|weekends|every day)\\b", RegexOption.IGNORE_CASE)
        private val habitCue = Regex("每(?:隔|[两二三四五六七八九十0-9])|\\bevery ", RegexOption.IGNORE_CASE)
        private val count = Regex("([0-9零一二三四五六七八九十两]+)次")
        private const val clockGap = "[\\s\u00a0\u3000]*"
        private val clockWhitespace = Regex("[\\s\u00a0\u3000]+")
        private val time = Regex("(凌晨|早上|上午|中午|下午|晚上|傍晚)?" + clockGap +
            "([0-9一二三四五六七八九十两]{1,3})" + clockGap + "(?:点|时|:|：)" + clockGap +
            "(半|一刻|三刻|[0-9一二三四五六七八九十]{1,3}(?:" + clockGap + "分)?)?")
        private val weekdayDate = Regex("(下|本|这)?(?:周|星期)([一二三四五六日天1-7])")
        private val chineseDate = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]")
        private val dateToken = Regex("\\d{4}-\\d{2}-\\d{2}|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]|大后天|后天|明天|明日|今天|今日|(下|本|这)?(?:周|星期)[一二三四五六日天1-7]|\\b(?:today|tomorrow)\\b", RegexOption.IGNORE_CASE)
        private val complex = Regex("[？?；;\\n]|然后|以及|如果|或者|下下周|下个星期|下个礼拜|下班后|放学后|午饭后|晚饭后|睡前|起床后|每隔|每两|每[二三四五六七八九十0-9]+(?:天|周|月)|月底|月末|小时后|分钟后|下个月|下月|下周[末]|上午$|下午$|晚上$|\\b(?:every other|next month)\\b", RegexOption.IGNORE_CASE)
        private val residual = Regex("每|明天|后天|明早|今晚|下周|本周|下星期|[0-9一二三四五六七八九十两]+(?:天|小时|分钟)后|周[一二三四五六日天]|星期|月底|月末|早上|上午|下午|晚上|[0-9一二三四五六七八九十]点|\\b(?:next|tomorrow|am|pm)\\b", RegexOption.IGNORE_CASE)
    }
}
