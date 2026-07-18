package com.deadliner.db

import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class DatabaseIntegrationTest {
    @Test
    fun persistsDomainAggregatesAndChangeLogWithNativeSqlite() {
        val path = "${NSTemporaryDirectory()}deadliner-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val category = Category("category.work", "Work", "briefcase", "#3B82F6", false, 10, NOW, NOW)
            database.categories.create(category)

            val task = Task(
                uid = "task.1",
                title = "Ship native database",
                categoryUid = category.uid,
                createdAt = NOW,
                updatedAt = NOW,
                subtasks = listOf(
                    TaskSubtask("subtask.1", "task.1", "Define schema", createdAt = NOW, updatedAt = NOW),
                    TaskSubtask("subtask.2", "task.1", "Run macOS test", sortOrder = 1, createdAt = NOW, updatedAt = NOW),
                ),
            )
            database.tasks.create(task)

            val habit = Habit("habit.1", "Review sync log", categoryUid = category.uid,
                period = HabitPeriod.DAILY, createdAt = NOW, updatedAt = NOW)
            database.habits.create(habit)
            database.habits.saveRecord(HabitRecord("record.1", habit.uid, "2026-07-18", createdAt = NOW), ChangeOperation.INSERT)

            assertEquals(task, database.tasks.find(task.uid))
            assertEquals(habit, database.habits.find(habit.uid))
            assertEquals(listOf("record.1"), database.habits.records(habit.uid).map { it.uid })
            assertEquals(6, database.changeLog.pending().size)

            database.categories.softDelete(category.uid, "2026-07-19T00:00:00Z")
            assertNull(database.categories.find(category.uid)?.takeUnless { it.isDeleted })
            assertNotNull(database.tasks.find(task.uid))
            assertEquals(7, database.changeLog.pending().size)

            database.tasks.delete(task.uid, "2026-07-19T00:00:00Z")
            database.habits.delete(habit.uid, "2026-07-19T00:00:00Z")
            assertTrue(database.tasks.find(task.uid)?.isDeleted == true)
            assertTrue(database.habits.find(habit.uid)?.isDeleted == true)
            assertTrue(database.tasks.list().isEmpty())
            assertTrue(database.habits.list().isEmpty())
            assertTrue(database.habits.records(habit.uid).isEmpty())
            assertEquals(12, database.changeLog.pending().size)
        } finally {
            database.close()
            remove(path)
        }
    }

    private companion object {
        const val NOW = "2026-07-18T00:00:00Z"
    }
}
