package com.deadliner.db.repository.focus

import com.deadliner.db.AppDatabase
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.ActiveFocusTimer
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerState

class ActiveFocusTimerStore(
    private val database: AppDatabase,
) {
    fun current(): ActiveFocusTimer? =
        database.activeFocusTimerQueries.selectActive(::toActive).executeAsOneOrNull()

    fun save(active: ActiveFocusTimer) {
        database.activeFocusTimerQueries.upsertActive(
            active.sessionUid,
            active.taskUid,
            active.taskTitleSnapshot,
            active.mode.name,
            active.plannedDurationSeconds,
            active.state.name,
            active.startedAt,
            active.runningSegmentStartedAt,
            active.accumulatedSeconds,
            active.updatedAt,
            active.boundTarget.kind.name,
            active.target?.takeIf { it.kind == FocusTargetKind.HABIT }?.uid,
            active.habitOccurredOn,
        )
    }

    fun clear() {
        database.activeFocusTimerQueries.deleteActive()
    }

    private fun toActive(
        sessionUid: String,
        taskUid: String,
        taskTitleSnapshot: String,
        mode: String,
        plannedDurationSeconds: Long?,
        state: String,
        startedAt: String,
        runningSegmentStartedAt: String?,
        accumulatedSeconds: Long,
        updatedAt: String,
        targetKind: String,
        habitUid: String?,
        habitOccurredOn: String?,
    ) = ActiveFocusTimer(
        sessionUid = sessionUid,
        taskUid = taskUid,
        taskTitleSnapshot = taskTitleSnapshot,
        mode = FocusTimerMode.valueOf(mode),
        plannedDurationSeconds = plannedDurationSeconds,
        state = FocusTimerState.valueOf(state),
        startedAt = startedAt,
        runningSegmentStartedAt = runningSegmentStartedAt,
        accumulatedSeconds = accumulatedSeconds,
        updatedAt = updatedAt,
        target = if (targetKind == "HABIT") FocusTargetRef(FocusTargetKind.HABIT, requireNotNull(habitUid)) else null,
        habitOccurredOn = habitOccurredOn,
    )
}
