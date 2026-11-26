package com.deadliner.db

import com.deadliner.domain.task.Task
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitRecord
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.deadliner.platform.*
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class NotificationMutationIntegrationTest {
    @Test fun committedQueriesCoverDirectWritesRestoreAndRollback() {
        val path = "${NSTemporaryDirectory()}notification-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "notification-test"))
        val gateway = MutationGateway()
        database.configureNotificationUpdates(gateway)
        try {
            val task = Task(uid = "a", title = "before", dueAt = "2099-01-01T10:00:00Z", createdAt = "2026-10-07T00:00:00Z", updatedAt = "2026-10-07T00:00:00Z")
            database.tasks.create(task)
            assertEquals(listOf("before"), gateway.scheduled.map { it.title })
            val backup = database.backups.export()
            database.tasks.update(task.copy(title = "after"))
            assertEquals("after", gateway.scheduled.last().title)
            database.backups.restore(backup)
            assertEquals("before", gateway.scheduled.last().title)
            val count = gateway.scheduled.size
            runCatching {
                database.database.transaction {
                    database.tasks.update(task.copy(title = "rolled back"))
                    error("rollback")
                }
            }
            assertEquals(count, gateway.scheduled.size)
            database.tasks.delete(task.uid, "2026-10-07T01:00:00Z")
            assertEquals(listOf("a"), gateway.cancelled)
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
            database.habits.create(Habit(uid = "h", name = "habit", period = HabitPeriod.DAILY,
                reminder = HabitReminder("08:30"), createdAt = "2026-10-07T00:00:00Z", updatedAt = "2026-10-07T00:00:00Z"))
            assertEquals(today + "T08:30", gateway.habits.last().scheduledLocalDateTimes.first())
            database.habits.saveRecord(HabitRecord(uid = "record", habitUid = "h", occurredOn = today,
                createdAt = "2026-10-07T00:00:00Z"))
            kotlin.test.assertTrue(gateway.habits.last().scheduledLocalDateTimes.none { it.startsWith(today) })
        } finally {
            database.close()
            remove(path)
        }
    }
}

private class MutationGateway : NotificationGateway {
    val scheduled = mutableListOf<TaskNotificationConfig>()
    val cancelled = mutableListOf<String>()
    val habits = mutableListOf<HabitNotificationConfig>()
    override fun setTaskNotification(config: TaskNotificationConfig) { scheduled += config }
    override fun cancelTaskNotification(taskUid: String) { cancelled += taskUid }
    override fun cancelAllTaskNotifications() = Unit
    override fun setHabitNotification(config: HabitNotificationConfig) { habits += config }
    override fun cancelHabitNotification(habitUid: String) = Unit
    override fun cancelAllHabitNotifications() = Unit
    override fun setDailySummaryNotification(config: DailySummaryConfig) = Unit
    override fun cancelDailySummaryNotification() = Unit
    override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) = Unit
    override fun cancelUpcomingLiveUpdate(taskUid: String) = Unit
    override fun cancelAll() = Unit
    override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) = Unit
}
