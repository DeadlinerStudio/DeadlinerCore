package com.deadliner.db.repository.focus

import com.deadliner.db.AppDatabase
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
    )
}
