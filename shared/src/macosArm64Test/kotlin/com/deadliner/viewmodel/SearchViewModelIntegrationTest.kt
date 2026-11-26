package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitDueReason
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.domain.task.Task
import com.deadliner.viewmodel.search.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class SearchViewModelIntegrationTest {
    @Test
    fun queryEnteredBeforeInitializationIsReappliedAfterRepositoriesLoad() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-search-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            database.tasks.create(
                Task("task", "Prepare migration", note = "KMP", createdAt = "2026-08-13T00:00:00Z", updatedAt = "2026-08-13T00:00:00Z"),
            )
            database.habits.create(
                Habit(
                    uid = "habit",
                    name = "Read KMP docs",
                    schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-08-13", weekdaysMask = HabitWeekdays.ALL),
                    goal = HabitGoal(),
                    createdAt = "2026-08-13T00:00:00Z",
                ),
            )
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val todayBit = 1 shl today.dayOfWeek.ordinal
            database.habits.create(
                Habit(
                    uid = "off-day-habit",
                    name = "KMP weekday plan",
                    schedule = HabitSchedule(
                        HabitScheduleKind.WEEKLY_DAYS,
                        "2026-08-13",
                        weekdaysMask = HabitWeekdays.ALL xor todayBit,
                    ),
                    goal = HabitGoal(),
                    createdAt = "2026-08-13T00:00:00Z",
                ),
            )
            val viewModel = SearchViewModel(
                taskRepository = database.tasks,
                habitRepository = database.habits,
                scope = CoroutineScope(coroutineContext),
            )

            viewModel.search("KMP")
            viewModel.initialize()
            withTimeout(5_000) {
                while (viewModel.uiState.value.isLoading) delay(10)
            }

            assertEquals(listOf("task"), viewModel.uiState.value.matchedTasks.map(Task::uid))
            assertEquals(listOf("habit", "off-day-habit"), viewModel.uiState.value.matchedHabits.map(Habit::uid))
            assertEquals(listOf("habit", "off-day-habit"), viewModel.uiState.value.matchedHabitStatuses.map { it.habit.uid })
            assertEquals(1, viewModel.uiState.value.matchedHabitStatuses.first().targetCount)
            val offDayStatus = viewModel.uiState.value.matchedHabitStatuses.last()
            assertEquals(false, offDayStatus.scheduleState.isDue)
            assertEquals(HabitDueReason.WEEKDAY_NOT_SELECTED, offDayStatus.scheduleState.reason)
            assertEquals(false, offDayStatus.canApplyPrimaryAction)
        } finally {
            database.close()
            remove(path)
        }
    }
}
