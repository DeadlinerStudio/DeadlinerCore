package com.deadliner.db

import com.deadliner.domain.category.Category
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.ChangeLogSyncPayload
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3TaskItem
import com.deadliner.sync.snapshot.SnapshotV3Version
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun snapshotRestoresTaskWhenIncrementalLogDoesNotContainIt() {
        val path = "${NSTemporaryDirectory()}deadliner-snapshot-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val task = Task("task.recovered", "Recovered from full snapshot", createdAt = NOW, updatedAt = NOW)
            val payload = ChangeLogSyncPayload(
                snapshot = SnapshotV3Root(
                    version = SnapshotV3Version(NOW, "remote-device"),
                    tasks = listOf(SnapshotV3TaskItem(task.uid, SnapshotV3EntityVersion(NOW, 0, "remote-device"), false, task)),
                ),
                changes = emptyList(),
            )

            val result = database.sync.mergeRemotePayload(Json.encodeToString(payload))

            assertEquals(1, result.remoteApplied)
            assertEquals(task, database.tasks.find(task.uid))
            assertTrue(Json.decodeFromString<ChangeLogSyncPayload>(result.payload).snapshot?.tasks?.any { it.uid == task.uid } == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun legacyChangeOnlyPayloadRemainsReadable() {
        val path = "${NSTemporaryDirectory()}deadliner-legacy-payload-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val result = database.sync.mergeRemotePayload("""{"formatVersion":1,"changes":[]}""")

            assertEquals(0, result.remoteApplied)
            assertTrue(Json.decodeFromString<ChangeLogSyncPayload>(result.payload).snapshot != null)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun taskActionsUseSharedStateMachineAndWriteOneUpdatedAggregate() {
        val path = "${NSTemporaryDirectory()}deadliner-action-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val task = Task("task.action", "Use the shared state machine", createdAt = NOW, updatedAt = NOW)
            database.tasks.create(task)

            val completed = database.tasks.applyAction(task.uid, TaskAction.MARK_COMPLETE, "2026-07-19T01:00:00Z")
            assertEquals(TaskActionOutcome.APPLIED, completed.outcome)
            assertEquals("2026-07-19T01:00:00Z", completed.task?.completedAt)
            assertEquals("2026-07-19T01:00:00Z", completed.task?.updatedAt)

            val archived = database.tasks.applyAction(task.uid, TaskAction.MARK_ARCHIVE, "2026-07-19T02:00:00Z")
            assertEquals(TaskActionOutcome.APPLIED, archived.outcome)
            assertEquals("2026-07-19T01:00:00Z", archived.task?.completedAt)

            val invalid = database.tasks.applyAction(task.uid, TaskAction.MARK_GIVE_UP, "2026-07-19T03:00:00Z")
            assertEquals(TaskActionOutcome.INVALID, invalid.outcome)
            assertEquals("2026-07-19T02:00:00Z", database.tasks.find(task.uid)?.updatedAt)
        } finally {
            database.close()
            remove(path)
        }
    }

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

            val capture = CaptureItem("capture.1", "Plan migration", CaptureSource.AI, NOW, NOW)
            val memory = MemoryFragment("memory.1", "Prefer UID-first persistence", createdAt = NOW, updatedAt = NOW)
            val profile = UserProfile("profile.1", "Aritx", tier = UserTier.GEEK, createdAt = NOW, updatedAt = NOW)
            database.captures.create(capture)
            database.memory.create(memory)
            database.profiles.create(profile)

            assertEquals(task, database.tasks.find(task.uid))
            assertEquals(habit, database.habits.find(habit.uid))
            assertEquals(listOf("record.1"), database.habits.records(habit.uid).map { it.uid })
            assertEquals(capture, database.captures.find(capture.uid))
            assertEquals(memory, database.memory.find(memory.uid))
            assertEquals(profile, database.profiles.find(profile.uid))
            assertEquals(9, database.changeLog.pending().size)

            database.categories.softDelete(category.uid, "2026-07-19T00:00:00Z")
            assertNull(database.categories.find(category.uid)?.takeUnless { it.isDeleted })
            assertNotNull(database.tasks.find(task.uid))
            assertEquals(10, database.changeLog.pending().size)

            database.tasks.delete(task.uid, "2026-07-19T00:00:00Z")
            database.habits.delete(habit.uid, "2026-07-19T00:00:00Z")
            assertTrue(database.tasks.find(task.uid)?.isDeleted == true)
            assertTrue(database.habits.find(habit.uid)?.isDeleted == true)
            assertTrue(database.tasks.list().isEmpty())
            assertTrue(database.habits.list().isEmpty())
            assertTrue(database.habits.records(habit.uid).isEmpty())
            assertEquals(15, database.changeLog.pending().size)

            database.captures.delete(capture.uid, "2026-07-19T00:00:00Z")
            database.memory.delete(memory.uid, "2026-07-19T00:00:00Z")
            database.profiles.delete(profile.uid, "2026-07-19T00:00:00Z")
            assertTrue(database.captures.list().isEmpty())
            assertTrue(database.memory.list().isEmpty())
            assertTrue(database.profiles.list().isEmpty())
            assertEquals(18, database.changeLog.pending().size)
        } finally {
            database.close()
            remove(path)
        }
    }

    private companion object {
        const val NOW = "2026-07-18T00:00:00Z"
    }
}
