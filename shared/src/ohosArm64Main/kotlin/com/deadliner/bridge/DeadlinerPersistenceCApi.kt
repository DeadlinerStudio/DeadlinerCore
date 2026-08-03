@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.OhosDatabaseDriverFactory
import com.deadliner.sync.WebDavChangeLogSyncOutcome
import com.deadliner.sync.webdav.WebDavCredentials
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import com.deadliner.viewmodel.task.TaskListViewModel
import com.deadliner.viewmodel.habit.HabitListViewModel
import com.deadliner.viewmodel.capture.CaptureViewModel
import com.deadliner.viewmodel.overview.OverviewViewModel
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitStatusAction
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.ai.model.CoreEvent
import com.deadliner.lifi.OhosLifiCore
import com.deadliner.lifi.OhosLifiEventListener
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Small C ABI for the HarmonyOS NAPI module.
 *
 * Return values are intentionally primitive so this boundary stays stable while snapshot import
 * and repository APIs are introduced in later migration phases.
 */
private object PersistenceRuntime {
    var database: DeadlinerDatabase? = null
    private var pendingLegacyV2Merge: LegacyV2MergeCache? = null
    private var pendingChangeLogSync: ChangeLogSyncCache? = null
    private var lifiCore: OhosLifiCore? = null
    private var lifiSignature: String? = null
    private val lifiEvents = mutableListOf<CoreEvent>()

    fun initialize(directory: String, installationId: String): Int = try {
        require(directory.startsWith('/')) { "database directory must be absolute" }
        require(installationId.isNotBlank()) { "installation ID must not be blank" }
        closeLifiCore()
        database?.close()
        database = DeadlinerDatabase.create(OhosDatabaseDriverFactory(directory, installationId))
        pendingLegacyV2Merge = null
        pendingChangeLogSync = null
        1
    } catch (_: Throwable) {
        database = null
        0
    }

    fun close(): Int {
        closeLifiCore()
        database?.close()
        database = null
        pendingLegacyV2Merge = null
        pendingChangeLogSync = null
        return 1
    }

    suspend fun processLifiInput(configPayload: String, text: String): Int = try {
        require(text.isNotBlank()) { "AI input must not be blank" }
        val core = ensureLifiCore(configPayload)
        core.processInput(text)
        1
    } catch (error: Throwable) {
        lifiEvents += CoreEvent.OnError(error.message ?: "KMP Lifi input failed")
        0
    }

    suspend fun submitLifiToolResult(toolCallId: String, tool: String, payload: String): Int = try {
        require(toolCallId.isNotBlank()) { "tool call ID must not be blank" }
        require(tool.isNotBlank()) { "tool name must not be blank" }
        val core = lifiCore ?: error("KMP Lifi has not been initialized")
        core.submitToolResult(toolCallId, tool, payload)
        1
    } catch (error: Throwable) {
        lifiEvents += CoreEvent.OnError(error.message ?: "KMP Lifi tool continuation failed")
        0
    }

    suspend fun extractLifiTasksJson(configPayload: String, text: String): String? = try {
        require(text.isNotBlank()) { "AI extraction input must not be blank" }
        bridgeJson.encodeToString(ensureLifiCore(configPayload).extractTasks(text))
    } catch (_: Throwable) {
        null
    }

    fun drainLifiEventsJson(): String = bridgeJson.encodeToString(
        lifiEvents.map(::lifiEventJson).also { lifiEvents.clear() },
    )

    fun lifiPendingEventCount(): Int = lifiEvents.size

    fun resetLifi(): Int {
        closeLifiCore()
        return 1
    }

    private fun ensureLifiCore(configPayload: String): OhosLifiCore {
        val config = bridgeJson.decodeFromString<LifiBridgeConfig>(configPayload)
        require(config.apiKey.isNotBlank()) { "请先在设置中填写 API Key" }
        val signature = listOf(config.apiKey, config.baseUrl, config.model, config.timezone).joinToString("\u001F")
        if (lifiCore != null && lifiSignature == signature) return requireNotNull(lifiCore)

        closeLifiCore()
        val activeDatabase = database ?: error("KMP persistence is not initialized")
        val newCore = OhosLifiCore.create(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model,
            timezone = config.timezone,
            memoryRepository = activeDatabase.memory,
            profileRepository = activeDatabase.profiles,
        )
        newCore.start(object : OhosLifiEventListener {
            override fun onEvent(event: CoreEvent) {
                lifiEvents += event
            }
        })
        lifiCore = newCore
        lifiSignature = signature
        return newCore
    }

    private fun closeLifiCore() {
        lifiCore?.close()
        lifiCore = null
        lifiSignature = null
        lifiEvents.clear()
    }

    fun importLegacyV2(taskPayload: String, habitPayload: String): Int = try {
        val activeDatabase = database ?: return -1
        val result = activeDatabase.legacyV2Sync.mergeRemotePayloads(
            taskPayload.takeIf(String::isNotBlank),
            habitPayload.takeIf(String::isNotBlank),
            null,
        )
        if (result.failureMessage == null) result.remoteApplied else -1
    } catch (_: Throwable) {
        -1
    }

    fun resolveLegacyV2PayloadsJson(taskPayload: String, habitPayload: String): String? {
        val pending = pendingLegacyV2Merge
        if (pending != null && pending.taskPayload == taskPayload && pending.habitPayload == habitPayload) {
            return pending.responsePayload
        }
        val response = try {
            val activeDatabase = database ?: return null
        val result = activeDatabase.legacyV2Sync.mergeRemotePayloads(
            taskPayload.takeIf(String::isNotBlank),
            habitPayload.takeIf(String::isNotBlank),
            null,
        )
        if (result.failureMessage == null) bridgeJson.encodeToString(result) else null
        } catch (_: Throwable) {
            null
        }
        if (response != null) {
            pendingLegacyV2Merge = LegacyV2MergeCache(taskPayload, habitPayload, response)
        }
        return response
    }

    fun clearPendingLegacyV2Merge() {
        pendingLegacyV2Merge = null
    }

    fun resolveChangeLogSyncJson(baseUrl: String, username: String, password: String): String? {
        val pending = pendingChangeLogSync
        if (pending != null && pending.baseUrl == baseUrl && pending.username == username && pending.password == password) {
            return pending.responsePayload
        }
        val response = try {
            val activeDatabase = database ?: return null
            val result: WebDavChangeLogSyncOutcome = runBlocking {
                activeDatabase.webDavChangeLogSync.synchronizeSafely(
                    WebDavTransportConfiguration(
                        baseUrl = baseUrl,
                        credentials = WebDavCredentials(
                            username = username.takeIf(String::isNotBlank),
                            password = password.takeIf(String::isNotBlank),
                        ),
                        requestTimeoutMillis = 15_000,
                    ),
                )
            }
            bridgeJson.encodeToString(result)
        } catch (_: Throwable) {
            null
        }
        if (response != null) pendingChangeLogSync = ChangeLogSyncCache(baseUrl, username, password, response)
        return response
    }

    fun clearPendingChangeLogSync() {
        pendingChangeLogSync = null
    }

    /** Reads the actual KMP task-list ViewModel state; ArkTS receives only its serializable DTO. */
    fun taskListViewModelJson(): String? = try {
        val activeDatabase = database ?: return null
        val viewModel = TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default))
        viewModel.initialize()
        val state = runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.isLoading } } }
        bridgeJson.encodeToString(state.tasks)
    } catch (_: Throwable) {
        null
    }

    /** HarmonyOS receives the same one-shot overview state consumed by the iOS bridge. */
    fun overviewViewModelJson(): String? = try {
        val activeDatabase = database ?: return null
        bridgeJson.encodeToString(OverviewViewModel(activeDatabase.tasks, activeDatabase.habits).loadNow())
    } catch (_: Throwable) {
        null
    }

    fun createTaskJson(taskPayload: String): String? = mutateTask { activeDatabase ->
        activeDatabase.tasks.create(bridgeJson.decodeFromString<Task>(taskPayload))
    }

    fun updateTaskJson(taskPayload: String): String? = mutateTask { activeDatabase ->
        activeDatabase.tasks.update(bridgeJson.decodeFromString<Task>(taskPayload))
    }

    fun applyTaskActionJson(uid: String, actionPayload: String): String? = mutateTask { activeDatabase ->
        val request = bridgeJson.decodeFromString<TaskActionRequest>(actionPayload)
        activeDatabase.tasks.applyAction(uid, TaskAction.valueOf(request.action), request.occurredAt)
    }

    fun deleteTaskJson(uid: String, updatedAt: String): String? = mutateTask { activeDatabase ->
        activeDatabase.tasks.delete(uid, updatedAt)
    }

    private fun mutateTask(operation: (DeadlinerDatabase) -> Unit): String? = try {
        val activeDatabase = database ?: return null
        operation(activeDatabase)
        taskListViewModelJson()
    } catch (_: Throwable) {
        null
    }

    /** Reads the KMP HabitListViewModel and supplements it with the repository's record state. */
    fun habitStateJson(): String? = try {
        val activeDatabase = database ?: return null
        val viewModel = HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
        viewModel.initialize()
        val state = runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.isLoading } } }
        bridgeJson.encodeToString(HabitBridgeState(state.habits, activeDatabase.habits.allRecords()))
    } catch (_: Throwable) {
        null
    }

    fun createHabitJson(habitPayload: String): String? = mutateHabit { activeDatabase ->
        activeDatabase.habits.create(bridgeJson.decodeFromString<Habit>(habitPayload))
    }

    fun updateHabitJson(habitPayload: String): String? = mutateHabit { activeDatabase ->
        activeDatabase.habits.update(bridgeJson.decodeFromString<Habit>(habitPayload))
    }

    fun applyHabitStatusJson(uid: String, actionPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitStatusActionRequest>(actionPayload)
        activeDatabase.habits.applyStatusAction(uid, HabitStatusAction.valueOf(request.action), request.occurredAt)
    }

    fun toggleHabitRecordJson(uid: String, recordPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitRecordActionRequest>(recordPayload)
        activeDatabase.habits.toggleRecord(uid, request.occurredOn, request.occurredAt)
    }

    fun saveHabitRecordJson(recordPayload: String): String? = mutateHabit { activeDatabase ->
        activeDatabase.habits.saveRecord(bridgeJson.decodeFromString<HabitRecord>(recordPayload))
    }

    fun clearHabitRecordsJson(uid: String, recordPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitRecordActionRequest>(recordPayload)
        activeDatabase.habits.clearRecords(uid, request.occurredOn, request.occurredAt)
    }

    fun importCapturesJson(capturePayload: String): Int = try {
        val activeDatabase = database ?: return -1
        val captures = bridgeJson.decodeFromString<List<CaptureItem>>(capturePayload)
        captures.forEach { capture -> activeDatabase.captures.create(capture) }
        captures.size
    } catch (_: Throwable) { -1 }

    fun captureStateJson(): String? = try {
        val activeDatabase = database ?: return null
        val viewModel = CaptureViewModel(activeDatabase.captures, CoroutineScope(Dispatchers.Default))
        viewModel.initialize()
        val state = runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.isLoading } } }
        bridgeJson.encodeToString(CaptureBridgeState(activeDatabase.captures.list(), state.items))
    } catch (_: Throwable) { null }

    fun createCaptureJson(capturePayload: String): String? = mutateCapture { activeDatabase ->
        activeDatabase.captures.create(bridgeJson.decodeFromString<CaptureItem>(capturePayload))
    }

    fun updateCaptureJson(capturePayload: String): String? = mutateCapture { activeDatabase ->
        activeDatabase.captures.update(bridgeJson.decodeFromString<CaptureItem>(capturePayload))
    }

    fun deleteCaptureJson(uid: String, updatedAt: String): String? = mutateCapture { activeDatabase ->
        activeDatabase.captures.delete(uid, updatedAt)
    }

    private fun mutateCapture(operation: (DeadlinerDatabase) -> Unit): String? = try {
        val activeDatabase = database ?: return null
        operation(activeDatabase)
        captureStateJson()
    } catch (_: Throwable) { null }

    fun memoryProfileStateJson(): String? = try {
        val activeDatabase = database ?: return null
        bridgeJson.encodeToString(MemoryProfileBridgeState(activeDatabase.memory.list(), activeDatabase.profiles.list()))
    } catch (_: Throwable) { null }

    fun importMemoryProfileJson(payload: String): Int = try {
        val activeDatabase = database ?: return -1
        val state = bridgeJson.decodeFromString<MemoryProfileBridgeState>(payload)
        state.fragments.forEach { activeDatabase.memory.create(it) }
        state.profiles.forEach { activeDatabase.profiles.create(it) }
        state.fragments.size + state.profiles.size
    } catch (_: Throwable) { -1 }

    fun saveMemoryJson(payload: String): String? = mutateMemoryProfile { database ->
        val fragment = bridgeJson.decodeFromString<MemoryFragment>(payload)
        if (database.memory.find(fragment.uid) == null) database.memory.create(fragment) else database.memory.update(fragment)
    }

    fun deleteMemoryJson(uid: String, updatedAt: String): String? = mutateMemoryProfile { database ->
        database.memory.delete(uid, updatedAt)
    }

    fun saveProfileJson(payload: String): String? = mutateMemoryProfile { database ->
        val profile = bridgeJson.decodeFromString<UserProfile>(payload)
        if (database.profiles.find(profile.uid) == null) database.profiles.create(profile) else database.profiles.update(profile)
    }

    private fun mutateMemoryProfile(operation: (DeadlinerDatabase) -> Unit): String? = try {
        val activeDatabase = database ?: return null
        operation(activeDatabase)
        memoryProfileStateJson()
    } catch (_: Throwable) { null }

    fun deleteHabitJson(uid: String, updatedAt: String): String? = mutateHabit { activeDatabase ->
        activeDatabase.habits.delete(uid, updatedAt)
    }

    private fun mutateHabit(operation: (DeadlinerDatabase) -> Unit): String? = try {
        val activeDatabase = database ?: return null
        operation(activeDatabase)
        habitStateJson()
    } catch (_: Throwable) {
        null
    }

    fun diagnosticsJson(): String {
        val activeDatabase = database
        if (activeDatabase == null) {
            return "{\"bridge_version\":6,\"ready\":false}"
        }
        return "{\"bridge_version\":6,\"ready\":true,\"categories\":" +
            activeDatabase.categories.list().size +
            ",\"tasks\":" + activeDatabase.tasks.list().size +
            ",\"habits\":" + activeDatabase.habits.list().size +
            ",\"captures\":" + activeDatabase.captures.list().size +
            ",\"memory_fragments\":" + activeDatabase.memory.list().size +
            ",\"user_profiles\":" + activeDatabase.profiles.list().size + "}"
    }

    private val bridgeJson = Json { encodeDefaults = true }

    private data class LegacyV2MergeCache(
        val taskPayload: String,
        val habitPayload: String,
        val responsePayload: String,
    )

    private data class ChangeLogSyncCache(
        val baseUrl: String,
        val username: String,
        val password: String,
        val responsePayload: String,
    )

    @Serializable
    private data class LifiBridgeConfig(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val timezone: String,
    )

    @Serializable
    private data class LifiEventEnvelope(
        val type: String,
        val requestId: String? = null,
        val stage: String? = null,
        val status: String? = null,
        val message: String? = null,
        val agentName: String? = null,
        val phase: String? = null,
        val id: String? = null,
        val toolName: String? = null,
        val argsJson: String? = null,
        val executionMode: String? = null,
        val primaryIntent: String? = null,
        val tasks: List<LifiTaskPayload> = emptyList(),
        val habits: List<LifiHabitPayload> = emptyList(),
        val chatResponse: String? = null,
        val sessionSummary: String? = null,
        val newMemories: List<String> = emptyList(),
        val userProfile: String? = null,
        val addedMemories: List<String> = emptyList(),
        val profileUpdated: Boolean? = null,
        val newRevision: Long? = null,
    )

    @Serializable
    private data class LifiTaskPayload(val name: String, val dueTime: String? = null, val note: String? = null)

    @Serializable
    private data class LifiHabitPayload(
        val name: String,
        val period: String,
        val timesPerPeriod: Int,
        val goalType: String,
        val totalTarget: Int? = null,
    )

    private fun lifiEventJson(event: CoreEvent): String = bridgeJson.encodeToString(
        when (event) {
            is CoreEvent.OnLifecycle -> LifiEventEnvelope(
                type = "lifecycle", requestId = event.requestId, stage = event.stage,
                status = event.status, message = event.message,
            )
            is CoreEvent.OnThinking -> LifiEventEnvelope(
                type = "thinking", agentName = event.agentName, phase = event.phase, message = event.message,
            )
            is CoreEvent.OnTextStream -> LifiEventEnvelope(type = "textStream", message = event.chunk)
            is CoreEvent.OnToolRequest -> LifiEventEnvelope(
                type = "toolRequest", id = event.id, toolName = lifiToolName(event.tool),
                argsJson = lifiToolArgsJson(event.tool),
                executionMode = event.executionMode?.name?.lowercase(),
            )
            is CoreEvent.OnFinish -> LifiEventEnvelope(
                type = "finish", primaryIntent = event.result.primaryIntent,
                tasks = event.result.tasks.map(::lifiTaskPayload),
                habits = event.result.habits.map(::lifiHabitPayload),
                chatResponse = event.result.chatResponse, sessionSummary = event.result.sessionSummary,
                newMemories = event.result.newMemories, userProfile = event.result.userProfile,
            )
            is CoreEvent.OnMemoryCommitted -> LifiEventEnvelope(
                type = "memoryCommitted", addedMemories = event.addedMemories,
                profileUpdated = event.profileUpdated, newRevision = event.newRevision,
            )
            is CoreEvent.OnError -> LifiEventEnvelope(type = "error", message = event.message)
        },
    )

    private fun lifiTaskPayload(proposal: AiTaskProposal) =
        LifiTaskPayload(name = proposal.title, dueTime = proposal.dueAt, note = proposal.note)

    private fun lifiHabitPayload(proposal: AiHabitProposal) = LifiHabitPayload(
        name = proposal.name,
        period = proposal.period.name,
        timesPerPeriod = proposal.timesPerPeriod,
        goalType = proposal.goalType.name,
        totalTarget = proposal.totalTarget,
    )

    private fun lifiToolName(call: com.deadliner.domain.ai.model.ToolCall): String = when (call) {
        is com.deadliner.domain.ai.model.ToolCall.ReadTasks -> "read_tasks"
        is com.deadliner.domain.ai.model.ToolCall.CreateTask -> "create_task"
        is com.deadliner.domain.ai.model.ToolCall.UpdateDeadline -> "update_deadline"
        is com.deadliner.domain.ai.model.ToolCall.ReadHabits -> "read_habits"
        is com.deadliner.domain.ai.model.ToolCall.CreateHabit -> "create_habit"
    }

    private fun lifiToolArgsJson(call: com.deadliner.domain.ai.model.ToolCall): String = when (call) {
        is com.deadliner.domain.ai.model.ToolCall.ReadTasks -> bridgeJson.encodeToString(
            LifiReadTasksArguments(call.timeRangeDays, call.status, call.keywords, call.limit, call.sort),
        )
        is com.deadliner.domain.ai.model.ToolCall.CreateTask -> bridgeJson.encodeToString(
            LifiCreateTaskArguments(call.name, call.dueTime, call.note),
        )
        is com.deadliner.domain.ai.model.ToolCall.UpdateDeadline -> bridgeJson.encodeToString(
            LifiUpdateDeadlineArguments(call.taskId, call.newDueTime),
        )
        is com.deadliner.domain.ai.model.ToolCall.ReadHabits -> bridgeJson.encodeToString(
            LifiReadHabitsArguments(call.keywords),
        )
        is com.deadliner.domain.ai.model.ToolCall.CreateHabit -> bridgeJson.encodeToString(
            LifiCreateHabitArguments(call.name, call.period, call.timesPerPeriod, call.goalType, call.totalTarget),
        )
    }

    @Serializable private data class LifiReadTasksArguments(val timeRangeDays: Int?, val status: String?, val keywords: List<String>, val limit: Int, val sort: String?)
    @Serializable private data class LifiCreateTaskArguments(val name: String, val dueTime: String?, val note: String?)
    @Serializable private data class LifiUpdateDeadlineArguments(val taskId: String, val newDueTime: String)
    @Serializable private data class LifiReadHabitsArguments(val keywords: List<String>)
    @Serializable private data class LifiCreateHabitArguments(val name: String, val period: String, val timesPerPeriod: Int, val goalType: String, val totalTarget: Int?)

    @Serializable
    private data class TaskActionRequest(
        val action: String,
        val occurredAt: String,
    )

    @Serializable
    private data class HabitBridgeState(
        val habits: List<Habit>,
        val records: List<HabitRecord>,
    )

    @Serializable
    private data class HabitStatusActionRequest(
        val action: String,
        val occurredAt: String,
    )

    @Serializable
    private data class HabitRecordActionRequest(
        val occurredOn: String,
        val occurredAt: String,
    )

    @Serializable
    private data class CaptureBridgeState(val items: List<CaptureItem>, val viewModelItems: List<CaptureItem>)

    @Serializable
    private data class MemoryProfileBridgeState(val fragments: List<MemoryFragment>, val profiles: List<UserProfile>)

}

private fun writeCallerOwnedJson(
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

@CName("deadliner_persistence_initialize")
fun deadlinerPersistenceInitialize(
    directory: CPointer<ByteVar>?,
    installationId: CPointer<ByteVar>?,
): Int {
    val resolvedDirectory = directory?.toKString() ?: return 0
    val resolvedInstallationId = installationId?.toKString() ?: return 0
    return PersistenceRuntime.initialize(resolvedDirectory, resolvedInstallationId)
}

@CName("deadliner_persistence_close")
fun deadlinerPersistenceClose(): Int = PersistenceRuntime.close()

@CName("deadliner_lifi_process_input")
fun deadlinerLifiProcessInput(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    return runBlocking { PersistenceRuntime.processLifiInput(resolvedConfig, resolvedText) }
}

@CName("deadliner_lifi_submit_tool_result")
fun deadlinerLifiSubmitToolResult(
    toolCallId: CPointer<ByteVar>?,
    tool: CPointer<ByteVar>?,
    payload: CPointer<ByteVar>?,
): Int {
    val resolvedId = toolCallId?.toKString() ?: return 0
    val resolvedTool = tool?.toKString() ?: return 0
    val resolvedPayload = payload?.toKString() ?: return 0
    return runBlocking { PersistenceRuntime.submitLifiToolResult(resolvedId, resolvedTool, resolvedPayload) }
}

@CName("deadliner_lifi_extract_tasks_json")
fun deadlinerLifiExtractTasksJson(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    return runBlocking {
        writeCallerOwnedJson(PersistenceRuntime.extractLifiTasksJson(resolvedConfig, resolvedText), output, outputCapacity)
    }
}

@CName("deadliner_lifi_drain_events_json")
fun deadlinerLifiDrainEventsJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.drainLifiEventsJson(), output, outputCapacity)

@CName("deadliner_lifi_pending_event_count")
fun deadlinerLifiPendingEventCount(): Int = PersistenceRuntime.lifiPendingEventCount()

@CName("deadliner_lifi_reset")
fun deadlinerLifiReset(): Int = PersistenceRuntime.resetLifi()

@CName("deadliner_persistence_health")
fun deadlinerPersistenceHealth(): Int = if (PersistenceRuntime.database == null) 0 else 1

@CName("deadliner_persistence_import_legacy_v2")
fun deadlinerPersistenceImportLegacyV2(
    taskPayload: CPointer<ByteVar>?,
    habitPayload: CPointer<ByteVar>?,
): Int {
    val resolvedTaskPayload = taskPayload?.toKString() ?: return -1
    val resolvedHabitPayload = habitPayload?.toKString() ?: return -1
    return PersistenceRuntime.importLegacyV2(resolvedTaskPayload, resolvedHabitPayload)
}

@CName("deadliner_persistence_merge_legacy_v2_json")
fun deadlinerPersistenceMergeLegacyV2Json(
    taskPayload: CPointer<ByteVar>?,
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedTaskPayload = taskPayload?.toKString() ?: return 0
    val resolvedHabitPayload = habitPayload?.toKString() ?: return 0
    val writeResult = writeCallerOwnedJson(
        PersistenceRuntime.resolveLegacyV2PayloadsJson(resolvedTaskPayload, resolvedHabitPayload),
        output,
        outputCapacity,
    )
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) {
        PersistenceRuntime.clearPendingLegacyV2Merge()
    }
    return writeResult
}

@CName("deadliner_persistence_sync_webdav_changelog_json")
fun deadlinerPersistenceSyncWebDavChangeLogJson(
    baseUrl: CPointer<ByteVar>?,
    username: CPointer<ByteVar>?,
    password: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedBaseUrl = baseUrl?.toKString() ?: return 0
    val resolvedUsername = username?.toKString() ?: return 0
    val resolvedPassword = password?.toKString() ?: return 0
    val writeResult = writeCallerOwnedJson(
        PersistenceRuntime.resolveChangeLogSyncJson(resolvedBaseUrl, resolvedUsername, resolvedPassword),
        output,
        outputCapacity,
    )
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) {
        PersistenceRuntime.clearPendingChangeLogSync()
    }
    return writeResult
}

@CName("deadliner_persistence_task_list_view_model_json")
fun deadlinerPersistenceTaskListViewModelJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.taskListViewModelJson(), output, outputCapacity)

@CName("deadliner_persistence_overview_view_model_json")
fun deadlinerPersistenceOverviewViewModelJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.overviewViewModelJson(), output, outputCapacity)

@CName("deadliner_persistence_task_create_json")
fun deadlinerPersistenceTaskCreateJson(
    taskPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(taskPayload?.toKString()?.let(PersistenceRuntime::createTaskJson), output, outputCapacity)

@CName("deadliner_persistence_task_update_json")
fun deadlinerPersistenceTaskUpdateJson(
    taskPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(taskPayload?.toKString()?.let(PersistenceRuntime::updateTaskJson), output, outputCapacity)

@CName("deadliner_persistence_task_action_json")
fun deadlinerPersistenceTaskActionJson(
    uid: CPointer<ByteVar>?,
    actionPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || actionPayload == null) null else PersistenceRuntime.applyTaskActionJson(uid.toKString(), actionPayload.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_task_delete_json")
fun deadlinerPersistenceTaskDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || updatedAt == null) null else PersistenceRuntime.deleteTaskJson(uid.toKString(), updatedAt.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_habit_state_json")
fun deadlinerPersistenceHabitStateJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.habitStateJson(), output, outputCapacity)

@CName("deadliner_persistence_habit_create_json")
fun deadlinerPersistenceHabitCreateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(habitPayload?.toKString()?.let(PersistenceRuntime::createHabitJson), output, outputCapacity)

@CName("deadliner_persistence_habit_update_json")
fun deadlinerPersistenceHabitUpdateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(habitPayload?.toKString()?.let(PersistenceRuntime::updateHabitJson), output, outputCapacity)

@CName("deadliner_persistence_habit_status_action_json")
fun deadlinerPersistenceHabitStatusActionJson(
    uid: CPointer<ByteVar>?,
    actionPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || actionPayload == null) null else PersistenceRuntime.applyHabitStatusJson(uid.toKString(), actionPayload.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_habit_toggle_record_json")
fun deadlinerPersistenceHabitToggleRecordJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || recordPayload == null) null else PersistenceRuntime.toggleHabitRecordJson(uid.toKString(), recordPayload.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_habit_save_record_json")
fun deadlinerPersistenceHabitSaveRecordJson(
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(recordPayload?.toKString()?.let(PersistenceRuntime::saveHabitRecordJson), output, outputCapacity)

@CName("deadliner_persistence_habit_clear_records_json")
fun deadlinerPersistenceHabitClearRecordsJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || recordPayload == null) null else PersistenceRuntime.clearHabitRecordsJson(uid.toKString(), recordPayload.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_habit_delete_json")
fun deadlinerPersistenceHabitDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(
    if (uid == null || updatedAt == null) null else PersistenceRuntime.deleteHabitJson(uid.toKString(), updatedAt.toKString()),
    output,
    outputCapacity,
)

@CName("deadliner_persistence_import_captures_json")
fun deadlinerPersistenceImportCapturesJson(capturePayload: CPointer<ByteVar>?): Int =
    capturePayload?.toKString()?.let(PersistenceRuntime::importCapturesJson) ?: -1

@CName("deadliner_persistence_capture_state_json")
fun deadlinerPersistenceCaptureStateJson(output: CPointer<ByteVar>?, outputCapacity: Int): Int =
    writeCallerOwnedJson(PersistenceRuntime.captureStateJson(), output, outputCapacity)

@CName("deadliner_persistence_capture_create_json")
fun deadlinerPersistenceCaptureCreateJson(capturePayload: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int =
    writeCallerOwnedJson(capturePayload?.toKString()?.let(PersistenceRuntime::createCaptureJson), output, outputCapacity)

@CName("deadliner_persistence_capture_update_json")
fun deadlinerPersistenceCaptureUpdateJson(capturePayload: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int =
    writeCallerOwnedJson(capturePayload?.toKString()?.let(PersistenceRuntime::updateCaptureJson), output, outputCapacity)

@CName("deadliner_persistence_capture_delete_json")
fun deadlinerPersistenceCaptureDeleteJson(uid: CPointer<ByteVar>?, updatedAt: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int =
    writeCallerOwnedJson(if (uid == null || updatedAt == null) null else PersistenceRuntime.deleteCaptureJson(uid.toKString(), updatedAt.toKString()), output, outputCapacity)

@CName("deadliner_persistence_memory_profile_state_json")
fun deadlinerPersistenceMemoryProfileStateJson(output: CPointer<ByteVar>?, outputCapacity: Int): Int = writeCallerOwnedJson(PersistenceRuntime.memoryProfileStateJson(), output, outputCapacity)

@CName("deadliner_persistence_import_memory_profile_json")
fun deadlinerPersistenceImportMemoryProfileJson(payload: CPointer<ByteVar>?): Int = payload?.toKString()?.let(PersistenceRuntime::importMemoryProfileJson) ?: -1

@CName("deadliner_persistence_memory_save_json")
fun deadlinerPersistenceMemorySaveJson(payload: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int = writeCallerOwnedJson(payload?.toKString()?.let(PersistenceRuntime::saveMemoryJson), output, outputCapacity)

@CName("deadliner_persistence_memory_delete_json")
fun deadlinerPersistenceMemoryDeleteJson(uid: CPointer<ByteVar>?, updatedAt: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int = writeCallerOwnedJson(if (uid == null || updatedAt == null) null else PersistenceRuntime.deleteMemoryJson(uid.toKString(), updatedAt.toKString()), output, outputCapacity)

@CName("deadliner_persistence_profile_save_json")
fun deadlinerPersistenceProfileSaveJson(payload: CPointer<ByteVar>?, output: CPointer<ByteVar>?, outputCapacity: Int): Int = writeCallerOwnedJson(payload?.toKString()?.let(PersistenceRuntime::saveProfileJson), output, outputCapacity)

/**
 * Writes a null-terminated diagnostics JSON document into caller-owned memory.
 * The return value is the required buffer capacity (including the terminator), which lets the
 * NAPI layer allocate without relying on Kotlin/Native-owned pointers.
 */
@CName("deadliner_persistence_diagnostics_json")
fun deadlinerPersistenceDiagnosticsJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    return writeCallerOwnedJson(PersistenceRuntime.diagnosticsJson(), output, outputCapacity)
}

@CName("deadliner_persistence_bridge_version")
fun deadlinerPersistenceBridgeVersion(): Int = 7

/*
 * Some HarmonyOS system libcurl builds predate libcurl's WebSocket entry points. Ktor's Curl
 * engine references these symbols even when Deadliner only performs ordinary HTTP/WebDAV calls.
 * Export compatibility stubs from our DSO so loading does not depend on those optional symbols.
 * WebSockets are deliberately unsupported on this target; the V3 WebDAV transport does not use
 * them.
 */
@CName("curl_ws_meta")
fun deadlinerCurlWebSocketMeta(handle: COpaquePointer?): COpaquePointer? = null

@CName("curl_ws_send")
fun deadlinerCurlWebSocketSend(
    handle: COpaquePointer?,
    buffer: COpaquePointer?,
    length: ULong,
    sent: CPointer<ULongVar>?,
    fragmentSize: Long,
    flags: UInt,
): Int {
    return 4 // CURLE_NOT_BUILT_IN
}
