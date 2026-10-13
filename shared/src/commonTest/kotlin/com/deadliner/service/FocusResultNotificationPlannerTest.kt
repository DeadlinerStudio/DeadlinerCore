package com.deadliner.service

import com.deadliner.domain.focus.*
import com.deadliner.domain.task.*
import com.deadliner.domain.habit.*
import com.deadliner.platform.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*

class FocusResultNotificationPlannerTest {
    private val ended = "2026-10-09T12:00:00Z"
    private val task = Task("task", "Report", dueAt = ended, createdAt = ended, updatedAt = ended)
    private val session = FocusSession("session", task.uid, task.title, FocusTimerMode.STOPWATCH,
        startedAt = "2026-10-09T11:59:00Z", endedAt = ended, durationSeconds = 60,
        completionKind = FocusCompletionKind.FOCUS_ONLY, createdAt = ended)
    private fun plan(at: String = ended, saved: FocusSession = session, owner: Task? = task, active: ActiveFocusTimer? = null) =
        FocusResultNotificationPlanner.plan(saved, owner, active, Instant.parse(at))

    @Test fun supportedCompletionsExposeTaskNavigationAndSavedDuration() {
        listOf(FocusCompletionKind.FOCUS_ONLY, FocusCompletionKind.FOCUS_AND_TASK_COMPLETED, FocusCompletionKind.COUNTDOWN_ELAPSED).forEach {
            val result = plan(saved = session.copy(completionKind = it))!!
            assertEquals(NotificationKind.FOCUS_RESULT, result.kind)
            assertEquals(NotificationPhase.FOCUS_FINISHED, result.phase)
            assertEquals(listOf(NotificationAction.OPEN_TASK), result.actions)
            assertEquals(task.uid, result.ownerUid)
            assertEquals(session.uid, result.focusSessionUid)
            assertEquals(60L, result.focusDurationSeconds)
            assertEquals("2026-10-09T12:03:00Z", result.endsAt)
        }
    }
    @Test fun lifetimeUsesOriginalEndTimeAndEditsInvalidateButtonsWithoutExtendingIt() {
        assertNull(plan("2026-10-09T11:59:59Z"))
        assertNotNull(plan("2026-10-09T12:02:59Z"))
        assertNull(plan("2026-10-09T12:03:00Z"))
        val changed = plan("2026-10-09T12:02:00Z", session.copy(durationSeconds = 30, updatedAt = "2026-10-09T12:02:00Z"))!!
        assertEquals(plan()!!.endsAt, changed.endsAt)
        assertNotEquals(plan()!!.version, changed.version)
        assertEquals(30L, changed.focusDurationSeconds)
    }
    @Test fun deletedMissingOwnersAndNewActiveTimerCannotShowResult() {
        assertNull(plan(saved = session.copy(isDeleted = true)))
        assertNull(plan(owner = null))
        assertNull(plan(owner = task.copy(isDeleted = true)))
        assertNull(plan(owner = task.copy(uid = "other")))
        val active = ActiveFocusTimer("new", task.uid, task.title, FocusTimerMode.STOPWATCH,
            state = FocusTimerState.RUNNING, startedAt = ended, runningSegmentStartedAt = ended,
            accumulatedSeconds = 0, updatedAt = ended)
        assertNull(plan(active = active))
        listOf(FocusCompletionKind.TASK_BECAME_INACTIVE, FocusCompletionKind.FOCUS_AND_TASK_CHANGED).forEach {
            assertNull(plan(saved = session.copy(completionKind = it)))
        }
    }
    @Test fun sharedMetadataPrioritizesRewardSuppressesDuplicateCompletionAndSchedulesExpiry() {
        val completed = task.copy(state = TaskState.COMPLETED)
        val result = plan(owner = completed)!!
        val receipts = NotificationInteractionState(listOf(TaskNotificationResult(task.uid, task.updatedAt, NotificationPhase.COMPLETED, ended)))
        val metadata = NotificationMetadataPlanner.plan(listOf(completed), emptyList(), { emptyList() },
            NotificationPolicy(), receipts, Instant.parse(ended), TimeZone.UTC, focusResult = result)
        assertEquals(listOf(result), metadata.realtime)
        assertEquals(result.endsAt, metadata.nextRefreshAt)
        val activeMetadata = NotificationMetadataPlanner.plan(listOf(task), emptyList(), { emptyList() },
            NotificationPolicy(), now = Instant.parse(ended), zone = TimeZone.UTC, focusResult = plan())
        assertEquals(NotificationKind.FOCUS_RESULT, activeMetadata.realtime.first().kind)
        assertTrue(activeMetadata.realtime.any { it.kind == NotificationKind.TASK_REALTIME })
    }
    @Test fun habitResultNavigatesBySessionWithoutTaskActionsOrImplicitQuantity() {
        val habit = Habit("habit", "Read", schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS,
            "2026-01-01", weekdaysMask = HabitWeekdays.ALL), goal = HabitGoal(), createdAt = ended)
        val saved = session.copy(taskUid = "", taskTitleSnapshot = habit.name,
            target = FocusTargetRef(FocusTargetKind.HABIT, habit.uid), habitOccurredOn = "2026-10-09")
        listOf(FocusCompletionKind.FOCUS_ONLY, FocusCompletionKind.COUNTDOWN_ELAPSED,
            FocusCompletionKind.FOCUS_AND_HABIT_RECORDED).forEach { kind ->
            val result = FocusResultNotificationPlanner.planHabit(saved.copy(completionKind = kind), habit, null, Instant.parse(ended))!!
            assertEquals(listOf(NotificationAction.OPEN_FOCUS), result.actions)
            assertEquals(saved.uid, result.focusSessionUid)
            assertEquals(habit.uid, result.ownerUid)
            assertEquals(saved.habitOccurredOn, result.occurrenceOn)
            assertEquals(60L, result.focusDurationSeconds)
        }
        assertNull(FocusResultNotificationPlanner.planHabit(saved.copy(completionKind = FocusCompletionKind.HABIT_BECAME_INACTIVE),
            habit, null, Instant.parse(ended)))
        assertNull(FocusResultNotificationPlanner.planHabit(saved, habit.copy(isDeleted = true), null, Instant.parse(ended)))
        assertNull(FocusResultNotificationPlanner.planHabit(saved, habit, null, Instant.parse("2026-10-09T12:03:00Z")))
    }
}
