package com.deadliner.service

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.DailySummaryConfig
import com.deadliner.platform.HabitNotificationConfig
import com.deadliner.platform.HabitNotificationInfo
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.TaskNotificationConfig
import com.deadliner.platform.TaskNotificationInfo
import com.deadliner.platform.UpcomingLiveUpdateConfig
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationOrchestratorTest {
    @Test
    fun taskLifecycleSchedulesOnlyActiveTasksWithDeadlines() {
        val gateway = RecordingNotificationGateway()
        val orchestrator = NotificationOrchestrator(gateway)
        val active = task(uid = "active", dueAt = "2026-08-01T09:00:00Z", isStarred = true)

        orchestrator.onTaskCreated(active)
        assertEquals(listOf("active"), gateway.taskSchedules.map { it.taskUid })
        assertEquals(60, gateway.taskSchedules.single().reminderMinutes)
        assertEquals("HIGH", gateway.taskSchedules.single().priority.name)

        orchestrator.onTaskUpdated(active.copy(state = TaskState.COMPLETED))
        orchestrator.onTaskUpdated(active.copy(dueAt = null))
        orchestrator.onTaskDeleted(active.uid)

        assertEquals(listOf("active", "active", "active"), gateway.cancelledTaskUids)
    }

    @Test
    fun habitLifecycleSchedulesOnlyEnabledActiveRemindersAndRefreshesFromPersistence() {
        val gateway = RecordingNotificationGateway()
        val orchestrator = NotificationOrchestrator(gateway)
        val enabled = habit(uid = "enabled", reminder = HabitReminder("08:30"))
        val disabled = habit(uid = "disabled", reminder = HabitReminder("09:00", isEnabled = false))
        val archived = habit(uid = "archived", reminder = HabitReminder("10:00"), status = HabitStatus.ARCHIVED)

        orchestrator.refreshAll(
            tasks = listOf(task(uid = "task.ok", dueAt = "2026-08-01T09:00:00Z"), task(uid = "task.none")),
            habits = listOf(enabled, disabled, archived),
        )

        assertEquals(0, gateway.cancelAllCalls)
        assertEquals(listOf("task.ok"), gateway.taskSchedules.map { it.taskUid })
        assertEquals(listOf("enabled"), gateway.habitSchedules.map { it.habitUid })
        assertTrue(gateway.cancelledTaskUids.contains("task.none"))
        assertEquals(setOf("disabled", "archived"), gateway.cancelledHabitUids.toSet())
    }

    @Test
    fun habitReminderOccurrencesComeFromKmpSchedule() {
        val gateway = RecordingNotificationGateway()
        val orchestrator = NotificationOrchestrator(
            gateway,
            todayProvider = { LocalDate.parse("2026-08-17") },
        )
        val habit = Habit(
            uid = "workdays",
            name = "Words",
            schedule = HabitSchedule(
                HabitScheduleKind.WEEKLY_DAYS,
                "2026-08-17",
                weekdaysMask = HabitWeekdays.MONDAY or HabitWeekdays.WEDNESDAY,
            ),
            goal = HabitGoal(),
            reminder = HabitReminder("08:30"),
            createdAt = NOW,
        )

        orchestrator.onHabitCreated(habit)

        val config = gateway.habitSchedules.single()
        assertTrue(config.isScheduleAuthoritative)
        assertEquals(
            listOf("2026-08-17T08:30", "2026-08-19T08:30", "2026-08-24T08:30"),
            config.scheduledLocalDateTimes.take(3),
        )

        orchestrator.onHabitRecordsChanged(
            habit,
            listOf(
                com.deadliner.domain.habit.HabitRecord(
                    uid = "done",
                    habitUid = habit.uid,
                    occurredOn = "2026-08-17",
                    createdAt = "2026-08-17T07:00:00Z",
                ),
            ),
        )
        assertEquals("2026-08-19T08:30", gateway.habitSchedules.last().scheduledLocalDateTimes.first())
    }

    @Test
    fun unifiedEntryDiffsPersistenceAndPreservesIndependentLiveSlots() {
        val gateway = RecordingNotificationGateway()
        var tasks = listOf(task("a", "2026-10-08T10:00:00Z"), task("b", "2026-10-08T11:00:00Z"))
        val orchestrator = NotificationOrchestrator(gateway, tasksProvider = { tasks }, habitsProvider = { emptyList() })
        orchestrator.triggerNotificationInfoUpdate()
        orchestrator.onTaskUpdated(tasks.first())
        assertEquals(2, gateway.taskSchedules.size)
        assertEquals(listOf("a", "b"), gateway.liveSchedules.map { it.taskUid })
        tasks = listOf(tasks.first().copy(title = "changed"))
        orchestrator.triggerNotificationInfoUpdate()
        assertEquals("changed", gateway.taskSchedules.last().title)
        assertEquals(listOf("b"), gateway.cancelledTaskUids)
        assertEquals(listOf("b"), gateway.cancelledLiveUids)
        orchestrator.triggerNotificationInfoUpdate(force = true)
        assertEquals(4, gateway.taskSchedules.size)
        assertEquals(0, gateway.cancelAllCalls)
        orchestrator.cancelAllNotifications()
        orchestrator.triggerNotificationInfoUpdate()
        assertEquals(5, gateway.taskSchedules.size)
        assertEquals(1, gateway.cancelAllCalls)
    }

    @Test
    fun refreshReadsHabitRecordsAndDeliveryRearmsUnchangedOccurrences() {
        val gateway = RecordingNotificationGateway()
        val habit = habit("h", HabitReminder("08:30"))
        val records = listOf(com.deadliner.domain.habit.HabitRecord(
            uid = "done", habitUid = "h", occurredOn = "2026-08-17", createdAt = NOW,
        ))
        val orchestrator = NotificationOrchestrator(gateway,
            todayProvider = { LocalDate.parse("2026-08-17") },
            tasksProvider = { emptyList() }, habitsProvider = { listOf(habit) }, recordsProvider = { records })
        orchestrator.triggerNotificationInfoUpdate()
        assertEquals("2026-08-18T08:30", gateway.habitSchedules.single().scheduledLocalDateTimes.first())
        orchestrator.triggerNotificationInfoUpdate()
        assertEquals(1, gateway.habitSchedules.size)
        orchestrator.onHabitNotificationDelivered(habit)
        assertEquals(2, gateway.habitSchedules.size)
    }

    private fun task(
        uid: String,
        dueAt: String? = null,
        state: TaskState = TaskState.ACTIVE,
        isStarred: Boolean = false,
    ) = Task(uid, "Task $uid", dueAt = dueAt, state = state, isStarred = isStarred, createdAt = NOW, updatedAt = NOW)

    private fun habit(
        uid: String,
        reminder: HabitReminder?,
        status: HabitStatus = HabitStatus.ACTIVE,
    ) = Habit(uid, "Habit $uid", period = HabitPeriod.DAILY, reminder = reminder, status = status, createdAt = NOW, updatedAt = NOW)

    private companion object {
        const val NOW = "2026-07-20T00:00:00Z"
    }
}

private class RecordingNotificationGateway : NotificationGateway {
    val taskSchedules = mutableListOf<TaskNotificationConfig>()
    val habitSchedules = mutableListOf<HabitNotificationConfig>()
    val cancelledTaskUids = mutableListOf<String>()
    val cancelledHabitUids = mutableListOf<String>()
    val liveSchedules = mutableListOf<UpcomingLiveUpdateConfig>()
    val cancelledLiveUids = mutableListOf<String>()
    var cancelAllCalls = 0

    override fun setTaskNotification(config: TaskNotificationConfig) { taskSchedules += config }
    override fun cancelTaskNotification(taskUid: String) { cancelledTaskUids += taskUid }
    override fun cancelAllTaskNotifications() = Unit
    override fun setHabitNotification(config: HabitNotificationConfig) { habitSchedules += config }
    override fun cancelHabitNotification(habitUid: String) { cancelledHabitUids += habitUid }
    override fun cancelAllHabitNotifications() = Unit
    override fun setDailySummaryNotification(config: DailySummaryConfig) = Unit
    override fun cancelDailySummaryNotification() = Unit
    override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) { liveSchedules += config }
    override fun cancelUpcomingLiveUpdate(taskUid: String) { cancelledLiveUids += taskUid }
    override fun cancelAll() { cancelAllCalls += 1 }
    override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) = Unit
}
