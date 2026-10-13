@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.deadliner.service

import com.deadliner.domain.habit.*
import com.deadliner.platform.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Schedule eligibility is supplied by HabitReminderPlanner; progress remains shared-domain data. */
internal object HabitRealtimeMetadataPlanner {
    fun entry(habit: Habit, records: List<HabitRecord>, at: Instant, date: String,
              now: Instant, zone: TimeZone): NotificationMetadataEntry? {
        val end = at + 1.hours
        if (now < at || now >= end + 3.minutes) return null
        val on = LocalDate.parse(date)
        val progress = HabitProgressEngine.progress(habit, records, on)
        val range = HabitProgressEngine.goalRange(habit, on)
        val windowRecords = records.filter { it.habitUid == habit.uid && it.occurredOn in range.start.toString()..range.endInclusive.toString() }
        val checkedAt = windowRecords.filter { !it.isDeleted && it.status == HabitRecordStatus.COMPLETED && it.occurredOn == date }
            .mapNotNull { NotificationMetadataPlanner.instant(it.updatedAt, zone) }
            .filter { it >= at && it < end && it <= now }.maxOrNull()
        val resultUntil = checkedAt?.plus(3.minutes)
        val checked = resultUntil != null && now < resultUntil
        if (!checked && (progress.isComplete || now >= end)) return null
        // Record state belongs in the version: habit.updatedAt alone does not change on check-in.
        val version = habit.updatedAt + "|" + windowRecords.sortedBy { it.uid }.joinToString("|") {
            "${it.uid}:${it.updatedAt}:${it.count}:${it.status}:${it.isDeleted}"
        }
        return NotificationMetadataEntry(
            key = "HABIT_REALTIME:${habit.uid}:$at", ownerUid = habit.uid, version = version,
            title = habit.name, kind = NotificationKind.HABIT_REALTIME, triggerAt = at.toString(),
            endsAt = (if (checked) requireNotNull(resultUntil) else end).toString(),
            phase = if (checked) NotificationPhase.HABIT_CHECKED_IN else NotificationPhase.HABIT_DUE,
            actions = if (checked) listOf(NotificationAction.IGNORE) else listOf(NotificationAction.IGNORE, NotificationAction.CHECK_IN),
            occurrenceOn = date, habitWindow = habit.goal.window,
            completedCount = progress.completedCount, targetCount = progress.targetCount,
        )
    }
}
