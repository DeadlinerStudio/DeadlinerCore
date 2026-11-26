package com.deadliner.viewmodel.focus

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.task.Task
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FocusTimerViewModelTest {
    @Test
    fun localDayRangeUsesCalendarBoundariesAcrossDaylightSavingTransition() {
        val range = focusLocalDayRange(
            now = Instant.parse("2026-03-08T12:00:00Z"),
            timeZone = TimeZone.of("America/Los_Angeles"),
        )

        assertEquals("2026-03-08T08:00:00Z", range.start.toString())
        assertEquals("2026-03-09T07:00:00Z", range.end.toString())
        assertEquals(23 * 60 * 60, (range.end - range.start).inWholeSeconds)
    }

    @Test
    fun exposesTodayTaskAndRecentSessionStatisticsWithoutClientAggregation() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-focus-viewmodel-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-viewmodel-test-device"))
        try {
            database.tasks.create(task("task.a", "Write release notes"))
            database.tasks.create(task("task.b", "Review changes"))
            val timer = database.createFocusTimerService()
            completeSession(timer, "task.a", "2026-09-09T23:50:00Z", "2026-09-10T00:10:00Z")
            val currentTaskSession = completeSession(
                timer,
                "task.a",
                "2026-09-10T09:00:00Z",
                "2026-09-10T09:20:00Z",
            )
            completeSession(timer, "task.b", "2026-09-10T10:00:00Z", "2026-09-10T10:05:00Z")

            val viewModel = FocusTimerViewModel(
                timerService = timer,
                sessionRepository = database.focusSessions,
                taskRepository = database.tasks,
                nowProvider = { Instant.parse("2026-09-10T12:00:00Z") },
                timeZoneProvider = { TimeZone.UTC },
            )

            val global = viewModel.loadNow(reconcile = false)
            assertEquals(1_500, global.todayDurationSeconds)
            assertEquals(2, global.todaySessionCount)
            assertEquals(listOf("task.b", "task.a", "task.a"), global.recentSessions.map(FocusSession::taskUid))

            viewModel.selectTask("task.a")
            val selected = viewModel.uiState.value
            assertEquals("Write release notes", selected.selectedTaskTitle)
            assertEquals(1_200, selected.selectedTaskTodayDurationSeconds)
            assertEquals(2_400, selected.selectedTaskTotalDurationSeconds)
            assertEquals(2, selected.selectedTaskRecentSessions.size)

            val adjusted = viewModel.adjustSessionDurationNow(currentTaskSession.uid, 600)
            assertEquals(FocusTimerOutcome.APPLIED, adjusted.lastOutcome)
            assertEquals(900, adjusted.todayDurationSeconds)
            assertEquals(1_800, adjusted.selectedTaskTotalDurationSeconds)
        } finally {
            database.close()
            remove(path)
        }
    }

    private suspend fun completeSession(
        timer: com.deadliner.service.focus.FocusTimerService,
        taskUid: String,
        startedAt: String,
        endedAt: String,
    ): FocusSession {
        assertEquals(
            FocusTimerOutcome.APPLIED,
            timer.execute(
                FocusTimerCommand.Start(taskUid, FocusTimerMode.STOPWATCH),
                Instant.parse(startedAt),
            ).outcome,
        )
        return assertNotNull(
            timer.execute(FocusTimerCommand.Finish, Instant.parse(endedAt)).session,
        )
    }

    private fun task(uid: String, title: String) = Task(
        uid = uid,
        title = title,
        createdAt = "2026-09-09T00:00:00Z",
        updatedAt = "2026-09-09T00:00:00Z",
    )
}
