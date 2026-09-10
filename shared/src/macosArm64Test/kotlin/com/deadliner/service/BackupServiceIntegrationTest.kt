package com.deadliner.service

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.task.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class BackupServiceIntegrationTest {
    @Test
    fun restoreIncludesCompletedFocusSessionsAndClearsDeviceLocalTimer() {
        withDatabase { database ->
            database.tasks.create(Task("task.focus-backup", "Focus backup", createdAt = NOW, updatedAt = NOW))
            val timer = database.createFocusTimerService()
            val completed = assertNotNull(
                runBlocking {
                    timer.execute(
                        FocusTimerCommand.Start("task.focus-backup", FocusTimerMode.STOPWATCH),
                        kotlinx.datetime.Instant.parse("2026-08-13T00:00:00Z"),
                    )
                    timer.execute(
                        FocusTimerCommand.Finish,
                        kotlinx.datetime.Instant.parse("2026-08-13T00:20:00Z"),
                    ).session
                },
            )
            val backup = database.backups.export()

            database.focusSessions.delete(completed.uid, LATER)
            runBlocking {
                timer.execute(
                    FocusTimerCommand.Start("task.focus-backup", FocusTimerMode.STOPWATCH),
                    kotlinx.datetime.Instant.parse("2026-08-13T02:00:00Z"),
                )
            }
            database.backups.restore(backup)

            assertEquals(1_200, database.focusSessions.find(completed.uid)?.durationSeconds)
            assertEquals(
                FocusTimerOutcome.TIMER_NOT_FOUND,
                runBlocking { database.createFocusTimerService().current() }.outcome,
            )
        }
    }

    @Test
    fun restoreReplacesLocalDataAndIncludesLifiMemory() {
        withDatabase { database ->
            database.tasks.create(Task("task.backup", "Backup", createdAt = NOW, updatedAt = NOW))
            database.captures.create(CaptureItem("capture.backup", "Idea", CaptureSource.MANUAL, NOW, NOW))
            database.memory.create(MemoryFragment("memory.backup", "Remember this", "preference", NOW, 3, NOW))
            database.memory.updateUserProfile("Prefers concise plans")
            database.memory.appendConversationTurn("user", "Keep this conversation")
            val backup = database.backups.export()

            database.tasks.create(Task("task.after", "After backup", createdAt = LATER, updatedAt = LATER))
            database.tasks.delete("task.backup", LATER)
            database.memory.appendConversationTurn("assistant", "Discard this conversation")
            database.memory.updateUserProfile("Discard this profile")
            database.backups.restore(backup)

            assertEquals("Backup", database.tasks.find("task.backup")?.title)
            assertNull(database.tasks.find("task.after"))
            assertEquals("Idea", database.captures.find("capture.backup")?.text)
            assertEquals("Remember this", database.memory.find("memory.backup")?.content)
            assertEquals("Prefers concise plans", database.memory.getUserProfile())
            assertEquals("[user] Keep this conversation", database.memory.getRecentConversationContext(10))
        }
    }

    @Test
    fun invalidBackupDoesNotModifyLocalData() {
        withDatabase { database ->
            database.tasks.create(Task("task.safe", "Safe", createdAt = NOW, updatedAt = NOW))

            assertFails { database.backups.restore("not a v3 backup") }

            assertEquals("Safe", database.tasks.find("task.safe")?.title)
        }
    }

    private fun withDatabase(block: (DeadlinerDatabase) -> Unit) {
        val path = "${NSTemporaryDirectory()}deadliner-backup-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "backup-test-device"))
        try {
            block(database)
        } finally {
            database.close()
            remove(path)
        }
    }

    private companion object {
        const val NOW = "2026-08-13T00:00:00Z"
        const val LATER = "2026-08-13T01:00:00Z"
    }
}
