@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.deadliner.service

import com.deadliner.domain.focus.*
import com.deadliner.domain.task.Task
import com.deadliner.platform.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.minutes

/** Result timing comes from the saved end time, never from edits or a native publish time. */
object FocusResultNotificationPlanner {
    fun planHabit(session: FocusSession?, habit: com.deadliner.domain.habit.Habit?, activeTimer: ActiveFocusTimer?, now: Instant): NotificationMetadataEntry? {
        if (activeTimer != null || session == null || session.isDeleted || habit == null || habit.isDeleted ||
            session.boundTarget != FocusTargetRef(FocusTargetKind.HABIT, habit.uid)) return null
        if (session.completionKind !in listOf(FocusCompletionKind.FOCUS_ONLY, FocusCompletionKind.COUNTDOWN_ELAPSED,
                FocusCompletionKind.FOCUS_AND_HABIT_RECORDED)) return null
        val ended = Instant.parse(session.endedAt)
        val expires = ended + 3.minutes
        if (now < ended || now >= expires) return null
        return NotificationMetadataEntry(key = "FOCUS_RESULT:${session.uid}", ownerUid = habit.uid,
            version = "${session.uid}:${session.updatedAt}:${habit.updatedAt}", title = habit.name,
            kind = NotificationKind.FOCUS_RESULT, triggerAt = ended.toString(), endsAt = expires.toString(),
            phase = NotificationPhase.FOCUS_FINISHED, actions = listOf(NotificationAction.OPEN_FOCUS),
            focusSessionUid = session.uid, focusDurationSeconds = session.durationSeconds, occurrenceOn = session.habitOccurredOn)
    }

    fun plan(session: FocusSession?, task: Task?, activeTimer: ActiveFocusTimer?, now: Instant): NotificationMetadataEntry? {
        if (activeTimer != null || session == null || session.isDeleted || task == null || task.isDeleted || task.uid != session.taskUid) return null
        if (session.completionKind !in listOf(FocusCompletionKind.FOCUS_ONLY,
                FocusCompletionKind.FOCUS_AND_TASK_COMPLETED, FocusCompletionKind.COUNTDOWN_ELAPSED)) return null
        val ended = Instant.parse(session.endedAt)
        val expires = ended + 3.minutes
        if (now < ended || now >= expires) return null
        return NotificationMetadataEntry(
            key = "FOCUS_RESULT:${session.uid}", ownerUid = task.uid,
            version = "${session.uid}:${session.updatedAt}:${task.updatedAt}", title = task.title,
            kind = NotificationKind.FOCUS_RESULT, triggerAt = ended.toString(), endsAt = expires.toString(),
            phase = NotificationPhase.FOCUS_FINISHED, actions = listOf(NotificationAction.OPEN_TASK),
            focusSessionUid = session.uid, focusDurationSeconds = session.durationSeconds,
        )
    }
}
