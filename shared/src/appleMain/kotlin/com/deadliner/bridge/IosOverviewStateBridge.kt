package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.viewmodel.overview.OverviewMonthUiState
import com.deadliner.viewmodel.overview.OverviewRangeUiState
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.statistics.FocusStatisticsQuery
import com.deadliner.domain.statistics.FocusStatisticsSnapshot
import com.deadliner.viewmodel.focus.FocusTimerViewModel
import com.deadliner.platform.FocusAlarmPort
import kotlinx.coroutines.CancellationException
import com.deadliner.viewmodel.overview.OverviewUiState
import com.deadliner.viewmodel.overview.OverviewViewModel
import com.deadliner.platform.PlatformLogger
import com.deadliner.domain.statistics.FocusOverviewStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owns Overview StateFlow collection so Swift never has to manage coroutines. */
class IosOverviewStateBridge(
    private val database: DeadlinerDatabase,
) : AutoCloseable {
    /** Repository reads and chart aggregation run away from the UI thread. */
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** Swift callbacks remain main-thread confined. */
    private val callbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = OverviewViewModel(database.tasks, database.habits, workerScope)
    private var observationStarted = false

    /**
     * Loads one snapshot off the UI thread and returns it on the main thread.
     * This deliberately stays one-shot: Swift owns the page lifecycle and
     * does not need a long-lived StateFlow collector for Overview.
     */
    fun load(onState: (OverviewUiState) -> Unit) {
        workerScope.launch {
            overviewBridgeLog("snapshot load requested")
            val state = viewModel.loadNow()
            callbackScope.launch { onState(state) }
        }
    }

    fun start(onState: (OverviewUiState) -> Unit) {
        overviewBridgeLog("start observationStarted=$observationStarted")
        if (!observationStarted) {
            observationStarted = true
            callbackScope.launch {
                viewModel.uiState.collect { state ->
                    overviewBridgeLog(
                        "state loading=${state.isLoading} daily=${state.daily.size} monthly=${state.monthly.size} contribution=${state.contribution.size} error=${state.error}",
                    )
                    onState(state)
                }
            }
        }
        overviewBridgeLog("initialize requested")
        viewModel.initialize()
    }

    fun refresh() {
        overviewBridgeLog("refresh requested")
        viewModel.refresh()
    }

    /** Separate focus projection keeps task completion metrics unchanged. */
    fun loadFocus(onState: (FocusOverviewStatistics) -> Unit) {
        workerScope.launch {
            val state = database.focusStatistics.overview()
            callbackScope.launch { onState(state) }
        }
    }

    /** Each domain has its own failure callback; no failed read becomes a fake zero. */
    fun loadMonth(monthStart: String, onState: (OverviewMonthUiState) -> Unit, onError: (String) -> Unit) {
        workerScope.launch {
            try {
                val state = viewModel.loadMonthNow(monthStart)
                callbackScope.launch { onState(state) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onError(error.message ?: "月度数据加载失败") } }
        }
    }

    /** Custom local date range `[startDate, endDateExclusive)`; invalid ranges report through `onError`. */
    fun loadRange(startDate: String, endDateExclusive: String, onState: (OverviewRangeUiState) -> Unit, onError: (String) -> Unit) {
        workerScope.launch {
            try {
                val state = viewModel.loadRangeNow(startDate, endDateExclusive)
                callbackScope.launch { onState(state) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onError(error.message ?: "时间范围数据加载失败") } }
        }
    }

    fun loadFocusSafely(onState: (FocusOverviewStatistics) -> Unit, onError: (String) -> Unit) {
        workerScope.launch {
            try {
                val state = database.focusStatistics.overview()
                callbackScope.launch { onState(state) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onError(error.message ?: "专注数据加载失败") } }
        }
    }

    fun loadFocusManagement(onState: (List<FocusSession>, FocusStatisticsSnapshot) -> Unit, onError: (String) -> Unit) {
        workerScope.launch {
            try {
                val sessions = database.focusSessions.list().filterNot { it.isDeleted }.sortedByDescending { it.startedAt }
                val statistics = database.focusStatistics.snapshot(FocusStatisticsQuery())
                callbackScope.launch { onState(sessions, statistics) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onError(error.message ?: "专注记录加载失败") } }
        }
    }

    /** One transaction supplies the management page and all of its derived history pages. */
    fun loadFocusManagementSnapshot(
        onState: (com.deadliner.domain.focus.management.FocusManagementSnapshot) -> Unit,
        onError: (String) -> Unit,
    ) {
        workerScope.launch {
            try {
                val snapshot = com.deadliner.db.repository.focus.FocusManagementRepository(database).snapshot()
                callbackScope.launch { onState(snapshot) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onError(error.message ?: "专注记录加载失败") } }
        }
    }

    /** Reuse the timer use cases, including downward-only adjustment and soft deletion. */
    fun mutateFocusSession(sessionUid: String, durationSeconds: Long?, onResult: (FocusTimerOutcome, String?) -> Unit) {
        workerScope.launch {
            try {
                val model = FocusTimerViewModel(database.createFocusTimerService(FocusAlarmPort.NONE), database.focusSessions, database.tasks, workerScope)
                val state = if (durationSeconds == null) model.deleteSessionNow(sessionUid)
                    else model.adjustSessionDurationNow(sessionUid, durationSeconds)
                callbackScope.launch { onResult(state.lastOutcome ?: FocusTimerOutcome.INVALID_STATE, state.error) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { callbackScope.launch { onResult(FocusTimerOutcome.INVALID_STATE, error.message) } }
        }
    }

    override fun close() {
        overviewBridgeLog("close")
        callbackScope.cancel()
        workerScope.cancel()
    }
}

private fun overviewBridgeLog(message: String) {
    PlatformLogger().d("overview-bridge", message)
}
