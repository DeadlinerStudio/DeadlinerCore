package com.deadliner.viewmodel.focus

import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusHabitRecordContext
import com.deadliner.domain.focus.ActiveFocusTimer
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerEngine
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.focus.FocusTimerSnapshot
import com.deadliner.domain.focus.FocusTimerWarning
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskState
import com.deadliner.service.focus.FocusTimerResult
import com.deadliner.service.focus.FocusTimerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Shared typed-target focus timer presentation authority for every client. */
class FocusTimerViewModel(
    private val timerService: FocusTimerService,
    private val sessionRepository: FocusSessionRepository,
    private val taskRepository: TaskRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val _uiState = MutableStateFlow(FocusTimerUiState())
    val uiState: StateFlow<FocusTimerUiState> = _uiState.asStateFlow()
    private var selectedTarget: FocusTargetRef? = null
    private var activeTimer: ActiveFocusTimer? = null
    private var monotonicAnchor: TimeMark? = null
    private var wallClockAnchor: Instant? = null

    fun initialize() {
        scope.launch { loadNow(reconcile = true) }
    }

    fun refresh() {
        scope.launch { loadNow(reconcile = false) }
    }

    /** Recomputes display time from persisted anchors without writing or querying session statistics. */
    fun tick() {
        val active = activeTimer ?: return
        val anchor = monotonicAnchor
        val wallAnchor = wallClockAnchor
        val now = if (anchor != null && wallAnchor != null) {
            Instant.fromEpochMilliseconds(
                wallAnchor.toEpochMilliseconds() + anchor.elapsedNow().inWholeMilliseconds,
            )
        } else {
            nowProvider()
        }
        if (FocusTimerEngine.calculate(active, now).countdownElapsed) {
            if (!_uiState.value.isBusy) submit(FocusTimerCommand.Reconcile)
            return
        }
        val snapshot = FocusTimerEngine.snapshot(
            active = active,
            now = now,
            taskTitle = _uiState.value.timer
                ?.takeIf { it.taskUid == active.taskUid }
                ?.taskTitle
                ?: active.taskTitleSnapshot,
            taskIsActive = true,
        )
        _uiState.update {
            it.copy(
                timer = snapshot.copy(habitRecordContext = it.timer?.habitRecordContext,
                    canFinishAndRecord = snapshot.canFinish && it.timer?.habitRecordContext?.canRecord == true),
                warning = snapshot.warning,
            )
        }
    }

    fun start(taskUid: String, mode: FocusTimerMode, plannedDurationSeconds: Long? = null) {
        submit(FocusTimerCommand.Start(taskUid, mode, plannedDurationSeconds))
    }

    fun startHabit(habitUid: String, mode: FocusTimerMode, plannedDurationSeconds: Long? = null) =
        submit(FocusTimerCommand.StartHabit(habitUid, mode, plannedDurationSeconds))

    fun finishAndRecordHabit(sessionUid: String, amount: Int? = null, expectedHabitUpdatedAt: String? = null) =
        submit(FocusTimerCommand.FinishAndRecordHabit(sessionUid, amount, expectedHabitUpdatedAt))

    fun pause() = submit(FocusTimerCommand.Pause)

    fun resume() = submit(FocusTimerCommand.Resume)

    fun finish() = submit(FocusTimerCommand.Finish)

    fun finishAndCompleteTask() = submit(FocusTimerCommand.FinishAndCompleteTask)

    fun completeSavedTask(sessionUid: String) {
        if (!beginOperation()) return
        scope.launch { completeSavedTaskNow(sessionUid) }
    }

    fun discard() = submit(FocusTimerCommand.Discard)

    fun selectTask(taskUid: String?) {
        if (_uiState.value.isBusy) return
        selectedTarget = taskUid?.takeIf { it.isNotBlank() }?.let { FocusTargetRef(FocusTargetKind.TASK, it) }
        refreshStatistics(nowProvider(), taskUid)
    }

    fun selectTarget(target: FocusTargetRef?) {
        if (_uiState.value.isBusy) return
        selectedTarget = target
        refreshStatistics(nowProvider(), target?.takeIf { it.kind == FocusTargetKind.TASK }?.uid)
    }

    fun selectSession(sessionUid: String) {
        if (_uiState.value.isBusy || activeTimer != null) return
        val session = sessionRepository.find(sessionUid)?.takeUnless { it.isDeleted } ?: return
        selectedTarget = session.boundTarget
        refreshStatistics(nowProvider(), session.taskUid.takeIf { it.isNotBlank() }, lastCompletedSession = session)
    }

    fun resolveTaskAction(taskUid: String, action: TaskAction) {
        if (!beginOperation()) return
        scope.launch { resolveTaskActionNow(taskUid, action) }
    }

    fun resolveTaskDeletion(taskUid: String) {
        if (!beginOperation()) return
        scope.launch { resolveTaskDeletionNow(taskUid) }
    }

    /** Platform confirmation path; preserves the service's atomic focus + Habit mutation. */
    fun resolveHabitChange(habitUid: String, delete: Boolean) {
        if (!beginOperation()) return
        scope.launch { resolveHabitChangeNow(habitUid, delete) }
    }

    fun adjustSessionDuration(sessionUid: String, durationSeconds: Long) {
        scope.launch { adjustSessionDurationNow(sessionUid, durationSeconds) }
    }

    fun deleteSession(sessionUid: String) {
        scope.launch { deleteSessionNow(sessionUid) }
    }

    suspend fun loadNow(reconcile: Boolean = true): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isLoading = true, error = null) }
        return runCatching {
            val result = if (reconcile) {
                timerService.execute(FocusTimerCommand.Reconcile, now)
            } else {
                timerService.current(now)
            }
            val recovered = if (result.activeTimer == null && result.session == null && _uiState.value.lastCompletedSession == null) {
                result.copy(session = sessionRepository.latestForNotification()?.takeUnless { it.isDeleted })
            } else result
            publish(recovered, now)
        }.getOrElse(::publishError)
    }

    suspend fun executeNow(
        command: FocusTimerCommand,
        expectedSessionUid: String? = null,
        expectedUpdatedAt: String? = null,
    ): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching { publish(timerService.execute(command, now, expectedSessionUid, expectedUpdatedAt), now) }
            .getOrElse(::publishError)
    }

    suspend fun resolveTaskActionNow(taskUid: String, action: TaskAction, expectedSessionUid: String? = null): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching { publish(timerService.resolveTaskAction(taskUid, action, now, expectedSessionUid), now) }
            .getOrElse(::publishError)
    }

    suspend fun resolveTaskDeletionNow(taskUid: String, expectedSessionUid: String? = null): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching { publish(timerService.resolveTaskDeletion(taskUid, now, expectedSessionUid), now) }
            .getOrElse(::publishError)
    }

    suspend fun resolveHabitChangeNow(habitUid: String, delete: Boolean, expectedSessionUid: String? = null): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching { publish(timerService.resolveHabitChange(habitUid, delete, now, expectedSessionUid), now) }
            .getOrElse(::publishError)
    }

    suspend fun completeSavedTaskNow(sessionUid: String): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching { publish(timerService.completeSavedTask(sessionUid, now), now) }.getOrElse(::publishError)
    }

    suspend fun adjustSessionDurationNow(sessionUid: String, durationSeconds: Long): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching {
            val mutation = sessionRepository.adjustDuration(sessionUid, durationSeconds, now.toString())
            mutation.session?.let { selectedTarget = it.boundTarget }
            refreshStatistics(
                now = now,
                selectedTaskUid = mutation.session?.taskUid ?: _uiState.value.selectedTaskUid,
                outcome = mutation.outcome,
            )
        }.getOrElse(::publishError)
    }

    suspend fun deleteSessionNow(sessionUid: String): FocusTimerUiState {
        val now = nowProvider()
        _uiState.update { it.copy(isBusy = true, error = null) }
        return runCatching {
            val mutation = sessionRepository.delete(sessionUid, now.toString())
            mutation.session?.let { selectedTarget = it.boundTarget }
            refreshStatistics(
                now = now,
                selectedTaskUid = mutation.session?.taskUid ?: _uiState.value.selectedTaskUid,
                outcome = mutation.outcome,
            )
        }.getOrElse(::publishError)
    }

    private fun submit(command: FocusTimerCommand) {
        if (!beginOperation()) return
        scope.launch { executeNow(command) }
    }

    private fun beginOperation(): Boolean {
        if (_uiState.value.isBusy) return false
        _uiState.update { it.copy(isBusy = true, error = null) }
        return true
    }

    private fun publish(result: FocusTimerResult, now: Instant): FocusTimerUiState {
        activeTimer = result.activeTimer
        monotonicAnchor = result.activeTimer?.let { TimeSource.Monotonic.markNow() }
        wallClockAnchor = result.activeTimer?.let { now }
        selectedTarget = result.activeTimer?.boundTarget ?: result.session?.boundTarget ?: selectedTarget
        val selectedTaskUid = if (selectedTarget?.kind == FocusTargetKind.HABIT) null else result.snapshot?.taskUid
            ?: result.session?.taskUid
            ?: result.task?.uid
            ?: _uiState.value.selectedTaskUid
        return refreshStatistics(
            now = now,
            selectedTaskUid = selectedTaskUid,
            timer = result.snapshot,
            lastCompletedSession = result.session ?: _uiState.value.lastCompletedSession.takeIf { result.activeTimer == null },
            outcome = result.outcome,
            warning = result.warning ?: result.snapshot?.warning,
        )
    }

    private fun refreshStatistics(
        now: Instant,
        selectedTaskUid: String?,
        timer: FocusTimerSnapshot? = _uiState.value.timer,
        lastCompletedSession: FocusSession? = _uiState.value.lastCompletedSession,
        outcome: FocusTimerOutcome? = _uiState.value.lastOutcome,
        warning: FocusTimerWarning? = _uiState.value.warning,
    ): FocusTimerUiState {
        val range = focusLocalDayRange(now, timeZoneProvider())
        val task = selectedTaskUid?.let(taskRepository::find)
        val taskRecentSessions = if (selectedTarget?.kind == FocusTargetKind.HABIT) sessionRepository.forTarget(selectedTarget!!).take(RECENT_TASK_SESSION_LIMIT) else selectedTaskUid?.let {
            sessionRepository.recentForTask(it, RECENT_TASK_SESSION_LIMIT)
        }.orEmpty()
        val state = FocusTimerUiState(
            timer = timer,
            todayDurationSeconds = sessionRepository.totalStartedInRange(range.start.toString(), range.end.toString()),
            todaySessionCount = sessionRepository
                .countStartedInRange(range.start.toString(), range.end.toString())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            recentSessions = sessionRepository.recent(RECENT_SESSION_LIMIT),
            selectedTaskUid = selectedTaskUid,
            selectedTarget = selectedTarget,
            habitRecordContext = timer?.habitRecordContext ?: lastCompletedSession?.let { timerService.habitRecordContext(it, now) },
            selectedTaskTitle = timer?.taskTitle ?: task?.title
                ?: taskRecentSessions.firstOrNull()?.taskTitleSnapshot
                ?: lastCompletedSession?.takeIf { it.taskUid == selectedTaskUid }?.taskTitleSnapshot,
            selectedTaskTodayDurationSeconds = if (selectedTarget?.kind == FocusTargetKind.HABIT) sessionRepository.forTarget(selectedTarget!!).filter { Instant.parse(it.startedAt) >= range.start && Instant.parse(it.startedAt) < range.end }.sumOf { it.durationSeconds } else selectedTaskUid?.let {
                sessionRepository.totalForTaskStartedInRange(it, range.start.toString(), range.end.toString())
            } ?: 0,
            selectedTaskTotalDurationSeconds = if (selectedTarget?.kind == FocusTargetKind.HABIT) sessionRepository.forTarget(selectedTarget!!).sumOf { it.durationSeconds } else selectedTaskUid?.let(sessionRepository::totalForTask) ?: 0,
            selectedTaskRecentSessions = taskRecentSessions,
            lastCompletedSession = lastCompletedSession,
            lastCompletedTaskState = lastCompletedSession?.takeIf { !it.isDeleted && it.boundTarget.kind == FocusTargetKind.TASK }
                ?.let { sessionRepository.find(it.uid)?.takeUnless { saved -> saved.isDeleted } }
                ?.let { taskRepository.find(it.boundTarget.uid)?.takeUnless { target -> target.isDeleted }?.state },
            canCompleteLastTask = lastCompletedSession?.takeIf { it.boundTarget.kind == FocusTargetKind.TASK && !it.isDeleted }
                ?.let { sessionRepository.find(it.uid)?.takeUnless { saved -> saved.isDeleted } }
                ?.let { saved -> taskRepository.find(saved.boundTarget.uid)?.let { !it.isDeleted && it.state == TaskState.ACTIVE && timer?.target != saved.boundTarget && timer?.taskUid != saved.taskUid } } == true,
            lastOutcome = outcome,
            warning = warning,
            isLoading = false,
            isBusy = false,
        )
        _uiState.value = state
        return state
    }

    private fun publishError(error: Throwable): FocusTimerUiState {
        val state = _uiState.value.copy(
            isLoading = false,
            isBusy = false,
            error = error.message ?: error::class.simpleName,
        )
        _uiState.value = state
        return state
    }

    private companion object {
        const val RECENT_SESSION_LIMIT = 20
        const val RECENT_TASK_SESSION_LIMIT = 10
    }
}

@Serializable
data class FocusTimerUiState(
    val timer: FocusTimerSnapshot? = null,
    val selectedTarget: FocusTargetRef? = null,
    val habitRecordContext: FocusHabitRecordContext? = null,
    val todayDurationSeconds: Long = 0,
    val todaySessionCount: Int = 0,
    val recentSessions: List<FocusSession> = emptyList(),
    val selectedTaskUid: String? = null,
    val selectedTaskTitle: String? = null,
    val selectedTaskTodayDurationSeconds: Long = 0,
    val selectedTaskTotalDurationSeconds: Long = 0,
    val selectedTaskRecentSessions: List<FocusSession> = emptyList(),
    val lastCompletedSession: FocusSession? = null,
    val lastCompletedTaskState: TaskState? = null,
    val canCompleteLastTask: Boolean = false,
    val lastOutcome: FocusTimerOutcome? = null,
    val warning: FocusTimerWarning? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
)

internal data class FocusDayRange(val start: Instant, val end: Instant)

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun focusLocalDayRange(now: Instant, timeZone: TimeZone): FocusDayRange {
    val today = now.toLocalDateTime(timeZone).date
    val tomorrow = today.plus(DatePeriod(days = 1))
    val modernStart = LocalDateTime.parse("${today}T00:00:00").toInstant(timeZone)
    val modernEnd = LocalDateTime.parse("${tomorrow}T00:00:00").toInstant(timeZone)
    return FocusDayRange(
        start = Instant.fromEpochMilliseconds(modernStart.toEpochMilliseconds()),
        end = Instant.fromEpochMilliseconds(modernEnd.toEpochMilliseconds()),
    )
}
