package com.deadliner.db

import com.deadliner.domain.focus.*
import com.deadliner.domain.task.*
import com.deadliner.domain.habit.*
import com.deadliner.viewmodel.focus.FocusTimerViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.*

class FocusActionParityTest {
    private fun task(uid: String) = Task(uid = uid, title = uid, createdAt = "2026-10-12T08:00:00Z", updatedAt = "2026-10-12T08:00:00Z")
    private fun now(seconds: Int) = Instant.parse("2026-10-12T08:00:00Z") + kotlin.time.Duration.parse(seconds.toString() + "s")
    private fun database(test: suspend (DeadlinerDatabase) -> Unit) = runBlocking {
        val path = NSTemporaryDirectory() + "focus-parity-" + NSUUID().UUIDString + ".db"
        val db = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-parity-test"))
        try { test(db) } finally { db.close(); remove(path) }
    }
    @Test fun taskActionsSaveActualDurationAndZeroDoesNotCreateRecord() = database { db ->
        for (action in listOf(TaskAction.MARK_COMPLETE, TaskAction.MARK_GIVE_UP)) for (seconds in listOf(0, 30)) {
            val uid = action.name + seconds
            db.tasks.create(task(uid))
            val service = db.createFocusTimerService()
            val started = service.execute(FocusTimerCommand.Start(uid, FocusTimerMode.STOPWATCH), now(0))
            val result = service.resolveTaskAction(uid, action, now(seconds), started.activeTimer!!.sessionUid)
            assertEquals(FocusTimerOutcome.APPLIED, result.outcome)
            assertNull(service.current(now(seconds)).activeTimer)
            assertEquals(if (seconds == 0) null else 30L, result.session?.durationSeconds)
            assertEquals(if (action == TaskAction.MARK_COMPLETE) TaskState.COMPLETED else TaskState.ABANDONED, db.tasks.find(uid)!!.state)
        }
    }
    @Test fun staleTaskAndHabitConfirmationsNeverSealNewSession() = database { db ->
        db.tasks.create(task("task"))
        val service = db.createFocusTimerService()
        val old = service.execute(FocusTimerCommand.Start("task", FocusTimerMode.STOPWATCH), now(0)).activeTimer!!
        service.execute(FocusTimerCommand.Finish, now(10))
        val fresh = service.execute(FocusTimerCommand.Start("task", FocusTimerMode.STOPWATCH), now(20)).activeTimer!!
        assertEquals(FocusTimerOutcome.INVALID_STATE, service.resolveTaskDeletion("task", now(30), old.sessionUid).outcome)
        assertEquals(fresh.sessionUid, service.current(now(30)).activeTimer!!.sessionUid)
        assertEquals(TaskState.ACTIVE, db.tasks.find("task")!!.state)
        service.execute(FocusTimerCommand.Discard, now(30))
        db.habits.create(Habit("habit", "Habit", schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = HabitWeekdays.ALL), goal = HabitGoal(targetCount = 1), createdAt = "2026-01-01T00:00:00Z"))
        val hOld = service.execute(FocusTimerCommand.StartHabit("habit", FocusTimerMode.STOPWATCH), now(40)).activeTimer!!
        service.execute(FocusTimerCommand.Finish, now(50))
        val hFresh = service.execute(FocusTimerCommand.StartHabit("habit", FocusTimerMode.STOPWATCH), now(60)).activeTimer!!
        assertEquals(FocusTimerOutcome.INVALID_STATE, service.resolveHabitChange("habit", true, now(70), hOld.sessionUid).outcome)
        assertEquals(hFresh.sessionUid, service.current(now(70)).activeTimer!!.sessionUid)
        assertFalse(db.habits.find("habit")!!.isDeleted)
    }
    @Test fun confirmedDeletionRejectsClockAnomalyAndAllowsZeroWithoutHistory() = database { db ->
        db.tasks.create(task("delete"))
        val service = db.createFocusTimerService()
        val active = service.execute(FocusTimerCommand.Start("delete", FocusTimerMode.STOPWATCH), now(0)).activeTimer!!
        assertEquals(FocusTimerOutcome.CLOCK_ANOMALY, service.resolveTaskDeletion("delete", now(-5), active.sessionUid).outcome)
        assertFalse(db.tasks.find("delete")!!.isDeleted)
        assertEquals(active.sessionUid, service.current(now(0)).activeTimer!!.sessionUid)
        val deleted = service.resolveTaskDeletion("delete", now(0), active.sessionUid)
        assertEquals(FocusTimerOutcome.APPLIED, deleted.outcome)
        assertNull(deleted.session)
        assertNull(service.current(now(0)).activeTimer)
        assertTrue(db.tasks.find("delete")!!.isDeleted)
        assertTrue(db.focusSessions.list().isEmpty())
    }
    @Test fun habitArchiveAndDeletionSaveOnlyEffectiveTimeAndNeverCheckIn() = database { db ->
        for (delete in listOf(false, true)) for (seconds in listOf(0, 10)) {
            val uid = "habit-" + delete + seconds
            db.habits.create(Habit(uid, uid,
                schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = HabitWeekdays.ALL),
                goal = HabitGoal(targetCount = 1), createdAt = "2026-01-01T00:00:00Z"))
            val service = db.createFocusTimerService()
            val active = service.execute(FocusTimerCommand.StartHabit(uid, FocusTimerMode.STOPWATCH), now(0)).activeTimer!!
            val result = service.resolveHabitChange(uid, delete, now(seconds), active.sessionUid)
            assertEquals(FocusTimerOutcome.APPLIED, result.outcome)
            assertEquals(if (seconds == 0) null else 10L, result.session?.durationSeconds)
            assertNull(service.current(now(seconds)).activeTimer)
            assertTrue(db.habits.records(uid).isEmpty())
            if (delete) assertTrue(db.habits.find(uid)!!.isDeleted)
            else assertEquals(HabitStatus.ARCHIVED, db.habits.find(uid)!!.status)
        }
    }
    @Test fun savedTaskCompletionIsIdempotentAndPreservesOtherTimer() = database { db ->
        db.tasks.create(task("a")); db.tasks.create(task("b"))
        val service = db.createFocusTimerService()
        service.execute(FocusTimerCommand.Start("a", FocusTimerMode.COUNTDOWN, 60), now(0))
        val saved = service.execute(FocusTimerCommand.Reconcile, now(65)).session!!
        val other = service.execute(FocusTimerCommand.Start("b", FocusTimerMode.STOPWATCH), now(70)).activeTimer!!
        assertEquals(FocusTimerOutcome.APPLIED, service.completeSavedTask(saved.uid, now(80)).outcome)
        assertEquals(FocusTimerOutcome.NO_OP, service.completeSavedTask(saved.uid, now(80)).outcome)
        assertEquals(other.sessionUid, service.current(now(80)).activeTimer!!.sessionUid)
        assertEquals(60L, db.focusSessions.totalForTask("a"))
        assertEquals(1, db.focusSessions.list().size)
        db.focusSessions.delete(saved.uid, now(50).toString())
        val deletedResult = service.completeSavedTask(saved.uid, now(90))
        assertEquals(FocusTimerOutcome.TIMER_NOT_FOUND, deletedResult.outcome)
        assertEquals(other.sessionUid, deletedResult.activeTimer!!.sessionUid)
    }
    @Test fun savedResultCannotCompleteTaskWithNewTimerAndStateReflectsCompletion() = database { db ->
        db.tasks.create(task("a"))
        var time = now(0)
        val service = db.createFocusTimerService()
        val vm = FocusTimerViewModel(service, db.focusSessions, db.tasks, nowProvider = { time })
        vm.executeNow(FocusTimerCommand.Start("a", FocusTimerMode.STOPWATCH))
        time = now(10)
        val result = vm.executeNow(FocusTimerCommand.Finish)
        assertTrue(result.canCompleteLastTask)
        val uid = result.lastCompletedSession!!.uid
        service.execute(FocusTimerCommand.Start("a", FocusTimerMode.STOPWATCH), now(20))
        assertEquals(FocusTimerOutcome.TIMER_REQUIRES_RESOLUTION, service.completeSavedTask(uid, now(30)).outcome)
        service.execute(FocusTimerCommand.Discard, now(30))
        time = now(40)
        val completed = vm.completeSavedTaskNow(uid)
        assertFalse(completed.canCompleteLastTask)
        assertEquals(TaskState.COMPLETED, completed.lastCompletedTaskState)
    }
}
