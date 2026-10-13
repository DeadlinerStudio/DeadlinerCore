package com.deadliner.domain.quickadd.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*

class QuickAddTemporalNormalizerTest {
    private val now = Instant.parse("2026-12-31T04:00:00Z")
    private val zone = TimeZone.of("Asia/Shanghai")
    private fun normalize(time: String, locale: String = "zh", repeat: String = "", kind: String = "TASK") =
        QuickAddTemporalNormalizer.normalize(time, repeat, kind, locale, now, zone)

    @Test fun turnOfClockAnd24HourNotationRollTheCalendar() {
        for (text in listOf("今晚转钟", "今天24:00", "今晚十二点", "今晚轉鐘"))
            assertEquals("2027-01-01T00:00", assertNotNull(normalize(text)).dueAt, text)
        assertEquals("2027-01-02T00:00", assertNotNull(normalize("明天转钟")).dueAt)
        assertNull(normalize("今天24:01"))
        assertNull(normalize("转钟后"))
    }
    @Test fun colloquialDaysAndSubtractiveMinutesPreserveDateAndPeriod() {
        assertEquals("2027-01-01T14:45", assertNotNull(normalize("明儿下午差一刻三点")).dueAt)
        assertEquals("2027-01-01T14:45", assertNotNull(normalize("明儿下午三点差一刻")).dueAt)
        assertEquals("2027-01-01T12:45", assertNotNull(normalize("明天下午差一刻一点")).dueAt)
        assertEquals("2027-01-01T11:45", assertNotNull(normalize("明天下午差一刻十二点")).dueAt)
        assertEquals("2027-01-01T15:00", assertNotNull(normalize("明儿下晌三点")).dueAt)
        assertEquals("2027-01-01T00:00", assertNotNull(normalize("by midnight tonight", "en")).dueAt)
        assertEquals("2027-01-01T12:00", assertNotNull(normalize("明儿晌午12点")).dueAt)
        for (time in listOf("晌午", "一早", "擦黑", "下班后")) assertNull(normalize(time), time)
    }
    @Test fun recurringTurnOfClockRotatesTheWeekdayMask() {
        val result = assertNotNull(normalize("转钟", repeat = "每周一", kind = "HABIT"))
        assertEquals("00:00", result.reminderTime)
        assertEquals(2, result.weekdaysMask)
        assertEquals(127, assertNotNull(normalize("转钟", repeat = "天天", kind = "HABIT")).weekdaysMask)
    }
    @Test fun englishAmPmNoonAndNightHaveDistinctBoundaries() {
        assertEquals("2027-01-01T00:00", assertNotNull(normalize("tomorrow at 12am", "en")).dueAt)
        assertEquals("2026-12-31T12:00", assertNotNull(normalize("today at 12pm", "en")).dueAt)
        assertEquals("2027-01-01T00:00", assertNotNull(normalize("tonight at midnight", "en")).dueAt)
        assertEquals("2027-01-01T00:00", assertNotNull(normalize("by midnight tonight", "en")).dueAt)
        assertEquals("2027-01-01T12:00", assertNotNull(normalize("tomorrow at noon", "en")).dueAt)
        assertEquals("2027-01-02T00:00", assertNotNull(normalize("tomorrow at 24:00", "en")).dueAt)
        for (text in listOf("tomorrow at midnight", "midnight tomorrow", "tonight at 1am", "tomorrow at 8", "tomorrow at 13pm", "tomorrow at 24:01", "COB tomorrow", "03/04 at noon"))
            assertNull(normalize(text, "en"), text)
    }
    @Test fun englishSpokenClockNumbersAndAmericanAfterNormalize() {
        assertEquals("2027-01-01T17:00", assertNotNull(normalize("tomorrow at five pm", "en")).dueAt)
        assertEquals("2027-01-01T16:15", assertNotNull(normalize("tomorrow at a quarter after four pm", "en")).dueAt)
        assertEquals("2027-01-01T16:10", assertNotNull(normalize("tomorrow at ten past four pm", "en")).dueAt)
        assertEquals("2027-01-01T07:30", assertNotNull(normalize("tomorrow morning at half seven", "en")).dueAt)
        assertNull(normalize("tomorrow at half seven", "en"))
    }
    @Test fun englishFractionalClocksAndHabitCountsNormalize() {
        assertEquals("2027-01-01T15:45", assertNotNull(normalize("tomorrow at quarter to four pm", "en")).dueAt)
        assertEquals("2027-01-01T07:30", assertNotNull(normalize("tomorrow morning at half past seven", "en")).dueAt)
        val result = assertNotNull(normalize("at 9am", "en", "every Monday and Wednesday", "HABIT"))
        assertEquals(5, result.weekdaysMask); assertEquals("09:00", result.reminderTime)
        val count = assertNotNull(normalize("at 8pm", "en", "three times a week", "HABIT"))
        assertEquals(3, count.targetCount); assertEquals("WEEKLY", count.period)
        assertNull(normalize("at 8pm", "en", "every other day", "HABIT"))
    }
}
