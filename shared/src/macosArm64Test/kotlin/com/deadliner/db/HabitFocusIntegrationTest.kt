package com.deadliner.db

import com.deadliner.domain.focus.*
import com.deadliner.domain.habit.*
import com.deadliner.domain.task.Task
import com.deadliner.domain.statistics.FocusStatisticsQuery
import com.deadliner.service.focus.FocusTimerService
import com.deadliner.sync.ChangeLogSyncPayload
import com.deadliner.sync.snapshot.SnapshotV3Codec
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.*
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.*

class HabitFocusIntegrationTest {
    private suspend fun fixture(block: suspend (DeadlinerDatabase, FocusTimerService) -> Unit) {
        val path = "${NSTemporaryDirectory()}habit-focus-${NSUUID().UUIDString}.db"
        val db = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "habit-focus-test"))
        try { block(db, db.createFocusTimerService()) } finally { db.close(); remove(path) }
    }
    private fun habit(quantity: Boolean = false, uid: String = "same") = Habit(uid, "Habit",
        schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = HabitWeekdays.ALL),
        goal = HabitGoal(targetCount = if (quantity) 20 else 1),
        tracking = HabitTracking(if (quantity) HabitTrackingMode.QUANTITY else HabitTrackingMode.CHECK_OFF, "pages"),
        createdAt = "2026-01-01T00:00:00Z")
    private fun at(seconds: Int) = Instant.parse("2026-10-11T08:00:00Z") + kotlin.time.Duration.parse("${seconds}s")
    private suspend fun start(timer: FocusTimerService, countdown: Boolean = false) = assertNotNull(timer.execute(
        FocusTimerCommand.StartHabit("same", if (countdown) FocusTimerMode.COUNTDOWN else FocusTimerMode.STOPWATCH,
            if (countdown) 60 else null), at(0)).snapshot)

    @Test fun startPauseResumeAndPlainFinishPreserveTargetWithoutCheckIn() = runBlocking { fixture { db, timer ->
        db.habits.create(habit())
        val started = start(timer)
        assertEquals(FocusTargetRef(FocusTargetKind.HABIT, "same"), started.target)
        assertEquals("", started.taskUid)
        assertFalse(started.canFinishAndRecord) // Zero-duration sessions cannot be saved.
        assertTrue(timer.current(at(1)).snapshot!!.canFinishAndRecord)
        assertFalse(started.canFinishAndComplete)
        timer.execute(FocusTimerCommand.Pause, at(30))
        timer.execute(FocusTimerCommand.Resume, at(60))
        val session = assertNotNull(timer.execute(FocusTimerCommand.Finish, at(90)).session)
        assertEquals(60, session.durationSeconds)
        assertTrue(db.habits.records("same").isEmpty())
        assertEquals(0, db.focusSessions.totalForTask("same"))
        assertEquals(1, db.focusSessions.forTarget(session.boundTarget).size)
    } }

    @Test fun checkOffJointFinishIsAtomicAndIdempotentAndNeverToggles() = runBlocking { fixture { db, timer ->
        db.habits.create(habit())
        val first = start(timer)
        val command = FocusTimerCommand.FinishAndRecordHabit(first.sessionUid)
        assertEquals(FocusTimerOutcome.APPLIED, timer.execute(command, at(30)).outcome)
        assertEquals(FocusTimerOutcome.NO_OP, timer.execute(command, at(40)).outcome)
        assertEquals(1, db.habits.records("same").single().count)
        val second = timer.execute(FocusTimerCommand.StartHabit("same", FocusTimerMode.STOPWATCH), at(60))
        val finished = timer.execute(FocusTimerCommand.FinishAndRecordHabit(second.snapshot!!.sessionUid), at(90))
        assertEquals(0, finished.session?.habitRecordedAmount)
        assertEquals(1, db.habits.records("same").single().count)
        db.focusSessions.delete(first.sessionUid, at(120).toString())
        assertEquals(1, db.habits.records("same").single().count)
    } }

    @Test fun quantityValidationDoesNotEndTimerAndActualAmountIsAddedOnce() = runBlocking { fixture { db, timer ->
        db.habits.create(habit(true))
        val started = start(timer)
        assertEquals(FocusTimerOutcome.HABIT_INVALID_AMOUNT, timer.execute(FocusTimerCommand.FinishAndRecordHabit(started.sessionUid, 0), at(30)).outcome)
        assertNotNull(timer.current(at(30)).snapshot)
        assertTrue(db.focusSessions.list().isEmpty())
        val command = FocusTimerCommand.FinishAndRecordHabit(started.sessionUid, 7)
        val result = timer.execute(command, at(40))
        assertEquals(7, result.session?.habitRecordedAmount)
        assertEquals(7, db.habits.records("same").single().count)
        timer.execute(command, at(50))
        assertEquals(7, db.habits.records("same").single().count)
    } }

    @Test fun countdownAutoEndCanRecordLaterWithoutClearingAnotherTimer() = runBlocking { fixture { db, timer ->
        db.habits.create(habit(true))
        db.tasks.create(Task("same", "Task", createdAt = at(0).toString(), updatedAt = at(0).toString()))
        val started = start(timer, true)
        val saved = assertNotNull(timer.execute(FocusTimerCommand.Reconcile, at(80)).session)
        assertEquals(60, saved.durationSeconds)
        assertTrue(db.habits.records("same").isEmpty())
        val taskTimer = timer.execute(FocusTimerCommand.Start("same", FocusTimerMode.STOPWATCH), at(90)).snapshot!!
        val command = FocusTimerCommand.FinishAndRecordHabit(started.sessionUid, 4)
        assertEquals(FocusTimerOutcome.APPLIED, timer.execute(command, at(120)).outcome)
        assertEquals(taskTimer.sessionUid, timer.current(at(120)).snapshot?.sessionUid)
        assertEquals(FocusTimerOutcome.NO_OP, timer.execute(command, at(130)).outcome)
        assertEquals(4, db.habits.records("same").single().count)
    } }

    @Test fun metadataChangeRejectsStaleQuantityRequestWithoutLosingTime() = runBlocking { fixture { db, timer ->
        val habit = habit(true)
        db.habits.create(habit)
        val started = start(timer)
        db.habits.update(habit.copy(name = "Renamed", updatedAt = at(20).toString()))
        val result = timer.execute(FocusTimerCommand.FinishAndRecordHabit(started.sessionUid, 3, habit.updatedAt), at(30))
        assertEquals(FocusTimerOutcome.INVALID_STATE, result.outcome)
        assertEquals("Renamed", result.snapshot?.taskTitle)
        assertTrue(db.focusSessions.list().isEmpty())
        assertTrue(db.habits.records("same").isEmpty())
    } }

    @Test fun archiveAndDeleteRequireResolutionAndSaveFocus() = runBlocking { fixture { db, timer ->
        db.habits.create(habit())
        start(timer)
        assertFailsWith<IllegalStateException> { db.habits.applyStatusAction("same", HabitStatusAction.ARCHIVE, at(10).toString()) }
        assertFailsWith<IllegalStateException> { db.habits.delete("same", at(10).toString()) }
        val result = timer.resolveHabitChange("same", false, at(30))
        assertEquals(FocusTimerOutcome.APPLIED, result.outcome)
        assertEquals(30, result.session?.durationSeconds)
        assertEquals(HabitStatus.ARCHIVED, db.habits.find("same")?.status)
        assertNull(timer.current(at(40)).snapshot)
        assertEquals(FocusTimerOutcome.HABIT_NOT_ACTIVE, timer.execute(FocusTimerCommand.StartHabit("same", FocusTimerMode.STOPWATCH), at(50)).outcome)
    } }

    @Test fun typedStatisticsAndBackupRoundTripDoNotMixTaskAndHabitUid() = runBlocking { fixture { db, timer ->
        db.habits.create(habit(true))
        db.tasks.create(Task("same", "Task", createdAt = at(0).toString(), updatedAt = at(0).toString()))
        val started = start(timer)
        timer.execute(FocusTimerCommand.FinishAndRecordHabit(started.sessionUid, 3), at(60))
        timer.execute(FocusTimerCommand.Start("same", FocusTimerMode.STOPWATCH), at(70))
        timer.execute(FocusTimerCommand.Finish, at(100))
        val stats = db.focusStatistics.snapshot(FocusStatisticsQuery())
        assertEquals(90, stats.summary.totalDurationSeconds)
        assertEquals(1, stats.summary.taskCount)
        assertEquals(1, stats.summary.habitCount)
        assertEquals(2, stats.summary.targetCount)
        assertEquals(2, stats.targets.size)
        assertEquals(1, stats.tasks.size)
        val backup = db.backups.export()
        db.backups.restore(backup)
        assertEquals(3, db.focusSessions.find(started.sessionUid)?.habitRecordedAmount)
        assertEquals(3, db.habits.records("same").single().count)
        val payload = db.sync.mergeRemotePayload(null)
        assertNull(payload.failureMessage)
        val json = Json.parseToJsonElement(payload.payload).jsonObject
        assertEquals(ChangeLogSyncPayload.FORMAT_VERSION, json["formatVersion"]!!.jsonPrimitive.int)
        assertEquals(2, SnapshotV3Codec.decode(json["snapshot"].toString()).focusSessions.size)
    } }

    @Test fun startConflictsWithTaskAndV5SyncUpgradesWithoutLosingTaskHistory() = runBlocking { fixture { db, timer ->
        db.habits.create(habit())
        db.tasks.create(Task("same", "Task", createdAt = at(0).toString(), updatedAt = at(0).toString()))
        timer.execute(FocusTimerCommand.Start("same", FocusTimerMode.STOPWATCH), at(0))
        val conflict = timer.execute(FocusTimerCommand.StartHabit("same", FocusTimerMode.STOPWATCH), at(10))
        assertEquals(FocusTimerOutcome.TIMER_ALREADY_ACTIVE, conflict.outcome)
        assertEquals("Task", conflict.snapshot?.taskTitle)
        val session = timer.execute(FocusTimerCommand.Finish, at(60)).session!!
        val payload = db.sync.mergeRemotePayload(null).payload
            .replace("\"formatVersion\":6", "\"formatVersion\":5").replace("\"format_version\":6", "\"format_version\":5")
        assertNull(db.sync.mergeRemotePayload(payload).failureMessage)
        assertEquals(session, db.focusSessions.find(session.uid))
        val unsupported = payload.replace("\"formatVersion\":5", "\"formatVersion\":99")
        assertNotNull(db.sync.mergeRemotePayload(unsupported).failureMessage)
        assertEquals(session, db.focusSessions.find(session.uid))
    } }

    @Test fun offDayOnlySavesTimeAndRemoteDeletionReconcilesWithoutCheckIn() = runBlocking { fixture { db, timer ->
        val weekday = at(0).toLocalDateTime(TimeZone.currentSystemDefault()).date.dayOfWeek.ordinal
        val notToday = 1 shl ((weekday + 1) % 7)
        db.habits.create(habit().copy(schedule = habit().schedule.copy(weekdaysMask = notToday)))
        val started = start(timer)
        val rejected = timer.execute(FocusTimerCommand.FinishAndRecordHabit(started.sessionUid), at(20))
        assertEquals(FocusTimerOutcome.HABIT_NOT_DUE, rejected.outcome)
        assertNotNull(rejected.activeTimer)
        assertTrue(db.focusSessions.list().isEmpty())
        db.database.habitQueries.softDeleteHabit(at(25).toString(), "same") // Remote merge bypasses local guard.
        val reconciled = timer.execute(FocusTimerCommand.Resume, at(30))
        assertEquals(FocusCompletionKind.HABIT_BECAME_INACTIVE, reconciled.session?.completionKind)
        assertEquals(30, reconciled.session?.durationSeconds)
        assertNull(reconciled.activeTimer)
        assertTrue(db.habits.records("same").isEmpty())
    } }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun crossMidnightUsesStartDayAndColdViewModelRecoversPendingResult() = runBlocking { fixture { db, timer ->
        db.habits.create(habit(true))
        val zone = TimeZone.currentSystemDefault()
        val day = at(0).toLocalDateTime(zone).date.toString()
        val startedAt = Instant.parse(LocalDateTime.parse("${day}T23:59:30").toInstant(zone).toString())
        val active = timer.execute(FocusTimerCommand.StartHabit("same", FocusTimerMode.STOPWATCH), startedAt).snapshot!!
        val endedAt = startedAt + kotlin.time.Duration.parse("60s")
        val saved = timer.execute(FocusTimerCommand.Finish, endedAt).session!!
        assertEquals(day, saved.habitOccurredOn)
        val vm = com.deadliner.viewmodel.focus.FocusTimerViewModel(timer, db.focusSessions, db.tasks, nowProvider = { endedAt })
        val recovered = vm.loadNow()
        assertEquals(saved.uid, recovered.lastCompletedSession?.uid)
        assertEquals(day, recovered.habitRecordContext?.occurredOn)
        assertEquals(FocusTargetRef(FocusTargetKind.HABIT, "same"), recovered.selectedTarget)
        timer.execute(FocusTimerCommand.FinishAndRecordHabit(active.sessionUid, 2), endedAt)
        assertEquals(day, db.habits.records("same").single().occurredOn)
    } }

    @Test fun focusInsertFailureRollsBackHabitRecordsAndKeepsActiveTimer() = runBlocking {
        val path = "${NSTemporaryDirectory()}habit-focus-rollback-${NSUUID().UUIDString}.db"
        val driver = MacosDatabaseDriverFactory(path, "habit-focus-rollback").createDriver()
        val db = DeadlinerDatabase.create(object : DatabaseDriverFactory { override fun createDriver() = driver })
        try {
            db.habits.create(habit(true))
            val timer = db.createFocusTimerService()
            val active = start(timer)
            driver.execute(null, """CREATE TRIGGER reject_focus BEFORE INSERT ON focus_session
                BEGIN SELECT RAISE(ABORT, 'fixture focus insert failed'); END""", 0)
            assertFailsWith<Exception> { timer.execute(FocusTimerCommand.FinishAndRecordHabit(active.sessionUid, 5), at(30)) }
            assertTrue(db.habits.records("same").isEmpty())
            assertTrue(db.focusSessions.list().isEmpty())
            assertEquals(active.sessionUid, timer.current(at(30)).snapshot?.sessionUid)
        } finally { db.close(); remove(path) }
    }
}
