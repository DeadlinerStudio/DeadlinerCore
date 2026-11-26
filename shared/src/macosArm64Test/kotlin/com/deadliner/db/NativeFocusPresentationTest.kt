package com.deadliner.db

import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.viewmodel.focus.FocusTimerViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFocusPresentationTest {
    @Test fun nativeHabitArchiveAndDeleteFacadePreservesTypedHistoryAndOutcome() = runBlocking {
        val path = "${NSTemporaryDirectory()}native-focus-${NSUUID().UUIDString}.db"
        val db = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "native-focus-test"))
        try {
            for (delete in listOf(false, true)) {
                val uid = if (delete) "habit-delete" else "habit-archive"
                db.habits.create(Habit(uid, "Native Habit", schedule = HabitSchedule(
                    HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = HabitWeekdays.ALL),
                    goal = HabitGoal(targetCount = 1), createdAt = "2026-01-01T00:00:00Z"))
                var now = Instant.parse("2026-10-12T08:00:00Z")
                val service = db.createFocusTimerService()
                val vm = FocusTimerViewModel(service, db.focusSessions, db.tasks, nowProvider = { now })
                val started = vm.executeNow(FocusTimerCommand.StartHabit(uid, FocusTimerMode.STOPWATCH))
                assertNotNull(started.timer)
                now = Instant.parse("2026-10-12T08:00:30Z")
                val resolved = vm.resolveHabitChangeNow(uid, delete)
                assertEquals(FocusTimerOutcome.APPLIED, resolved.lastOutcome)
                assertNull(resolved.timer)
                assertFalse(resolved.isBusy)
                assertEquals(FocusTargetRef(FocusTargetKind.HABIT, uid), resolved.selectedTarget)
                assertEquals(30, resolved.lastCompletedSession?.durationSeconds)
                assertEquals(30, resolved.selectedTaskTotalDurationSeconds)
                assertTrue(db.habits.records(uid).isEmpty())
                if (delete) assertTrue(db.habits.find(uid)!!.isDeleted)
                else assertEquals(HabitStatus.ARCHIVED, db.habits.find(uid)?.status)
            }
            assertEquals(60, db.focusStatistics.overview().allTime.totalDurationSeconds)
            assertEquals(60, db.focusStatistics.overview().allTime.durationByTarget?.habitDurationSeconds)
        } finally { db.close(); remove(path) }
    }
}
