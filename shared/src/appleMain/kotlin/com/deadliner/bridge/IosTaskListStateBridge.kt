package com.deadliner.bridge

import com.deadliner.db.repository.TaskRepository
import com.deadliner.service.NotificationOrchestrator
import com.deadliner.platform.WidgetUpdateReason
import com.deadliner.platform.updateWidget
import com.deadliner.viewmodel.task.TaskListUiState
import com.deadliner.viewmodel.task.TaskListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * iOS-facing lifecycle bridge for [TaskListViewModel].
 *
 * Kotlin/Native exposes [TaskListViewModel.uiState] as a generic `StateFlow`
 * protocol, which would otherwise make Swift implement coroutine collector
 * machinery. This bridge owns that scope and forwards each typed state to a
 * Swift closure. Call [close] when the Swift owner is released.
 */
class IosTaskListStateBridge(
    taskRepository: TaskRepository,
    notificationOrchestrator: NotificationOrchestrator? = null,
) : AutoCloseable {
    /** SQLite reads and list materialisation must never occupy the iOS UI thread. */
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** SwiftUI state delivery remains main-thread confined. */
    private val callbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = TaskListViewModel(
        taskRepository = taskRepository,
        scope = workerScope,
        notificationOrchestrator = notificationOrchestrator,
    )
    private var observationStarted = false

    /** Starts typed state delivery and performs the initial repository read. */
    fun start(onState: (TaskListUiState) -> Unit) {
        if (!observationStarted) {
            observationStarted = true
            callbackScope.launch {
                viewModel.uiState.collect { state -> onState(state) }
            }
        }
        viewModel.initialize()
    }

    /** Re-reads the repository after a write performed outside this ViewModel. */
    fun refresh() {
        viewModel.initialize()
        updateWidget(WidgetUpdateReason.USER_REFRESH)
    }

    override fun close() {
        callbackScope.cancel()
        workerScope.cancel()
    }
}
