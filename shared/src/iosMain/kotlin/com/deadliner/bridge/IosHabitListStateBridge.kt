package com.deadliner.bridge

import com.deadliner.db.repository.HabitRepository
import com.deadliner.service.NotificationOrchestrator
import com.deadliner.viewmodel.habit.HabitListUiState
import com.deadliner.viewmodel.habit.HabitListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * iOS-facing lifecycle bridge for [HabitListViewModel].
 *
 * It delivers typed `HabitListUiState` values to Swift while owning the
 * coroutine collector required by Kotlin/Native's generic StateFlow API.
 */
class IosHabitListStateBridge(
    habitRepository: HabitRepository,
    notificationOrchestrator: NotificationOrchestrator? = null,
) : AutoCloseable {
    /** Keep synchronous SQLDelight reads off the iOS main thread. */
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** The exported Swift callback must continue to run on the main thread. */
    private val callbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = HabitListViewModel(
        habitRepository = habitRepository,
        scope = workerScope,
        notificationOrchestrator = notificationOrchestrator,
    )
    private var observationStarted = false

    fun start(onState: (HabitListUiState) -> Unit) {
        if (!observationStarted) {
            observationStarted = true
            callbackScope.launch {
                viewModel.uiState.collect { state -> onState(state) }
            }
        }
        viewModel.initialize()
    }

    fun refresh() {
        viewModel.initialize()
    }

    override fun close() {
        callbackScope.cancel()
        workerScope.cancel()
    }
}
