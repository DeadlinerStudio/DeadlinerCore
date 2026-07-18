package com.deadliner.domain

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.capture.CaptureConversionTarget
import com.deadliner.domain.sync.EntityVersion
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun taskStateMachinePreservesExistingTransitions() {
        assertEquals(TaskState.COMPLETED, TaskStateMachine.apply(TaskState.ACTIVE, TaskAction.MARK_COMPLETE))
        assertEquals(TaskState.ABANDONED_ARCHIVED, TaskStateMachine.apply(TaskState.ABANDONED, TaskAction.MARK_ARCHIVE))
        assertNull(TaskStateMachine.apply(TaskState.ACTIVE, TaskAction.MARK_ARCHIVE))
        assertNull(TaskStateMachine.apply(TaskState.COMPLETED, TaskAction.MARK_COMPLETE))
        assertTrue(TaskStateMachine.isNoOp(TaskState.COMPLETED, TaskAction.MARK_COMPLETE))
        assertTrue(TaskStateMachine.isVisibleInMainList(TaskState.ABANDONED))
    }

    @Test
    fun taskAndHabitHaveIndependentIdentityAndCategoryReferences() {
        val task = Task(
            uid = "task-1",
            title = "Write model",
            categoryUid = "category.work",
            createdAt = "2026-07-18T00:00:00Z",
            updatedAt = "2026-07-18T00:00:00Z",
        )
        val habit = Habit(
            uid = "habit-1",
            name = "Read",
            categoryUid = "category.work",
            period = HabitPeriod.DAILY,
            createdAt = "2026-07-18T00:00:00Z",
            updatedAt = "2026-07-18T00:00:00Z",
        )

        assertEquals("task-1", task.uid)
        assertEquals("habit-1", habit.uid)
        assertEquals(task.categoryUid, habit.categoryUid)
    }

    @Test
    fun entityVersionUsesTimestampCounterAndDeviceAsStableTieBreakers() {
        val first = EntityVersion("2026-07-18T00:00:00Z", 0, "device-a")
        val second = EntityVersion("2026-07-18T00:00:00Z", 1, "device-a")
        val third = EntityVersion("2026-07-18T00:00:00Z", 1, "device-b")

        assertTrue(first < second)
        assertTrue(second < third)
    }

    @Test
    fun captureConversionTargetsAnIndependentAggregate() {
        assertEquals(CaptureConversionTarget.TASK.name, "TASK")
        assertEquals(CaptureConversionTarget.HABIT.name, "HABIT")
    }
}
