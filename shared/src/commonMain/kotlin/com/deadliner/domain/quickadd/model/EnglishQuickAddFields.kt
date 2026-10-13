package com.deadliner.domain.quickadd.model

import kotlinx.datetime.*

/** English field grammar after neural extraction. Ambiguous dates/clock periods fail closed. */
internal object EnglishQuickAddFields {
    private val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val dayChars = listOf("一", "二", "三", "四", "五", "六", "日")
    private val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    private val numericClock = Regex("([a-z]+|[0-9]{1,2})(?::([0-9]{2}))?\\s*(am|pm)?")
    private val relativeDate = Regex("^(the day after tomorrow|day after tomorrow|tomorrow|today|tonight)\\b\\s*")
    private val weekday = Regex("^(?:(next|this)\\s+)?(" + days.joinToString("|") + ")\\b\\s*")
    private val fractionalClock = Regex("(quarter|half|[a-z-]+|[0-9]{1,2}) (past|after|to|till) ([a-z]+|[0-9]{1,2})(?: (am|pm))?")

    fun normalize(rawTime: String, rawRepeat: String, kind: String, today: LocalDate): QuickAddNormalizedFields? {
        val repeat = normalizeRepeat(rawRepeat) ?: return null
        var time = rawTime.trim().lowercase().replace(Regex("\\s+"), " ").replace("a.m.", "am").replace("p.m.", "pm")
        if (time.isEmpty()) return QuickAddNormalizedFields("", repeat)
        if (Regex("\\b(eod|cob|sometime|after work|tomorrow night at midnight|midnight tomorrow)\\b").containsMatchIn(time)) return null
        time = time.removePrefix("on ").removePrefix("by ").removePrefix("at ")
        Regex("^(.+) (tonight|tomorrow|today)$").matchEntire(time)?.let { match ->
            time = "${match.groupValues[2]} at ${match.groupValues[1]}"
        }
        var date: LocalDate? = null
        var period = ""
        var endOfNight = false
        relativeDate.find(time)?.let { match ->
            val token = match.groupValues[1]
            date = today.plus(DatePeriod(days = when (token) { "tomorrow" -> 1; "day after tomorrow", "the day after tomorrow" -> 2; else -> 0 }))
            endOfNight = token == "tonight"
            time = time.removeRange(match.range).trim()
        }
        if (date == null) weekday.find(time)?.let { match ->
            val difference = days.indexOf(match.groupValues[2]) - today.dayOfWeek.ordinal
            val offset = when (match.groupValues[1]) { "next" -> difference + 7; "this" -> difference; else -> if (difference < 0) difference + 7 else difference }
            date = today.plus(DatePeriod(days = offset))
            time = time.removeRange(match.range).trim()
        }
        if (date == null) Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2})(?:\\s+|$)").find(time)?.let { match ->
            date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
            time = time.removeRange(match.range).trim()
        }
        for ((token, suffix) in listOf("morning" to "am", "afternoon" to "pm", "evening" to "pm")) {
            if (time.startsWith("$token ")) { period = suffix; time = time.removePrefix("$token "); break }
        }
        time = time.removePrefix("at ").trim()
        if (kind == "HABIT" && date != null) return null
        if (time.isEmpty()) return date?.let { QuickAddNormalizedFields(it.toString(), repeat) }
        time = time.removePrefix("a ")
        Regex("^half ([a-z]+|[0-9]{1,2})(?: (am|pm))?$").matchEntire(time)?.let { match -> time = "half past ${match.groupValues[1]} ${match.groupValues[2]}".trim() }
        var offset = 0
        val clock = when (time) {
            "noon", "midday" -> "12:00"
            "midnight" -> {
                if (kind != "HABIT" && !endOfNight) return null
                if (endOfNight) offset = 1
                "00:00"
            }
            "24:00" -> { offset = 1; "00:00" }
            else -> {
                val fractional = fractionalClock.matchEntire(time)
                if (fractional != null) {
                    val amount = when (fractional.groupValues[1]) { "quarter" -> 15; "half" -> 30; else -> number(fractional.groupValues[1]) ?: return null }
                    val hour = number(fractional.groupValues[3]) ?: return null
                    val suffix = fractional.groupValues[4].ifEmpty { period }
                    if (hour !in 1..12 || amount !in 1..59 || suffix.isEmpty()) return null
                    val absolute = hour % 12 + if (suffix == "pm") 12 else 0
                    val minuteOfDay = absolute * 60 + if (fractional.groupValues[2] in listOf("past", "after")) amount else -amount
                    if (minuteOfDay !in 0..1439) return null
                    LocalTime(minuteOfDay / 60, minuteOfDay % 60).toString()
                } else {
                    val match = numericClock.matchEntire(time) ?: return null
                    var hour = number(match.groupValues[1]) ?: return null
                    val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
                    val suffix = match.groupValues[3].ifEmpty { period }
                    if (period.isNotEmpty() && match.groupValues[3].isNotEmpty() && period != match.groupValues[3]) return null
                    if (minute !in 0..59) return null
                    if (suffix.isNotEmpty()) {
                        if (hour !in 1..12) return null
                        hour = hour % 12 + if (suffix == "pm") 12 else 0
                    } else if (match.groupValues[2].isEmpty() || hour !in 0..23) return null
                    if (endOfNight && hour < 12) return null // "tonight at 1am" needs a date decision.
                    LocalTime(hour, minute).toString()
                }
            }
        }
        if (endOfNight && offset == 0 && LocalTime.parse(clock).hour < 12) return null
        return QuickAddNormalizedFields(listOfNotNull(date?.toString(), clock).joinToString(" "), repeat, offset)
    }

    private fun number(value: String): Int? = value.toIntOrNull() ?: when (value) { "twenty" -> 20; "twenty-five" -> 25; else -> words.indexOf(value).takeIf { it >= 0 } }

    private fun normalizeRepeat(raw: String): String? {
        val token = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (token.isEmpty()) return ""
        when (token) {
            "daily", "every day", "each day" -> return "每天"
            "weekly", "every week", "each week" -> return "每周"
            "monthly", "every month" -> return "每月"
            "weekdays", "every weekday", "on weekdays" -> return "每个工作日"
            "weekends", "every weekend", "on weekends" -> return "每个周末"
        }
        val count = Regex("(once|twice|three times|[0-9]+ times) (a|per|every) (day|week|month)").matchEntire(token)
        if (count != null) {
            val n = when (count.groupValues[1]) { "once" -> 1; "twice" -> 2; "three times" -> 3; else -> count.groupValues[1].substringBefore(' ').toIntOrNull() ?: return null }
            if (n !in 1..10000) return null
            return when (count.groupValues[3]) { "day" -> "每天"; "week" -> "每周"; else -> "每月" } + "${n}次"
        }
        val dayList = token.removePrefix("every ").removePrefix("on ").split(Regex("\\s*(?:,|and)\\s*"))
        if (dayList.isNotEmpty() && dayList.all { it.removeSuffix("s") in days } && (token.startsWith("every ") || token.startsWith("on "))) {
            return "每周" + dayList.joinToString("") { dayChars[days.indexOf(it.removeSuffix("s"))] }
        }
        return null
    }
}
