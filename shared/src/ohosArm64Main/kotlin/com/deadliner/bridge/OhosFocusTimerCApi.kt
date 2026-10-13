@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.task.TaskAction
import com.deadliner.platform.FocusAlarmPort
import com.deadliner.viewmodel.focus.FocusTimerUiState
import com.deadliner.viewmodel.focus.FocusTimerViewModel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Focus-specific HarmonyOS ABI kept separate from the legacy persistence bridge. */
private object OhosFocusTimerBridge {
    private val json = Json { encodeDefaults = true }
    private var pendingRequest: String? = null
    private var pendingResponse: String? = null

    fun stateJson(selectedTaskUid: String?): String? = runCatching {
        val viewModel = createViewModel()
        val state = runBlocking { viewModel.loadNow(reconcile = true) }
        if (!selectedTaskUid.isNullOrBlank()) {
            viewModel.selectTask(selectedTaskUid)
        }
        json.encodeToString(if (selectedTaskUid.isNullOrBlank()) state else viewModel.uiState.value)
    }.getOrNull()

    /** Typed target projection, without changing the legacy Task-only ABI. */
    fun targetStateJson(payload: String): String? = runCatching {
        val request = json.decodeFromString<FocusBridgeTarget>(payload)
        val viewModel = createViewModel()
        runBlocking { viewModel.loadNow(reconcile = true) }
        viewModel.selectTarget(FocusTargetRef(request.targetKind, request.targetUid))
        json.encodeToString(viewModel.uiState.value)
    }.getOrNull()

    fun overviewJson(): String? = runCatching {
        val database = requireNotNull(PersistenceRuntime.database) { "KMP persistence is not initialized" }
        json.encodeToString(database.focusStatistics.overview())
    }.getOrNull()

    fun commandJson(payload: String): String? {
        if (pendingRequest == payload) return pendingResponse
        val response = runCatching {
            val request = json.decodeFromString<FocusBridgeCommand>(payload)
            val viewModel = createViewModel()
            val state: FocusTimerUiState = runBlocking {
                when (request.operation) {
                    "select_session" -> {
                        viewModel.loadNow(reconcile = true)
                        viewModel.selectSession(requireNotNull(request.sessionUid))
                        viewModel.uiState.value
                    }
                    "resolve_habit_archive" -> viewModel.resolveHabitChangeNow(requireNotNull(request.habitUid), delete = false, request.sessionUid)
                    "resolve_habit_delete" -> viewModel.resolveHabitChangeNow(requireNotNull(request.habitUid), delete = true, request.sessionUid)
                    "complete_saved_task" -> viewModel.completeSavedTaskNow(requireNotNull(request.sessionUid))
                    "start_habit_stopwatch" -> viewModel.executeNow(FocusTimerCommand.StartHabit(requireNotNull(request.habitUid), FocusTimerMode.STOPWATCH))
                    "start_habit_countdown" -> viewModel.executeNow(FocusTimerCommand.StartHabit(requireNotNull(request.habitUid), FocusTimerMode.COUNTDOWN, requireNotNull(request.durationSeconds)))
                    "finish_and_record_habit" -> viewModel.executeNow(FocusTimerCommand.FinishAndRecordHabit(requireNotNull(request.sessionUid), request.amount, request.expectedHabitUpdatedAt))
                    "start_stopwatch" -> viewModel.executeNow(
                        FocusTimerCommand.Start(requireTaskUid(request), FocusTimerMode.STOPWATCH),
                    )
                    "start_countdown" -> viewModel.executeNow(
                        FocusTimerCommand.Start(
                            requireTaskUid(request),
                            FocusTimerMode.COUNTDOWN,
                            requireNotNull(request.durationSeconds) { "durationSeconds is required" },
                        ),
                    )
                    "pause" -> viewModel.executeNow(FocusTimerCommand.Pause)
                    "resume" -> viewModel.executeNow(FocusTimerCommand.Resume)
                    "finish" -> viewModel.executeNow(FocusTimerCommand.Finish)
                    "finish_and_complete" -> viewModel.executeNow(FocusTimerCommand.FinishAndCompleteTask)
                    "discard" -> viewModel.executeNow(FocusTimerCommand.Discard)
                    "reconcile" -> viewModel.loadNow(reconcile = true)
                    "adjust_session" -> viewModel.adjustSessionDurationNow(
                        requireNotNull(request.sessionUid) { "sessionUid is required" },
                        requireNotNull(request.durationSeconds) { "durationSeconds is required" },
                    )
                    "delete_session" -> viewModel.deleteSessionNow(
                        requireNotNull(request.sessionUid) { "sessionUid is required" },
                    )
                    "resolve_task_action" -> viewModel.resolveTaskActionNow(
                        requireTaskUid(request),
                        TaskAction.valueOf(requireNotNull(request.taskAction) { "taskAction is required" }),
                        request.sessionUid,
                    )
                    "resolve_task_delete" -> viewModel.resolveTaskDeletionNow(requireTaskUid(request), request.sessionUid)
                    else -> error("Unsupported focus operation: ${request.operation}")
                }
            }
            // A late joint check-in must not replace the new active timer's presentation target.
            if (state.timer == null && request.operation != "select_session") {
                val target = request.selectionTarget()
                if (target != null && state.selectedTarget != target) viewModel.selectTarget(target)
            }
            json.encodeToString(viewModel.uiState.value)
        }.getOrNull()
        pendingRequest = payload
        pendingResponse = response
        return response
    }

    fun clearPendingCommand() {
        pendingRequest = null
        pendingResponse = null
    }

    private fun createViewModel(): FocusTimerViewModel {
        val database = requireNotNull(PersistenceRuntime.database) { "KMP persistence is not initialized" }
        return FocusTimerViewModel(
            timerService = database.createFocusTimerService(FocusAlarmPort.NONE),
            sessionRepository = database.focusSessions,
            taskRepository = database.tasks,
            scope = CoroutineScope(Dispatchers.Default),
        )
    }

    private fun requireTaskUid(request: FocusBridgeCommand): String =
        requireNotNull(request.taskUid).also { require(it.isNotBlank()) { "taskUid is required" } }
}

@Serializable
private data class FocusBridgeCommand(
    val operation: String,
    val taskUid: String? = null,
    val durationSeconds: Long? = null,
    val sessionUid: String? = null,
    val taskAction: String? = null,
    val habitUid: String? = null,
    val amount: Int? = null,
    val expectedHabitUpdatedAt: String? = null,
    val targetKind: FocusTargetKind? = null,
    val targetUid: String? = null,
) {
    fun selectionTarget(): FocusTargetRef? = when {
        targetKind != null && !targetUid.isNullOrBlank() -> FocusTargetRef(targetKind, targetUid)
        !habitUid.isNullOrBlank() -> FocusTargetRef(FocusTargetKind.HABIT, habitUid)
        !taskUid.isNullOrBlank() -> FocusTargetRef(FocusTargetKind.TASK, taskUid)
        else -> null
    }
}

@Serializable
private data class FocusBridgeTarget(val targetKind: FocusTargetKind, val targetUid: String)

private fun writeFocusJson(
    payload: String?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    if (payload == null) return 0
    val bytes = payload.encodeToByteArray()
    val requiredCapacity = bytes.size + 1
    if (output == null || outputCapacity < requiredCapacity) return requiredCapacity
    bytes.forEachIndexed { index, value -> output[index] = value }
    output[bytes.size] = 0
    return requiredCapacity
}

@CName("deadliner_persistence_focus_state_json")
fun deadlinerPersistenceFocusStateJson(
    selectedTaskUid: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeFocusJson(
    OhosFocusTimerBridge.stateJson(selectedTaskUid?.toKString()?.takeIf(String::isNotBlank)),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_focus_target_state_json")
fun deadlinerPersistenceFocusTargetStateJson(
    targetPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeFocusJson(targetPayload?.toKString()?.let(OhosFocusTimerBridge::targetStateJson), output, outputCapacity)

@CName("deadliner_persistence_focus_overview_json")
fun deadlinerPersistenceFocusOverviewJson(output: CPointer<ByteVar>?, outputCapacity: Int): Int =
    writeFocusJson(OhosFocusTimerBridge.overviewJson(), output, outputCapacity)

@CName("deadliner_persistence_focus_command_json")
fun deadlinerPersistenceFocusCommandJson(
    requestPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = requestPayload?.toKString() ?: return 0
    val result = writeFocusJson(OhosFocusTimerBridge.commandJson(payload), output, outputCapacity)
    if (output != null && result > 0 && outputCapacity >= result) {
        OhosFocusTimerBridge.clearPendingCommand()
    }
    return result
}
