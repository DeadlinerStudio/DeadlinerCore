package com.deadliner.domain.quickadd

import com.deadliner.domain.ai.AiTaskProposal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickAddParserTest {
    private val parser = QuickAddParser()
    private val zone = TimeZone.of("Asia/Shanghai")
    private val now = Instant.parse("2026-12-31T15:00:00Z")
    private fun parse(text: String, kind: String = "TASK") = parser.parse(text, kind, now, zone)

    @Test fun tomorrowCrossesYearAndChineseAfternoonTime() {
        val result = parse("明天下午三点半交报告")
        assertTrue(result.ready)
        assertEquals("交报告", result.title)
        assertEquals("2027-01-01T15:30", result.dueAt)
    }
    @Test fun dateOnlyMeansEndOfDay() {
        assertEquals("2027-01-02T23:59", parse("后天交报告").dueAt)
        assertEquals("2027-01-02T23:59", parse("1月2日交报告").dueAt)
    }
    @Test fun spacedClocksPreserveHabitTitleAndReminder() {
        for (text in listOf("每天下午 3 点交报告书", "每天下午三 点交报告书", "每天下午\u30003\u3000点交报告书")) {
            val result = parse(text)
            assertTrue(result.ready, text)
            assertEquals("HABIT", result.kind, text)
            assertEquals("交报告书", result.title, text)
            assertEquals("15:00", result.reminderTime, text)
        }
        assertEquals("15:30", parse("每天下午 3 点 30 分交报告书").reminderTime)
        assertEquals("2027-01-01T18:30", parse("明天 18 : 30 交报告书").dueAt)
        assertFalse(parse("每天 25 点交报告书").ready)
        assertFalse(parse("每天 3 点 80 分交报告书").ready)
    }
    @Test fun timezoneDefinesToday() {
        val instant = Instant.parse("2026-12-31T18:00:00Z")
        assertEquals("2027-01-01T23:59", parser.parse("今天提交", "TASK", instant, zone).dueAt)
        assertEquals("2026-12-31T23:59", parser.parse("今天提交", "TASK", instant, TimeZone.UTC).dueAt)
    }
    @Test fun weekdaysAndReminderAreExtractedAsHabit() {
        val result = parse("每周一三五晚上8点跑步2次")
        assertTrue(result.ready)
        assertEquals("HABIT", result.kind)
        assertEquals("跑步", result.title)
        assertEquals(21, result.weekdaysMask)
        assertEquals(2, result.targetCount)
        assertEquals("20:00", result.reminderTime)
    }
    @Test fun dailyWeekdayWeekendAndWindow() {
        assertEquals(31, parse("每个工作日读书").weekdaysMask)
        assertEquals(96, parse("每周末跑步").weekdaysMask)
        assertEquals("WEEKLY", parse("每周跑步三次").period)
        assertEquals("MONTHLY", parse("每月整理房间2次").period)
        assertEquals("HABIT", parse("每天读书").kind)
        assertEquals("HABIT", parse("读书", "HABIT").kind)
    }
    @Test fun weeklyCountIsNotMistakenForMonday() {
        val result = parse("每周一次打扫房间")
        assertTrue(result.ready)
        assertEquals("WEEKLY", result.period)
        assertEquals("打扫房间", result.title)
        assertEquals(127, result.weekdaysMask)
        assertEquals(1, result.targetCount)
    }
    @Test fun weeklyClockDoesNotBecomeAWeekday() {
        val result = parse("每周13点跑步")
        assertTrue(result.ready)
        assertEquals("13:00", result.reminderTime)
        assertEquals("WEEKLY", result.period)
        assertEquals(127, result.weekdaysMask)
    }
    @Test fun emptyAndIncompleteInputsCannotSave() {
        listOf("", "  ", "明天", "每周一", "下午3点").forEach { assertFalse(parse(it).ready, it) }
    }
    @Test fun ambiguousAndInvalidSchedulingGoesToAI() {
        assertEquals("HABIT", parse("每隔两天跑步").kind)
        listOf("每隔两天跑步", "下个月读书", "明天25点提交报告", "明天提交然后后天检查", "2027-02-30提交报告", "明天下午交报告").forEach {
            val result = parse(it)
            assertFalse(result.ready, it)
            assertTrue(result.needsAI, it)
        }
    }
    @Test fun incompleteNonemptyInputOffersAIInsteadOfAnUnusableArrow() {
        listOf("明天", "每周一", "下午3点", "提醒我").forEach {
            val result = parse(it)
            assertFalse(result.ready, it)
            assertTrue(result.needsAI, it)
        }
        assertFalse(parse("").needsAI)
    }
    @Test fun unresolvedNaturalLanguageSchedulingMustNotBecomeAnOrdinaryTitle() {
        listOf("下班后去超市", "睡前读书", "下下周交报告", "午饭后开会").forEach {
            val result = parse(it)
            assertFalse(result.ready, it)
            assertTrue(result.needsAI, it)
        }
    }
    @Test fun numericOrSymbolOnlyTitlesRequireAI() {
        listOf("123", "１２３", "123 !!!", "✨", "明天123").forEach {
            val result = parse(it)
            assertFalse(result.ready, it)
            assertTrue(result.needsAI, it)
        }
        listOf("完成123", "Read chapter 123", "买牛奶").forEach {
            assertTrue(parse(it).ready, it)
            assertFalse(parse(it).needsAI, it)
        }
    }
    @Test fun simpleTitleNeedsNoNetworkAndHasNoInventedDeadline() {
        val result = parse("买牛奶")
        assertTrue(result.ready)
        assertFalse(result.needsAI)
        assertEquals(null, result.dueAt)
    }
    @Test fun aiInvalidDeadlineCannotBeConfirmed() {
        assertFalse(QuickAddProposal.fromTask(AiTaskProposal("报告", dueAt = "not-a-date")).ready)
        assertEquals("2027-01-01T15:30", QuickAddProposal.fromTask(AiTaskProposal("报告", dueAt = "2027-01-01 15:30")).dueAt)
    }
    @Test fun aiCannotSilentlyDropExtraTasks() {
        assertFalse(QuickAddAiResult.tasks(emptyList()).ready)
        assertFalse(QuickAddAiResult.tasks(listOf(AiTaskProposal("A"), AiTaskProposal("B"))).ready)
        assertEquals("A", QuickAddAiResult.tasks(listOf(AiTaskProposal("A"))).title)
    }
}
