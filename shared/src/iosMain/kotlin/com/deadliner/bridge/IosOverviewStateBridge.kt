package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.viewmodel.overview.OverviewUiState
import com.deadliner.viewmodel.overview.OverviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owns Overview StateFlow collection so Swift never has to manage coroutines. */
class IosOverviewStateBridge(
    database: DeadlinerDatabase,
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

    override fun close() {
        overviewBridgeLog("close")
        callbackScope.cancel()
        workerScope.cancel()
    }
}

private fun overviewBridgeLog(message: String) {
    println("[KMP][OverviewBridge] $message")
}
