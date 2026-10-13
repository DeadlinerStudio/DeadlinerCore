@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.deadliner.service

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Pure shared rules, including time-only transitions with no repository mutation. */
object NotificationMetadataPlanner {
    fun plan(tasks: List<Task>, habits: List<Habit>, records: (String) -> List<HabitRecord>,
             policy: NotificationPolicy, interactions: NotificationInteractionState = NotificationInteractionState(),
             now: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault(),
             focusResult: NotificationMetadataEntry? = null): NotificationMetadata {
        val alarms = mutableListOf<NotificationMetadataEntry>()
        val live = mutableListOf<NotificationMetadataEntry>()
        val boundaries = mutableListOf<Instant>()
        fun boundary(at: Instant) { if (at > now) boundaries += at }
        tasks.filterNot { it.isDeleted }.forEach { task ->
            val due = instant(task.dueAt, zone) ?: return@forEach
            val start = due - 1.hours
            val end = due + 1.hours
            if (task.state == TaskState.ACTIVE) {
                if (policy.taskNearby && due - 1.days > now) alarms += task.entry(NotificationKind.TASK_NEARBY, due - 1.days, due)
                if (policy.taskDue && due > now) alarms += task.entry(NotificationKind.TASK_DUE, due, due)
                if (policy.taskRealtime) {
                    boundary(start); boundary(due); boundary(end)
                    if (now >= start && now < end) live += task.entry(NotificationKind.TASK_REALTIME, start, due).copy(
                        endsAt = end.toString(), phase = if (now < due) NotificationPhase.UPCOMING else NotificationPhase.OVERDUE,
                        actions = listOf(NotificationAction.GIVE_UP, NotificationAction.COMPLETE))
                }
            } else if (policy.taskRealtime) {
                val phase = when (task.state) {
                    TaskState.COMPLETED -> NotificationPhase.COMPLETED
                    TaskState.ABANDONED -> NotificationPhase.ABANDONED
                    else -> null
                }
                val receipt = interactions.taskResults.lastOrNull {
                    it.taskUid == task.uid && it.version == task.updatedAt && it.phase == phase
                } ?: return@forEach
                val occurred = instant(receipt.occurredAt, zone) ?: return@forEach
                val expires = occurred + 3.minutes
                if (occurred >= start && occurred < end && now >= occurred && now < expires) {
                    live += task.entry(NotificationKind.TASK_REALTIME, occurred, due).copy(endsAt = expires.toString(), phase = phase,
                        actions = listOf(NotificationAction.RESTORE_ACTIVE))
                    boundary(expires)
                }
            }
        }
        val today = now.toLocalDateTime(zone).date
        habits.forEach { habit ->
            val habitRecords = records(habit.uid)
            val occurrences = HabitReminderPlanner.plan(habit, habitRecords, today.minus(DatePeriod(days = 1)), 65)
                .mapNotNull { local -> instant(local, zone)?.let { it to local.take(10) } }
            val next = occurrences.firstOrNull { it.first > now }
            if (next != null) {
                val kind = if (policy.habitMode == HabitNotificationMode.REALTIME) NotificationKind.HABIT_START else NotificationKind.HABIT_DUE
                alarms += habit.entry(kind, next.first, next.second)
                if (policy.habitMode == HabitNotificationMode.REALTIME) boundary(next.first)
            }
            if (policy.habitMode == HabitNotificationMode.REALTIME) {
                // Completed goals disappear from ordinary reminder planning; result cards must
                // use the schedule and authoritative record progress independently.
                HabitReminderPlanner.plan(habit, emptyList(), today.minus(DatePeriod(days = 1)), 1)
                    .mapNotNull { local -> instant(local, zone)?.let { it to local.take(10) } }
                    .forEach { (at, date) ->
                        val entry = HabitRealtimeMetadataPlanner.entry(habit, habitRecords, at, date, now, zone)
                        if (entry != null && entry.key !in interactions.dismissedHabitOccurrences) {
                            live += entry
                            boundary(Instant.parse(requireNotNull(entry.endsAt)))
                        }
                    }
            }
        }
        if (focusResult != null) {
            live.removeAll { it.ownerUid == focusResult.ownerUid && it.kind == NotificationKind.TASK_REALTIME && it.phase == NotificationPhase.COMPLETED }
            live += focusResult
            focusResult.endsAt?.let { boundary(Instant.parse(it)) }
        }
        if (live.isNotEmpty()) boundary(Instant.parse(today.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toString()))
        val activeVersions = tasks.filter { !it.isDeleted && it.state == TaskState.ACTIVE }.associate { it.uid to it.updatedAt } +
            habits.filter { !it.isDeleted && it.status == com.deadliner.domain.habit.HabitStatus.ACTIVE && it.reminder?.isEnabled == true }.associate { it.uid to it.updatedAt }
        val ordinaryKinds = buildSet {
            if (policy.taskNearby) add(NotificationKind.TASK_NEARBY)
            if (policy.taskDue) add(NotificationKind.TASK_DUE)
            if (policy.habitMode == HabitNotificationMode.DUE) add(NotificationKind.HABIT_DUE)
        }
        return NotificationMetadata(alarms.sortedBy { it.triggerAt }, live.sortedWith(compareBy<NotificationMetadataEntry> { if (it.kind == NotificationKind.FOCUS_RESULT) 0 else 1 }.thenBy { it.dueAt }), boundaries.minOrNull()?.toString(), activeVersions, ordinaryKinds)
    }

    fun instant(raw: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Instant? = raw?.let {
        // Planned habit occurrences are local timestamps; avoid throwing once per future date.
        val time = it.substringAfter('T', "")
        if (it.endsWith("Z", ignoreCase = true) || '+' in time || '-' in time) {
            runCatching { Instant.parse(it) }.getOrNull()
        } else runCatching { Instant.parse(LocalDateTime.parse(it).toInstant(zone).toString()) }.getOrNull()
    }

    private fun Task.entry(kind: NotificationKind, at: Instant, due: Instant) = NotificationMetadataEntry(
        key = "${kind.name}:$uid", ownerUid = uid, version = updatedAt, title = title,
        kind = kind, triggerAt = at.toString(), dueAt = due.toString())
    private fun Habit.entry(kind: NotificationKind, at: Instant, date: String) = NotificationMetadataEntry(
        key = "${kind.name}:$uid:$at", ownerUid = uid, version = updatedAt, title = name,
        kind = kind, triggerAt = at.toString(), occurrenceOn = date)
}
