package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.task.TaskAction
import com.deadliner.platform.FocusAlarmPort
import com.deadliner.viewmodel.focus.FocusTimerUiState
import com.deadliner.viewmodel.focus.FocusTimerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.habit.HabitActionOutcome
import com.deadliner.domain.habit.HabitStatusAction

/** Owns Focus StateFlow collection and command dispatch for Swift clients. */
class IosFocusTimerStateBridge(
    private val database: DeadlinerDatabase,
    alarmPort: FocusAlarmPort,
) : AutoCloseable {
    constructor(database: DeadlinerDatabase) : this(database, FocusAlarmPort.NONE)

    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val callbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = FocusTimerViewModel(
        timerService = database.createFocusTimerService(alarmPort),
        sessionRepository = database.focusSessions,
        taskRepository = database.tasks,
        scope = workerScope,
    )
    private var observationStarted = false

    /** Starts typed state delivery and reconciles any persisted timer. */
    fun start(onState: (FocusTimerUiState) -> Unit) {
        if (!observationStarted) {
            observationStarted = true
            callbackScope.launch {
                viewModel.uiState.collect(onState)
            }
        }
        viewModel.initialize()
    }

    fun refresh() = viewModel.refresh()

    fun reconcile() = viewModel.initialize()

    /** Safe to call once per visible second; the normal path performs no database write. */
    fun tick() = viewModel.tick()

    fun startStopwatch(taskUid: String) = viewModel.start(taskUid, FocusTimerMode.STOPWATCH)

    fun startHabitStopwatch(habitUid: String) = viewModel.startHabit(habitUid, FocusTimerMode.STOPWATCH)

    fun startHabitCountdown(habitUid: String, plannedDurationSeconds: Long) =
        viewModel.startHabit(habitUid, FocusTimerMode.COUNTDOWN, plannedDurationSeconds)

    fun finishAndRecordHabit(sessionUid: String, amount: Int?, expectedHabitUpdatedAt: String?) =
        viewModel.finishAndRecordHabit(sessionUid, amount, expectedHabitUpdatedAt)

    fun startCountdown(taskUid: String, plannedDurationSeconds: Long) =
        viewModel.start(taskUid, FocusTimerMode.COUNTDOWN, plannedDurationSeconds)

    fun pause() = viewModel.pause()

    fun resume() = viewModel.resume()

    fun finish() = viewModel.finish()

    fun finishAndCompleteTask() = viewModel.finishAndCompleteTask()

    fun completeSavedTask(sessionUid: String) = viewModel.completeSavedTask(sessionUid)

    fun resolveConfirmedChange(targetUid: String, habit: Boolean, delete: Boolean, taskAction: TaskAction?, sessionUid: String,
        onResult: (FocusTimerOutcome) -> Unit) {
        workerScope.launch {
            val state = when {
                habit -> viewModel.resolveHabitChangeNow(targetUid, delete, sessionUid)
                delete -> viewModel.resolveTaskDeletionNow(targetUid, sessionUid)
                taskAction != null -> viewModel.resolveTaskActionNow(targetUid, taskAction, sessionUid)
                else -> null
            }
            callbackScope.launch { onResult(state?.lastOutcome ?: FocusTimerOutcome.INVALID_STATE) }
        }
    }

    fun discard() = viewModel.discard()

    fun selectTask(taskUid: String?) = viewModel.selectTask(taskUid)

    fun selectHabit(habitUid: String?) = viewModel.selectTarget(
        habitUid?.takeIf(String::isNotBlank)?.let { FocusTargetRef(FocusTargetKind.HABIT, it) },
    )

    fun selectSession(sessionUid: String) = viewModel.selectSession(sessionUid)

    fun resolveHabitChange(habitUid: String, delete: Boolean) = viewModel.resolveHabitChange(habitUid, delete)

    /** Catch repository mutation guards before crossing the Swift boundary. */
    fun mutateHabitIfFocusResolved(habitUid: String, delete: Boolean, onResult: (FocusTimerOutcome) -> Unit) {
        workerScope.launch {
            val outcome = runCatching {
                val now = Clock.System.now().toString()
                if (delete) {
                    database.habits.delete(habitUid, now)
                    FocusTimerOutcome.APPLIED
                } else when (database.habits.applyStatusAction(habitUid, HabitStatusAction.ARCHIVE, now).outcome) {
                    HabitActionOutcome.APPLIED, HabitActionOutcome.NO_OP -> FocusTimerOutcome.APPLIED
                    HabitActionOutcome.NOT_FOUND -> FocusTimerOutcome.HABIT_NOT_FOUND
                    else -> FocusTimerOutcome.INVALID_STATE
                }
            }.getOrElse { error ->
                if (error is IllegalStateException) FocusTimerOutcome.TIMER_REQUIRES_RESOLUTION
                else FocusTimerOutcome.INVALID_STATE
            }
            callbackScope.launch { onResult(outcome) }
        }
    }

    fun resolveTaskAction(taskUid: String, action: TaskAction) =
        viewModel.resolveTaskAction(taskUid, action)

    fun resolveTaskDeletion(taskUid: String) = viewModel.resolveTaskDeletion(taskUid)

    fun adjustSessionDuration(sessionUid: String, durationSeconds: Long) =
        viewModel.adjustSessionDuration(sessionUid, durationSeconds)

    fun deleteSession(sessionUid: String) = viewModel.deleteSession(sessionUid)

    override fun close() {
        callbackScope.cancel()
        workerScope.cancel()
    }
}
