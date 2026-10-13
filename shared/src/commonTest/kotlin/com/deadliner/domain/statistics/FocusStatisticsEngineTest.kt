package com.deadliner.domain.statistics

import com.deadliner.domain.category.Category
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FocusStatisticsEngineTest {
    @Test
    fun usesLocalStartDayAndKeepsHistoryAfterTaskDeletion() {
        val snapshot = engine(
            listOf(
                session("one", "a", "2026-10-01T15:55:00Z", 1_800),
                session("two", "b", "2026-10-01T16:00:00Z", 600),
                session("deleted", "a", "2026-10-01T15:00:00Z", 300).copy(isDeleted = true),
                session("outside", "a", "2026-10-02T16:00:00Z", 300),
            ),
            tasks = listOf(task("a", state = TaskState.ARCHIVED)),
            timeZone = TimeZone.of("Asia/Shanghai"),
        ).snapshot(FocusStatisticsQuery(FocusStatisticsDateRange("2026-10-01", "2026-10-03")))
        assertEquals(2_400, snapshot.summary.totalDurationSeconds)
        assertEquals(2, snapshot.summary.taskCount)
        assertEquals(listOf(1_800L, 600L), snapshot.daily.map { it.durationSeconds })
        assertFalse(snapshot.tasks.single { it.taskUid == "b" }.isTaskAvailable)
        assertEquals("Snapshot b", snapshot.tasks.single { it.taskUid == "b" }.taskTitle)
        assertEquals(snapshot.summary.totalDurationSeconds, snapshot.categories.sumOf { it.durationSeconds })
    }

    @Test
    fun usesCurrentMetadataAndAppliesTaskAndCategoryFilters() {
        val engine = engine(
            listOf(session("a1", "a", "2026-10-02T09:00:00Z", 600),
                session("a2", "a", "2026-10-03T09:00:00Z", 300),
                session("b1", "b", "2026-10-03T10:00:00Z", 1_200)),
            tasks = listOf(task("a", title = "Renamed", categoryUid = "work"), task("b")),
            categories = listOf(category("work", "Work")),
        )
        val work = engine.snapshot(FocusStatisticsQuery(categoryUid = "work"))
        assertEquals(900, work.summary.totalDurationSeconds)
        assertEquals("Renamed", work.tasks.single().taskTitle)
        assertEquals("Work", work.categories.single().categoryTitle)
        assertEquals(450, work.summary.averageSessionDurationSeconds)
        val task = engine.snapshot(FocusStatisticsQuery(taskUid = "b"))
        assertEquals(1_200, task.summary.totalDurationSeconds)
        assertNull(task.categories.single().categoryUid)
        assertTrue(task.daily.isEmpty())
    }

    @Test
    fun calendarPeriodsCrossYearAndMonthlyComparisonUsesWholeMonths() {
        val statistics = engine(listOf(
            session("nov", "a", "2025-11-10T09:00:00Z", 600),
            session("dec", "a", "2025-12-31T09:00:00Z", 1_200),
            session("jan", "a", "2026-01-01T09:00:00Z", 300),
            session("future", "a", "2026-02-01T09:00:00Z", 1_800),
        )).overview(LocalDate.parse("2026-01-01"))
        assertEquals(300, statistics.today.totalDurationSeconds)
        assertEquals(1_500, statistics.thisWeek.totalDurationSeconds)
        assertEquals(2_100, statistics.allTime.totalDurationSeconds)
        assertEquals(4, statistics.weekly.size)
        assertEquals("2025-12-29", statistics.weekly.last().startDate)
        assertEquals(12, statistics.monthly.size)
        assertEquals("2025-02-01", statistics.monthly.first().startDate)
        assertEquals(31, statistics.lastMonth.current.daily.size)
        assertEquals(1_200, statistics.lastMonth.current.summary.totalDurationSeconds)
        assertEquals(600, statistics.lastMonth.durationChangeSeconds)
        assertEquals(100.0, statistics.lastMonth.durationChangePercent)
        assertEquals(7, statistics.daily.size)
    }

    @Test
    fun emptyMonthHasZeroPointsAndNoInventedGrowthRate() {
        val statistics = engine(emptyList()).overview(LocalDate.parse("2026-03-15"))
        assertEquals(28, statistics.lastMonth.current.daily.size)
        assertTrue(statistics.lastMonth.current.daily.all { it.durationSeconds == 0L })
        assertEquals(0, statistics.lastMonth.durationChangeSeconds)
        assertNull(statistics.lastMonth.durationChangePercent)
        assertTrue(statistics.thisMonth.tasks.isEmpty())
        assertEquals(0, statistics.allTime.averageSessionDurationSeconds)
        assertEquals(statistics, Json.decodeFromString<FocusOverviewStatistics>(Json.encodeToString(statistics)))
    }

    @Test
    fun daylightSavingBoundaryUsesLocalCalendarDate() {
        val snapshot = engine(listOf(
            session("before", "a", "2026-03-08T04:59:00Z", 60),
            session("start", "a", "2026-03-08T05:00:00Z", 60),
            session("after", "a", "2026-03-09T04:00:00Z", 60),
        ), timeZone = TimeZone.of("America/New_York"))
            .snapshot(FocusStatisticsQuery(FocusStatisticsDateRange("2026-03-08", "2026-03-09")))
        assertEquals(60, snapshot.summary.totalDurationSeconds)
        assertEquals(1, snapshot.summary.sessionCount)
    }

    @Test
    fun rollingWeeksAlignWithTaskTrendsWithoutChangingNaturalWeekSummary() {
        val statistics = engine(listOf(
            session("first", "a", "2026-09-12T09:00:00Z", 60),
            session("edge", "a", "2026-10-02T09:00:00Z", 120),
            session("last", "a", "2026-10-03T09:00:00Z", 180),
            session("today", "a", "2026-10-09T09:00:00Z", 240),
            session("future", "a", "2026-10-10T09:00:00Z", 300),
        )).overview(LocalDate.parse("2026-10-09"))
        assertEquals(listOf("2026-09-12", "2026-09-19", "2026-09-26", "2026-10-03"),
            statistics.rollingWeekly.map { it.startDate })
        assertEquals(listOf(60L, 0L, 120L, 420L), statistics.rollingWeekly.map { it.durationSeconds })
        assertEquals("2026-10-10", statistics.rollingWeekly.last().endDateExclusive)
        assertEquals("2026-10-05", statistics.weekly.last().startDate)
        assertEquals(240, statistics.thisWeek.totalDurationSeconds)
    }

    private fun engine(
        sessions: List<FocusSession>,
        tasks: List<Task> = emptyList(),
        categories: List<Category> = emptyList(),
        timeZone: TimeZone = TimeZone.UTC,
    ) = FocusStatisticsEngine(sessions, tasks, categories, timeZone)

    private fun task(uid: String, title: String = uid, categoryUid: String? = null, state: TaskState = TaskState.ACTIVE) =
        Task(uid, title, categoryUid = categoryUid, state = state, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")

    private fun category(uid: String, name: String) =
        Category(uid = uid, name = name, iconKey = "work", colorHex = "#123456", isPreset = false, sortOrder = 0,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")

    private fun session(uid: String, taskUid: String, started: String, seconds: Long) =
        FocusSession(uid, taskUid, "Snapshot $taskUid", FocusTimerMode.STOPWATCH, startedAt = started,
            endedAt = (Instant.parse(started) + seconds.seconds).toString(), durationSeconds = seconds,
            completionKind = FocusCompletionKind.FOCUS_ONLY, createdAt = started)
}
