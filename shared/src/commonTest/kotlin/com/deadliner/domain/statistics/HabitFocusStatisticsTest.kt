package com.deadliner.domain.statistics

import com.deadliner.domain.focus.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class HabitFocusStatisticsTest {
    private fun session(uid: String, kind: FocusTargetKind, start: String, seconds: Long) = FocusSession(
        uid, if (kind == FocusTargetKind.TASK) "same" else "", "Snapshot", FocusTimerMode.STOPWATCH,
        startedAt = start, endedAt = (Instant.parse(start) + seconds.seconds).toString(), durationSeconds = seconds,
        completionKind = FocusCompletionKind.FOCUS_ONLY, createdAt = start,
        target = FocusTargetRef(kind, "same"), habitOccurredOn = if (kind == FocusTargetKind.HABIT) start.take(10) else null)

    @Test fun mixedKindsRemainSeparateWithSameUidAndExactPeriodTotals() {
        val rows = listOf(session("task", FocusTargetKind.TASK, "2026-10-09T09:00:00Z", 600),
            session("habit", FocusTargetKind.HABIT, "2026-10-09T10:00:00Z", 900),
            session("last-month", FocusTargetKind.HABIT, "2026-09-20T10:00:00Z", 300),
            session("deleted", FocusTargetKind.HABIT, "2026-10-09T12:00:00Z", 1_200).copy(isDeleted = true),
            session("future", FocusTargetKind.HABIT, "2026-10-10T10:00:00Z", 1_800))
        val engine = FocusStatisticsEngine(rows, emptyList(), emptyList(), TimeZone.UTC)
        val overview = engine.overview(LocalDate.parse("2026-10-09"))
        assertEquals(FocusDurationBreakdown(600, 900), overview.today.durationByTarget)
        assertEquals(2, overview.today.targetCount)
        assertEquals(1, overview.today.taskCount)
        assertEquals(1, overview.today.habitCount)
        assertEquals(FocusDurationBreakdown(600, 1_200), overview.allTime.durationByTarget)
        assertEquals(FocusDurationBreakdown(0, 300), overview.lastMonth.current.summary.durationByTarget)
        assertEquals(2, overview.thisMonth.targets.size)
        assertEquals(1, overview.thisMonth.tasks.size)
        val habits = engine.snapshot(FocusStatisticsQuery(targetKind = FocusTargetKind.HABIT,
            range = FocusStatisticsDateRange("2026-10-09", "2026-10-10")))
        assertEquals(FocusDurationBreakdown(0, 900), habits.summary.durationByTarget)
        assertTrue(habits.tasks.isEmpty())
        val mismatched = engine.snapshot(FocusStatisticsQuery(taskUid = "same", targetKind = FocusTargetKind.HABIT))
        assertEquals(0, mismatched.summary.totalDurationSeconds)
    }

    @Test fun summariesMatchAllTrendPointsIncludingZeroWindows() {
        val overview = FocusStatisticsEngine(listOf(
            session("year", FocusTargetKind.HABIT, "2025-11-01T10:00:00Z", 120),
            session("month", FocusTargetKind.TASK, "2026-09-30T10:00:00Z", 240),
            session("today", FocusTargetKind.HABIT, "2026-10-09T10:00:00Z", 360)),
            emptyList(), emptyList(), TimeZone.UTC).overview(LocalDate.parse("2026-10-09"))
        listOf(overview.daily to overview.recentDaysSummary,
            overview.monthly to overview.recentMonthsSummary,
            overview.rollingWeekly to overview.recentWeeksSummary).forEach { (points, summary) ->
            val split = assertNotNull(assertNotNull(summary).durationByTarget)
            assertEquals(summary.totalDurationSeconds, points.sumOf { it.durationSeconds })
            assertEquals(split.taskDurationSeconds, points.sumOf { it.durationByTarget!!.taskDurationSeconds })
            assertEquals(split.habitDurationSeconds, points.sumOf { it.durationByTarget!!.habitDurationSeconds })
            points.forEach { assertEquals(it.durationSeconds, it.durationByTarget!!.totalDurationSeconds) }
            assertTrue(points.any { it.durationSeconds == 0L && it.durationByTarget == FocusDurationBreakdown() })
        }
    }

    @Test fun habitOnlyAcrossMidnightUsesLocalStartDayNotCheckInOrEndDate() {
        val overview = FocusStatisticsEngine(listOf(
            session("cross", FocusTargetKind.HABIT, "2026-10-09T15:50:00Z", 1_200),
            session("next", FocusTargetKind.HABIT, "2026-10-09T16:00:00Z", 600)),
            emptyList(), emptyList(), TimeZone.of("Asia/Shanghai")).overview(LocalDate.parse("2026-10-09"))
        assertEquals(FocusDurationBreakdown(0, 1_200), overview.today.durationByTarget)
        assertEquals(1_200, overview.daily.last().durationSeconds)
        assertEquals(0, overview.today.taskCount)
        assertEquals(1, overview.today.habitCount)
    }

    @Test fun legacyProjectionDoesNotInventBreakdownAndNewProjectionRoundTrips() {
        assertNull(Json.decodeFromString<FocusStatisticsSummary>("{\"totalDurationSeconds\":600}").durationByTarget)
        assertNull(Json.decodeFromString<FocusDurationPoint>(
            "{\"startDate\":\"2026-10-09\",\"endDateExclusive\":\"2026-10-10\",\"durationSeconds\":600}").durationByTarget)
        val overview = FocusStatisticsEngine(listOf(session("one", FocusTargetKind.HABIT,
            "2026-10-09T10:00:00Z", 600)), emptyList(), emptyList(), TimeZone.UTC).overview(LocalDate.parse("2026-10-09"))
        assertEquals(overview, Json.decodeFromString<FocusOverviewStatistics>(Json.encodeToString(overview)))
    }
}
