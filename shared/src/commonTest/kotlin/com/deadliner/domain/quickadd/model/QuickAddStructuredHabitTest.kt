package com.deadliner.domain.quickadd.model

import com.deadliner.domain.quickadd.QuickAddParser
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuickAddStructuredHabitTest {
    private val now = Instant.parse("2026-10-19T04:00:00Z")
    private val zone = TimeZone.of("Asia/Shanghai")
    private fun fields(days: String = "", window: String = "", count: String = "", time: String = "") =
        QuickAddTemporalNormalizer.normalizeHabitFields(time, days, window, count, "zh", now, zone)

    @Test fun weekdaysAndWeeklyTargetRemainIndependent() {
        val value = assertNotNull(fields("工作日", "每周", "三次", "早上8点"))
        assertEquals(31, value.weekdaysMask)
        assertEquals("WEEKLY", value.period)
        assertEquals(3, value.targetCount)
        assertEquals("08:00", value.reminderTime)
        assertNull(value.dueAt)
    }
    @Test fun fixedWeekdaysAreNotWeeklyCount() {
        val days = assertNotNull(fields("每周一三五"))
        val count = assertNotNull(fields(window = "每周", count = "三次"))
        assertEquals(21, days.weekdaysMask)
        assertEquals("DAILY", days.period)
        assertEquals(1, days.targetCount)
        assertEquals(127, count.weekdaysMask)
        assertEquals("WEEKLY", count.period)
        assertEquals(3, count.targetCount)
    }
    @Test fun weekdayDigitDoesNotMergeIntoAdjacentClock() {
        val parser = QuickAddParser()
        for ((text, due) in listOf("本周三11:30开会" to "2026-10-21T11:30",
            "下周六14:00开会" to "2026-10-31T14:00",
            "下星期六14:00开会" to "2026-10-31T14:00",
            "本周三十一点开会" to "2026-10-21T11:00")) {
            assertEquals(due, parser.parse(text, "TASK", now, zone).dueAt, text)
        }
    }
    @Test fun defaultsDoNotInventAReminder() {
        val value = assertNotNull(fields())
        assertEquals(127, value.weekdaysMask)
        assertEquals(1, value.targetCount)
        assertEquals("DAILY", value.period)
        assertNull(value.reminderTime)
    }
    @Test fun tenParsesForTaskHabitClockAndCount() {
        assertEquals("2026-10-20T10:00", QuickAddParser().parse("明天上午十点开会", "TASK", now, zone).dueAt)
        assertEquals("10:00", assertNotNull(fields(time = "上午十点")).reminderTime)
        assertEquals(10, assertNotNull(fields(count = "十次")).targetCount)
        assertEquals("09:50", QuickAddTemporalNormalizer.normalize("差十分十点", "", "TASK", "zh", now, zone)?.dueAt?.substringAfter('T'))
    }
    @Test fun unsupportedOrUnconsumedFieldsAreRejected() {
        assertNull(fields(window = "每月"))
        assertNull(fields(days = "每周"))
        assertNull(fields(days = "每隔两天"))
        assertNull(fields(count = "两杯"))
        assertNull(fields(count = "零次"))
        assertNull(fields(time = "明天早上7点"))
        assertNull(fields(time = "早上7点晚上8点"))
        assertNull(fields(time = "25点"))
        assertNull(fields(window = "每天然后"))
    }
}
