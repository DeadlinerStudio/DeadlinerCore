package com.deadliner.service

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitScheduleEngine
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.HabitNotificationConfig
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.NotificationPriority
import com.deadliner.platform.TaskNotificationConfig
import com.deadliner.platform.UpcomingLiveUpdateConfig
import com.deadliner.platform.PlatformLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Shared authority for deciding which persisted aggregates own a platform notification.
 *
 * Platform actuals only schedule or cancel requests. This class owns the lifecycle rule so the
 * result is identical for Android, iOS and HarmonyOS.
 */
class NotificationOrchestrator(
    private val notifications: NotificationGateway,
    private val taskReminderMinutes: Int = DEFAULT_TASK_REMINDER_MINUTES,
    private val todayProvider: () -> LocalDate = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
    private val tasksProvider: (() -> List<Task>)? = null,
    private val habitsProvider: (() -> List<Habit>)? = null,
    private val policyProvider: (() -> com.deadliner.platform.NotificationPolicy)? = null,
    private val interactionsProvider: () -> com.deadliner.platform.NotificationInteractionState = { com.deadliner.platform.NotificationInteractionState() },
    private val recordsProvider: (String) -> List<HabitRecord> = { emptyList() },
    private val focusResultProvider: () -> com.deadliner.platform.NotificationMetadataEntry? = { null },
) {
    init {
        require(taskReminderMinutes >= 0) { "taskReminderMinutes must not be negative" }
    }

    var lastMetadata: com.deadliner.platform.NotificationMetadata? = null
        private set

    private val logger = PlatformLogger()
    private val coordinationMutex = Mutex()
    private val registeredTasks = mutableMapOf<String, TaskNotificationConfig>()
    private val registeredHabits = mutableMapOf<String, HabitNotificationConfig>()

    /** Re-read committed state; native set methods are idempotent upserts. Force after boot/settings. */
    fun triggerNotificationInfoUpdate(force: Boolean = false) {
        val tasks = tasksProvider ?: return
        val habits = habitsProvider ?: return
        runBlocking {
            coordinationMutex.withLock {
                val taskSnapshot = tasks()
                val habitSnapshot = habits()
                logger.i("notification.update.begin", "force=$force tasks=${taskSnapshot.size} habits=${habitSnapshot.size}")
                refreshAll(taskSnapshot, habitSnapshot, force)
                logger.i("notification.update.end", "registeredTasks=${registeredTasks.size} registeredHabits=${registeredHabits.size}")
            }
        }
    }

    /** Keep the diff ledger consistent when a host explicitly clears every native request. */
    fun cancelAllNotifications() {
        runBlocking {
            coordinationMutex.withLock {
                lastMetadata = null
                notifications.cancelAll()
                registeredTasks.clear()
                registeredHabits.clear()
            }
        }
    }

    fun onTaskCreated(task: Task) = onTaskUpdated(task)
    fun onTaskUpdated(task: Task) {
        if (tasksProvider != null) triggerNotificationInfoUpdate() else reconcileTask(task)
    }
    fun onTaskDeleted(taskUid: String) {
        if (tasksProvider != null) triggerNotificationInfoUpdate() else cancelTask(taskUid)
    }
    fun onHabitCreated(habit: Habit) = onHabitUpdated(habit)
    fun onHabitUpdated(habit: Habit) {
        if (habitsProvider != null) triggerNotificationInfoUpdate() else reconcileHabit(habit, recordsProvider(habit.uid))
    }
    fun onHabitRecordsChanged(habit: Habit, records: List<HabitRecord>) {
        if (habitsProvider != null) triggerNotificationInfoUpdate() else reconcileHabit(habit, records)
    }
    fun onHabitDeleted(habitUid: String) {
        if (habitsProvider != null) triggerNotificationInfoUpdate() else cancelHabit(habitUid)
    }

    /** A one-shot platform alarm has been consumed; schedule the next occurrence even today. */
    fun onHabitNotificationDelivered(habit: Habit) {
        runBlocking {
            coordinationMutex.withLock {
                registeredHabits.remove(habit.uid)
                reconcileHabit(habit, recordsProvider(habit.uid))
            }
        }
    }

    /** Incremental reconciliation: never tear down unrelated daily summaries or live activities. */
    fun refreshAll(tasks: List<Task>, habits: List<Habit>, force: Boolean = false) {
        policyProvider?.let { policy ->
            val metadata = NotificationMetadataPlanner.plan(tasks, habits, recordsProvider, policy(), interactionsProvider(), focusResult = focusResultProvider())
            lastMetadata = metadata
            notifications.applyNotificationMetadata(metadata)
            return
        }
        val taskUids = tasks.map { it.uid }.toSet()
        val habitUids = habits.map { it.uid }.toSet()
        (registeredTasks.keys - taskUids).forEach(::cancelTask)
        (registeredHabits.keys - habitUids).forEach(::cancelHabit)
        if (force) {
            registeredTasks.clear()
            registeredHabits.clear()
        }
        tasks.forEach(::reconcileTask)
        habits.forEach { reconcileHabit(it, recordsProvider(it.uid)) }
    }

    private fun cancelTask(uid: String) {
        logger.i("notification.task.cancel", "uid=$uid")
        notifications.cancelTaskNotification(uid)
        notifications.cancelUpcomingLiveUpdate(uid)
        registeredTasks.remove(uid)
    }

    private fun cancelHabit(uid: String) {
        logger.i("notification.habit.cancel", "uid=$uid")
        notifications.cancelHabitNotification(uid)
        registeredHabits.remove(uid)
    }

    private fun reconcileTask(task: Task) {
        val dueAt = task.dueAt
        if (task.isDeleted || task.state != TaskState.ACTIVE || dueAt == null) {
            cancelTask(task.uid)
            return
        }
        val config = TaskNotificationConfig(
                taskUid = task.uid,
                title = task.title,
                dueAt = dueAt,
                reminderMinutes = taskReminderMinutes,
                priority = if (task.isStarred) NotificationPriority.HIGH else NotificationPriority.NORMAL,
            )
        if (registeredTasks[task.uid] == config) { logger.d("notification.task.unchanged", "uid=${task.uid}"); return }
        logger.i("notification.task.upsert", "uid=${task.uid} dueAt=$dueAt")
        notifications.setTaskNotification(config)
        notifications.setUpcomingLiveUpdate(UpcomingLiveUpdateConfig(task.uid, task.title, dueAt))
        registeredTasks[task.uid] = config
    }

    private fun reconcileHabit(habit: Habit, records: List<HabitRecord> = emptyList()) {
        val reminder = habit.reminder
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE || reminder == null || !reminder.isEnabled) {
            cancelHabit(habit.uid)
            return
        }
        val config = HabitNotificationConfig(
                habitUid = habit.uid,
                name = habit.name,
                alarmTime = reminder.localTime,
                scheduledLocalDateTimes = HabitReminderPlanner.plan(habit, records, todayProvider()),
                isScheduleAuthoritative = true,
            )
        if (registeredHabits[habit.uid] == config) { logger.d("notification.habit.unchanged", "uid=${habit.uid}"); return }
        logger.i("notification.habit.upsert", "uid=${habit.uid} occurrences=${config.scheduledLocalDateTimes.size}")
        notifications.setHabitNotification(config)
        registeredHabits[habit.uid] = config
    }

    private companion object {
        const val DEFAULT_TASK_REMINDER_MINUTES = 60
    }
}

object HabitReminderPlanner {
    private const val HORIZON_DAYS = 64

    fun plan(
        habit: Habit,
        records: List<HabitRecord>,
        from: LocalDate,
        horizonDays: Int = HORIZON_DAYS,
    ): List<String> {
        val reminder = habit.reminder?.takeIf { it.isEnabled } ?: return emptyList()
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE || horizonDays < 0) return emptyList()
        val through = from.plus(DatePeriod(days = horizonDays))
        return HabitScheduleEngine.dueDates(habit.schedule, from, through)
            .filterNot { date -> HabitProgressEngine.progress(habit, records, date).isComplete }
            .map { date -> "${date}T${reminder.localTime}" }
    }
}
