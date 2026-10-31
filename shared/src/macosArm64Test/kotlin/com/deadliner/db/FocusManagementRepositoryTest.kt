package com.deadliner.db

import com.deadliner.db.repository.focus.FocusManagementRepository
import com.deadliner.domain.focus.*
import com.deadliner.domain.focus.management.FocusManagedTargetStatus
import com.deadliner.domain.task.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class FocusManagementRepositoryTest {
    @Test fun persistedEditsRenameAndTargetDeletionRefreshTheWholeManagementSnapshot() = runBlocking {
        val path = "${NSTemporaryDirectory()}focus-management-${NSUUID().UUIDString}.db"
        val core = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-management-test"))
        val now = "2026-10-14T12:00:00Z"
        try {
            val repository = FocusManagementRepository(core, { Instant.parse(now) }, { TimeZone.UTC })
            core.tasks.create(Task("target", "Original", createdAt = now, updatedAt = now))
            val timer = core.createFocusTimerService()
            timer.execute(FocusTimerCommand.Start("target", FocusTimerMode.STOPWATCH), Instant.parse("2026-10-14T09:00:00Z"))
            assertEquals(0L, repository.snapshot().today.totalDurationSeconds) // Active is not a saved fact.
            val saved = timer.execute(FocusTimerCommand.Finish, Instant.parse("2026-10-14T09:10:00Z")).session!!
            assertEquals(600L, repository.snapshot().historyPage().days.single().durationSeconds)
            core.tasks.update(core.tasks.find("target")!!.copy(title = "Renamed", updatedAt = now))
            assertEquals("Renamed", repository.snapshot().records.single().title)
            core.focusSessions.adjustDuration(saved.uid, 300, now)
            val adjusted = repository.snapshot()
            assertEquals(300L, adjusted.today.totalDurationSeconds)
            assertEquals(300L, adjusted.recentSevenDays.summary.totalDurationSeconds)
            assertEquals(300L, adjusted.records.single().maximumAdjustmentSeconds)
            core.tasks.delete("target", now)
            val deletedTarget = repository.snapshot()
            assertTrue(deletedTarget.startTargets.isEmpty())
            assertEquals("Original", deletedTarget.records.single().title)
            assertEquals(FocusManagedTargetStatus.DELETED, deletedTarget.records.single().targetStatus)
            core.focusSessions.delete(saved.uid, now)
            assertEquals(0, repository.snapshot().historyPage().totalCount)
            assertEquals(0L, repository.snapshot().allTime.summary.totalDurationSeconds)
        } finally { core.close(); remove(path) }
    }
}
