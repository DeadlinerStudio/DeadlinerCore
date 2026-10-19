package com.deadliner.db

import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskDeadlineDelay
import com.deadliner.domain.task.TaskDeadlineUnit
import com.deadliner.domain.task.TaskDeadlineCommands
import com.deadliner.domain.task.TaskDeadlineOutcome
import com.deadliner.domain.task.TaskState
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class TaskDeadlineRepositoryTest {
    @Test fun commandAccumulatesFromLatestPersistedDeadlineAndPreservesOtherFields() {
        withDatabase { database ->
            val task = Task("postpone", "Test", note = "Preserve", startAt = "2026-10-17T09:00",
                dueAt = "2026-10-19T09:00", state = TaskState.ACTIVE, isStarred = true,
                calendarEventId = 42, createdAt = "2026-10-17T01:00:00Z", updatedAt = "2026-10-17T01:00:00Z")
            database.tasks.create(task)
            val commands: TaskDeadlineCommands = database.tasks
            assertEquals(TaskDeadlineOutcome.APPLIED, commands.postponeOneDay(task.uid, "2026-10-19T01:00:00Z").outcome)
            val second = commands.postponeOneDay(task.uid, "2026-10-19T01:01:00Z")
            assertEquals(TaskDeadlineOutcome.APPLIED, second.outcome)
            val stored = database.tasks.find(task.uid)!!
            assertEquals(LocalDateTime.parse("2026-10-21T09:00"), LocalDateTime.parse(stored.dueAt!!))
            assertEquals(task.copy(dueAt = stored.dueAt, updatedAt = "2026-10-19T01:01:00Z"), stored)
            assertEquals(stored, second.task)
        }
    }
    @Test fun invalidOrMissingTaskDoesNotMutatePersistence() {
        withDatabase { database ->
            val task = Task("no-end", "No deadline", createdAt = "2026-10-17T01:00:00Z", updatedAt = "2026-10-17T01:00:00Z")
            database.tasks.create(task)
            assertEquals(TaskDeadlineOutcome.INVALID_DEADLINE,
                database.tasks.postponeOneDay(task.uid, "2026-10-19T01:00:00Z").outcome)
            assertEquals(task, database.tasks.find(task.uid))
            assertEquals(TaskDeadlineOutcome.NOT_FOUND,
                database.tasks.postponeOneDay("missing", "2026-10-19T01:00:00Z").outcome)
        }
    }
    @Test fun customDelayPersistsAndInvalidDelayCannotWrite() {
        withDatabase { database ->
            val task = Task("custom", "Custom", startAt = "2026-10-17T09:00", dueAt = "2026-10-19T23:00",
                createdAt = "2026-10-17T01:00:00Z", updatedAt = "2026-10-17T01:00:00Z")
            database.tasks.create(task)
            val result = database.tasks.postpone(task.uid, TaskDeadlineDelay(3, TaskDeadlineUnit.HOURS), "2026-10-19T01:00:00Z")
            assertEquals(TaskDeadlineOutcome.APPLIED, result.outcome)
            assertEquals(LocalDateTime.parse("2026-10-20T02:00"), LocalDateTime.parse(result.task!!.dueAt!!))
            assertEquals(task.startAt, result.task!!.startAt)
            for (delay in listOf(TaskDeadlineDelay(169, TaskDeadlineUnit.HOURS), TaskDeadlineDelay(8, TaskDeadlineUnit.DAYS),
                TaskDeadlineDelay(0, TaskDeadlineUnit.HOURS))) {
                assertEquals(TaskDeadlineOutcome.INVALID_DELAY, database.tasks.postpone(task.uid, delay, "2026-10-19T02:00:00Z").outcome)
                assertEquals(result.task, database.tasks.find(task.uid))
            }
        }
    }
    private fun withDatabase(block: (DeadlinerDatabase) -> Unit) {
        val path = "${NSTemporaryDirectory()}deadline-postpone-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "postpone-test"))
        try { block(database) } finally { database.close(); remove(path) }
    }
}
