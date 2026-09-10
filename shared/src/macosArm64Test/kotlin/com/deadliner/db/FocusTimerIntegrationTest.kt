package com.deadliner.db

import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.FocusAlarmPort
import com.deadliner.platform.FocusAlarmResult
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.Instant

class FocusTimerIntegrationTest {
    @Test
    fun countdownAlarmTracksPauseResumeAndFinishWithoutOwningCompletion() = runBlocking {
        val path = temporaryDatabasePath("focus-alarm")
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
        try {
            database.tasks.create(task("task.alarm"))
            val alarm = RecordingFocusAlarmPort()
            val timer = database.createFocusTimerService(alarm)

            timer.execute(
                FocusTimerCommand.Start("task.alarm", FocusTimerMode.COUNTDOWN, 1_500),
                instant("2026-09-10T07:00:00Z"),
            )
            timer.execute(FocusTimerCommand.Pause, instant("2026-09-10T07:05:00Z"))
            timer.execute(FocusTimerCommand.Reconcile, instant("2026-09-10T07:06:00Z"))
            timer.execute(FocusTimerCommand.Resume, instant("2026-09-10T07:10:00Z"))
            timer.execute(FocusTimerCommand.Reconcile, instant("2026-09-10T07:12:00Z"))
            val finished = timer.execute(FocusTimerCommand.Finish, instant("2026-09-10T07:15:00Z"))

            assertEquals(
                listOf("2026-09-10T07:25:00Z", "2026-09-10T07:30:00Z", "2026-09-10T07:30:00Z"),
                alarm.fireTimes,
            )
            assertEquals(3, alarm.cancelledSessionUids.size)
            assertEquals(600, assertNotNull(finished.session).durationSeconds)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun stopwatchPersistsOnlyActiveSegmentsAndSurvivesReopen() = runBlocking {
        val path = temporaryDatabasePath("focus-reopen")
        var database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
        try {
            database.tasks.create(task("task.timer"))
            val timer = database.createFocusTimerService()
            assertEquals(
                FocusTimerOutcome.APPLIED,
                timer.execute(
                    FocusTimerCommand.Start("task.timer", FocusTimerMode.STOPWATCH),
                    instant("2026-09-10T01:00:00Z"),
                ).outcome,
            )
            timer.execute(FocusTimerCommand.Pause, instant("2026-09-10T01:10:00Z"))
            database.close()

            database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
            val restoredTimer = database.createFocusTimerService()
            val paused = assertNotNull(restoredTimer.current(instant("2026-09-10T01:15:00Z")).snapshot)
            assertEquals(600, paused.elapsedSeconds)

            restoredTimer.execute(FocusTimerCommand.Resume, instant("2026-09-10T01:15:00Z"))
            val finished = restoredTimer.execute(FocusTimerCommand.Finish, instant("2026-09-10T01:35:00Z"))

            assertEquals(FocusTimerOutcome.APPLIED, finished.outcome)
            assertEquals(1_800, assertNotNull(finished.session).durationSeconds)
            assertEquals(TaskState.ACTIVE, database.tasks.find("task.timer")?.state)
            assertEquals(1_800, database.focusSessions.totalForTask("task.timer"))
            assertEquals(FocusTimerOutcome.TIMER_NOT_FOUND, restoredTimer.current().outcome)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun finishAndCompleteCommitsSessionAndTaskTogether() = runBlocking {
        val path = temporaryDatabasePath("focus-complete")
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
        try {
            database.tasks.create(task("task.complete"))
            val timer = database.createFocusTimerService()
            timer.execute(
                FocusTimerCommand.Start("task.complete", FocusTimerMode.COUNTDOWN, 1_500),
                instant("2026-09-10T02:00:00Z"),
            )
            val pendingBeforeFinish = database.changeLog.pending().size

            val finished = timer.execute(
                FocusTimerCommand.FinishAndCompleteTask,
                instant("2026-09-10T02:12:00Z"),
            )

            assertEquals(FocusTimerOutcome.APPLIED, finished.outcome)
            assertEquals(720, assertNotNull(finished.session).durationSeconds)
            assertEquals(TaskState.COMPLETED, assertNotNull(finished.task).state)
            assertEquals(TaskState.COMPLETED, database.tasks.find("task.complete")?.state)
            assertEquals(720, database.focusSessions.totalForTask("task.complete"))
            assertEquals(pendingBeforeFinish + 2, database.changeLog.pending().size)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun nonActiveTaskCannotStartAndCompletedCountdownDoesNotCompleteTask() = runBlocking {
        val path = temporaryDatabasePath("focus-state")
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
        try {
            database.tasks.create(task("task.inactive").copy(state = TaskState.COMPLETED))
            val timer = database.createFocusTimerService()
            assertEquals(
                FocusTimerOutcome.TASK_NOT_ACTIVE,
                timer.execute(
                    FocusTimerCommand.Start("task.inactive", FocusTimerMode.STOPWATCH),
                    instant("2026-09-10T03:00:00Z"),
                ).outcome,
            )

            database.tasks.create(task("task.countdown"))
            timer.execute(
                FocusTimerCommand.Start("task.countdown", FocusTimerMode.COUNTDOWN, 1_500),
                instant("2026-09-10T03:00:00Z"),
            )
            val reconciled = timer.execute(FocusTimerCommand.Reconcile, instant("2026-09-10T04:00:00Z"))

            assertEquals(1_500, assertNotNull(reconciled.session).durationSeconds)
            assertEquals(TaskState.ACTIVE, database.tasks.find("task.countdown")?.state)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun sessionCanOnlyBeAdjustedDownAndDeletedFromTotals() = runBlocking {
        val path = temporaryDatabasePath("focus-adjust")
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-test-device"))
        try {
            database.tasks.create(task("task.adjust"))
            val timer = database.createFocusTimerService()
            timer.execute(
                FocusTimerCommand.Start("task.adjust", FocusTimerMode.STOPWATCH),
                instant("2026-09-10T05:00:00Z"),
            )
            val session = assertNotNull(
                timer.execute(FocusTimerCommand.Finish, instant("2026-09-10T05:30:00Z")).session,
            )

            assertEquals(
                FocusTimerOutcome.INVALID_DURATION,
                database.focusSessions.adjustDuration(session.uid, 1_900, "2026-09-10T06:00:00Z").outcome,
            )
            assertEquals(
                FocusTimerOutcome.APPLIED,
                database.focusSessions.adjustDuration(session.uid, 1_200, "2026-09-10T06:00:00Z").outcome,
            )
            assertEquals(1_200, database.focusSessions.totalForTask("task.adjust"))

            database.focusSessions.delete(session.uid, "2026-09-10T06:01:00Z")
            assertEquals(0, database.focusSessions.totalForTask("task.adjust"))
            assertNull(database.focusSessions.list().firstOrNull { it.uid == session.uid })
        } finally {
            database.close()
            remove(path)
        }
    }

    private fun task(uid: String) = Task(
        uid = uid,
        title = "Focus task",
        createdAt = "2026-09-10T00:00:00Z",
        updatedAt = "2026-09-10T00:00:00Z",
    )

    private fun instant(value: String) = Instant.parse(value)

    private fun temporaryDatabasePath(name: String): String =
        "${NSTemporaryDirectory()}deadliner-$name-${NSUUID().UUIDString}.db"
}

private class RecordingFocusAlarmPort : FocusAlarmPort {
    val fireTimes = mutableListOf<String>()
    val cancelledSessionUids = mutableListOf<String>()

    override fun scheduleCountdown(
        sessionUid: String,
        fireAt: String,
        taskTitle: String,
    ): FocusAlarmResult {
        fireTimes += fireAt
        return FocusAlarmResult(succeeded = true)
    }

    override fun cancelCountdown(sessionUid: String): FocusAlarmResult {
        cancelledSessionUids += sessionUid
        return FocusAlarmResult(succeeded = true)
    }
}
