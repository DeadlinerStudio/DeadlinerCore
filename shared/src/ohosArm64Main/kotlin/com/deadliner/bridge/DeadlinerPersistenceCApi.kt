@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.OhosDatabaseDriverFactory
import com.deadliner.sync.WebDavChangeLogSyncOutcome
import com.deadliner.sync.webdav.WebDavCredentials
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import com.deadliner.viewmodel.task.TaskListViewModel
import com.deadliner.viewmodel.task.TaskListUiState
import com.deadliner.viewmodel.habit.HabitListViewModel
import com.deadliner.viewmodel.habit.HabitListUiState
import com.deadliner.viewmodel.habit.HabitDayStatus
import com.deadliner.viewmodel.capture.CaptureViewModel
import com.deadliner.viewmodel.overview.OverviewViewModel
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.category.Category
import com.deadliner.domain.category.CategoryMutation
import com.deadliner.domain.category.CategoryMutationRequest
import com.deadliner.domain.category.HabitCategoryAssignmentRequest
import com.deadliner.domain.category.CategoryMutationResult
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitStatusAction
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.account.UserProfile
import com.deadliner.platform.PlatformIdentity
import com.deadliner.platform.PlatformLogSink
import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.ai.model.CoreEvent
import com.deadliner.lifi.OhosLifiCore
import com.deadliner.lifi.OhosLifiEventListener
import com.deadliner.service.HabitReminderPlanner
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
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate

/**
 * Small C ABI for the HarmonyOS NAPI module.
 *
 * Return values are intentionally primitive so this boundary stays stable while snapshot import
 * and repository APIs are introduced in later migration phases.
 */
private object PersistenceRuntime {
    var database: DeadlinerDatabase? = null
    private var lastInitializationError: String = ""
    private var pendingLegacyV2Merge: LegacyV2MergeCache? = null
    private var pendingChangeLogSync: ChangeLogSyncCache? = null
    private var pendingCategoryMutation: CategoryMutationCache? = null
    private var pendingStateMutation: StateMutationCache? = null
    private var lifiCore: OhosLifiCore? = null
    private var lifiSignature: String? = null
    private var pendingLifiUtility: LifiUtilityCache? = null
    private val lifiEvents = mutableListOf<CoreEvent>()

    fun initialize(directory: String, installationId: String): Int = try {
        lastInitializationError = ""
        require(directory.startsWith('/')) { "database directory must be absolute" }
        require(installationId.isNotBlank()) { "installation ID must not be blank" }
        PlatformLogSink.configure(directory.substringBeforeLast('/'))
        PlatformLogSink.write(
            level = com.deadliner.platform.LogLevel.INFO,
            category = "persistence",
            message = "[kmp.runtime.initialize]",
        )
        closeLifiCore()
        database?.close()
        database = DeadlinerDatabase.create(OhosDatabaseDriverFactory(directory, installationId))
        pendingLegacyV2Merge = null
        pendingChangeLogSync = null
        pendingCategoryMutation = null
        pendingStateMutation = null
        pendingLifiUtility = null
        PlatformLogSink.write(
            level = com.deadliner.platform.LogLevel.INFO,
            category = "persistence",
            message = "[kmp.runtime.initialized]",
        )
        1
    } catch (error: Throwable) {
        database = null
        lastInitializationError = error.message ?: error.toString()
        PlatformLogSink.write(
            level = com.deadliner.platform.LogLevel.ERROR,
            category = "persistence",
            message = "[kmp.runtime.initialization-failed]",
            errorMessage = lastInitializationError,
        )
        0
    }

    fun initializationError(): String = lastInitializationError

    fun close(): Int {
        PlatformLogSink.write(
            level = com.deadliner.platform.LogLevel.INFO,
            category = "persistence",
            message = "[kmp.runtime.close]",
        )
        closeLifiCore()
        database?.close()
        database = null
        pendingLegacyV2Merge = null
        pendingChangeLogSync = null
        pendingCategoryMutation = null
        pendingStateMutation = null
        pendingLifiUtility = null
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
        resolveLifiUtility("tasks", configPayload, text) {
            bridgeJson.encodeToString(ensureLifiCore(configPayload).extractTasks(text))
        }
    } catch (_: Throwable) {
        null
    }

    suspend fun extractLifiHabitsJson(configPayload: String, text: String): String? = try {
        resolveLifiUtility("habits", configPayload, text) {
            bridgeJson.encodeToString(ensureLifiCore(configPayload).extractHabits(text))
        }
    } catch (_: Throwable) {
        null
    }

    suspend fun generateLifiMonthlyAnalysisJson(configPayload: String, requestPayload: String): String? = try {
        resolveLifiUtility("monthly", configPayload, requestPayload) {
            val request = bridgeJson.decodeFromString<LifiMonthlyAnalysisRequest>(requestPayload)
            bridgeJson.encodeToString(
                ensureLifiCore(configPayload).generateMonthlyAnalysis(
                    request.monthName,
                    request.metricsSummary,
                    request.completedTaskNames,
                ),
            )
        }
    } catch (_: Throwable) {
        null
    }

    fun clearPendingLifiUtility() {
        pendingLifiUtility = null
    }

    private suspend fun resolveLifiUtility(
        operation: String,
        configPayload: String,
        requestPayload: String,
        execute: suspend () -> String,
    ): String {
        require(requestPayload.isNotBlank()) { "AI utility input must not be blank" }
        pendingLifiUtility?.takeIf {
            it.operation == operation && it.configPayload == configPayload && it.requestPayload == requestPayload
        }?.let { return it.response }
        return execute().also { response ->
            pendingLifiUtility = LifiUtilityCache(operation, configPayload, requestPayload, response)
        }
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
        pendingLifiUtility = null
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

    fun configureAutomaticWebDavSync(baseUrl: String, username: String, password: String): Int = try {
        val activeDatabase = database ?: return 0
        if (baseUrl.isBlank()) {
            activeDatabase.disableAutomaticSync()
        } else {
            activeDatabase.configureAutomaticWebDavSync(
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
        1
    } catch (_: Throwable) {
        0
    }

    fun resolveCategoryMutationJson(
        operation: String,
        requestPayload: String,
        execute: () -> String?,
    ): String? {
        val pending = pendingCategoryMutation
        if (pending != null && pending.operation == operation && pending.requestPayload == requestPayload) {
            return pending.responsePayload
        }
        val response = execute()
        if (response != null) pendingCategoryMutation = CategoryMutationCache(operation, requestPayload, response)
        return response
    }

    fun clearPendingCategoryMutation() {
        pendingCategoryMutation = null
    }

    /**
     * NAPI asks a mutating C ABI twice: once for the response size and once for its bytes.
     * Retain the first response so each user action is committed exactly once.
     */
    fun resolveStateMutationJson(
        operation: String,
        requestPayload: String,
        execute: () -> String?,
    ): String? {
        val pending = pendingStateMutation
        if (pending != null && pending.operation == operation && pending.requestPayload == requestPayload) {
            return pending.responsePayload
        }
        val response = execute()
        if (response != null) pendingStateMutation = StateMutationCache(operation, requestPayload, response)
        return response
    }

    fun clearPendingStateMutation() {
        pendingStateMutation = null
    }

    /** Reads the actual KMP task-list ViewModel state; ArkTS receives only its serializable DTO. */
    fun taskListViewModelJson(): String? = try {
        val activeDatabase = database ?: return null
        val state = TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default)).loadNow()
        check(state.error == null) { state.error ?: "KMP task ViewModel failed" }
        bridgeJson.encodeToString(state.tasks)
    } catch (_: Throwable) {
        null
    }

    /** Returns the persisted categories; presets are inserted once at database creation. */
    fun categoryCatalogJson(): String? = try {
        val activeDatabase = database ?: return null
        bridgeJson.encodeToString(categoryCatalog(activeDatabase))
    } catch (_: Throwable) {
        null
    }

    fun createCategoryJson(payload: String): String? = try {
        val activeDatabase = database ?: return null
        val request = bridgeJson.decodeFromString<CategoryMutationRequest>(payload)
        val now = Clock.System.now().toString()
        val existing = categoryCatalog(activeDatabase)
        val nextSortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 10
        val created = CategoryMutation.create(request, PlatformIdentity.newId(), nextSortOrder, now)
        activeDatabase.categories.create(created)
        bridgeJson.encodeToString(CategoryMutationResult(created, categoryCatalog(activeDatabase)))
    } catch (_: Throwable) {
        null
    }

    fun updateCategoryJson(payload: String): String? = try {
        val activeDatabase = database ?: return null
        val request = bridgeJson.decodeFromString<CategoryMutationRequest>(payload)
        val uid = requireNotNull(request.uid) { "category uid is required" }
        val existing = categoryCatalog(activeDatabase).firstOrNull { it.uid == uid }
            ?: error("category not found")
        val updated = CategoryMutation.update(existing, request, Clock.System.now().toString())
        activeDatabase.categories.update(updated)
        bridgeJson.encodeToString(CategoryMutationResult(updated, categoryCatalog(activeDatabase)))
    } catch (_: Throwable) {
        null
    }

    fun deleteCategoryJson(uid: String, updatedAt: String): String? = try {
        val activeDatabase = database ?: return null
        requireNotNull(activeDatabase.categories.find(uid)) { "category not found" }
        activeDatabase.categories.softDelete(uid, updatedAt)
        bridgeJson.encodeToString(CategoryMutationResult(categories = categoryCatalog(activeDatabase)))
    } catch (_: Throwable) {
        null
    }

    fun restorePresetCategoriesJson(): String? = try {
        val activeDatabase = database ?: return null
        bridgeJson.encodeToString(CategoryMutationResult(categories = activeDatabase.categories.restorePresets()))
    } catch (_: Throwable) {
        null
    }

    /** Keeps the Habit source record and the legacy Deadline carrier aligned in one SQL transaction. */
    fun updateHabitCategoryJson(payload: String): String? = try {
        val activeDatabase = database ?: return null
        val request = bridgeJson.decodeFromString<HabitCategoryAssignmentRequest>(payload)
        if (request.categoryUid != null) {
            require(categoryCatalog(activeDatabase).any { it.uid == request.categoryUid }) { "category not found" }
        }
        val habit = activeDatabase.habits.find(request.uid) ?: error("habit not found")
        val now = Clock.System.now().toString()
        activeDatabase.database.transaction {
            activeDatabase.habits.update(habit.copy(categoryUid = request.categoryUid, updatedAt = now))
            val carrier = activeDatabase.tasks.find(request.uid)
            if (carrier != null) {
                activeDatabase.tasks.update(carrier.copy(categoryUid = request.categoryUid, updatedAt = now))
            }
        }
        habitStateJson()
    } catch (_: Throwable) {
        null
    }

    /** Category deletion deliberately does not rewrite historical task or habit references. */
    private fun categoryCatalog(activeDatabase: DeadlinerDatabase): List<Category> =
        activeDatabase.categories.list().sortedBy(Category::sortOrder)

    /** HarmonyOS receives the same one-shot overview state consumed by the iOS bridge. */
    fun overviewViewModelJson(): String? = try {
        val activeDatabase = database ?: return null
        bridgeJson.encodeToString(OverviewViewModel(activeDatabase.tasks, activeDatabase.habits).loadNow())
    } catch (_: Throwable) {
        null
    }

    fun createTaskJson(taskPayload: String): String? = mutateTask { activeDatabase ->
        TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default))
            .saveTaskNow(bridgeJson.decodeFromString(taskPayload))
    }

    fun updateTaskJson(taskPayload: String): String? = mutateTask { activeDatabase ->
        TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default))
            .saveTaskNow(bridgeJson.decodeFromString(taskPayload))
    }

    fun applyTaskActionJson(uid: String, actionPayload: String): String? = mutateTask { activeDatabase ->
        val request = bridgeJson.decodeFromString<TaskActionRequest>(actionPayload)
        TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default))
            .applyTaskActionNow(uid, TaskAction.valueOf(request.action), request.occurredAt)
    }

    fun deleteTaskJson(uid: String, updatedAt: String): String? = mutateTask { activeDatabase ->
        TaskListViewModel(activeDatabase.tasks, CoroutineScope(Dispatchers.Default)).deleteTaskNow(uid, updatedAt)
    }

    private fun mutateTask(operation: (DeadlinerDatabase) -> TaskListUiState): String? = try {
        val activeDatabase = database ?: return null
        val state = operation(activeDatabase)
        check(state.error == null) { state.error ?: "KMP task ViewModel intent failed" }
        bridgeJson.encodeToString(state.tasks)
    } catch (_: Throwable) {
        null
    }

    /** Reads the KMP HabitListViewModel projection for the requested calendar date. */
    fun habitStateJson(selectedDate: String? = null): String? = try {
        val activeDatabase = database ?: return null
        val viewModel = HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
        val state = viewModel.loadNow().let { initialState ->
            if (selectedDate == null) {
                initialState
            } else {
                viewModel.selectDate(selectedDate)
                viewModel.uiState.value
            }
        }
        check(state.error == null) { state.error ?: "KMP habit ViewModel failed" }
        bridgeJson.encodeToString(state.toHabitBridgeState())
    } catch (_: Throwable) {
        null
    }

    fun createHabitJson(habitPayload: String): String? = mutateHabit { activeDatabase ->
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .saveHabitNow(bridgeJson.decodeFromString(habitPayload))
    }

    fun updateHabitJson(habitPayload: String): String? = mutateHabit { activeDatabase ->
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .saveHabitNow(bridgeJson.decodeFromString(habitPayload))
    }

    fun applyHabitStatusJson(uid: String, actionPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitStatusActionRequest>(actionPayload)
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .applyHabitStatusNow(uid, HabitStatusAction.valueOf(request.action), request.occurredAt)
    }

    fun toggleHabitRecordJson(uid: String, recordPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitRecordActionRequest>(recordPayload)
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .toggleHabitRecordNow(uid, request.occurredOn, request.occurredAt)
    }

    fun saveHabitRecordJson(recordPayload: String): String? = mutateHabit { activeDatabase ->
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .saveHabitRecordNow(bridgeJson.decodeFromString(recordPayload))
    }

    fun clearHabitRecordsJson(uid: String, recordPayload: String): String? = mutateHabit { activeDatabase ->
        val request = bridgeJson.decodeFromString<HabitRecordActionRequest>(recordPayload)
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default))
            .clearHabitRecordsNow(uid, request.occurredOn, request.occurredAt)
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
        HabitListViewModel(activeDatabase.habits, CoroutineScope(Dispatchers.Default)).deleteHabitNow(uid, updatedAt)
    }

    private fun mutateHabit(operation: (DeadlinerDatabase) -> HabitListUiState): String? = try {
        val activeDatabase = database ?: return null
        val state = operation(activeDatabase)
        check(state.error == null) { state.error ?: "KMP habit ViewModel intent failed" }
        bridgeJson.encodeToString(state.toHabitBridgeState())
    } catch (_: Throwable) {
        null
    }

    fun diagnosticsJson(): String {
        val activeDatabase = database
        if (activeDatabase == null) {
            return "{\"bridge_version\":12,\"ready\":false}"
        }
        return "{\"bridge_version\":12,\"ready\":true,\"categories\":" +
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

    private data class CategoryMutationCache(
        val operation: String,
        val requestPayload: String,
        val responsePayload: String,
    )

    private data class StateMutationCache(
        val operation: String,
        val requestPayload: String,
        val responsePayload: String,
    )

    @Serializable
    private data class LifiBridgeConfig(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val timezone: String,
    )

    private data class LifiUtilityCache(
        val operation: String,
        val configPayload: String,
        val requestPayload: String,
        val response: String,
    )

    @Serializable
    private data class LifiMonthlyAnalysisRequest(
        val monthName: String,
        val metricsSummary: String,
        val completedTaskNames: List<String>,
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
        val schedule: HabitSchedule,
        val goal: HabitGoal,
        val tracking: HabitTracking,
        val reminder: HabitReminder? = null,
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
        schedule = proposal.schedule,
        goal = proposal.goal,
        tracking = proposal.tracking,
        reminder = proposal.reminder,
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
            LifiCreateHabitArguments(call.name, call.schedule, call.goal, call.tracking, call.reminder),
        )
    }

    @Serializable private data class LifiReadTasksArguments(val timeRangeDays: Int?, val status: String?, val keywords: List<String>, val limit: Int, val sort: String?)
    @Serializable private data class LifiCreateTaskArguments(val name: String, val dueTime: String?, val note: String?)
    @Serializable private data class LifiUpdateDeadlineArguments(val taskId: String, val newDueTime: String)
    @Serializable private data class LifiReadHabitsArguments(val keywords: List<String>)
    @Serializable private data class LifiCreateHabitArguments(
        val name: String,
        val schedule: HabitSchedule,
        val goal: HabitGoal,
        val tracking: HabitTracking,
        val reminder: HabitReminder?,
    )

    @Serializable
    private data class TaskActionRequest(
        val action: String,
        val occurredAt: String,
    )

    @Serializable
    private data class HabitBridgeState(
        val habits: List<Habit>,
        val records: List<HabitRecord>,
        val selectedDate: String,
        val visibleHabits: List<HabitDayStatus>,
        val upcomingReviews: List<HabitDayStatus>,
        val weekOverview: List<HabitDayOverviewBridge>,
        val reminderPlans: List<HabitReminderPlanBridge>,
    )

    @Serializable
    private data class HabitDayOverviewBridge(
        val date: String,
        val completedCount: Int,
        val totalCount: Int,
        val completionRatio: Float,
    )

    @Serializable
    private data class HabitReminderPlanBridge(
        val habitUid: String,
        val scheduledLocalDateTimes: List<String>,
    )

    private fun HabitListUiState.toHabitBridgeState(): HabitBridgeState {
        val from = LocalDate.parse(selectedDate)
        return HabitBridgeState(
            habits = habits,
            records = records,
            selectedDate = selectedDate,
            visibleHabits = visibleHabits,
            upcomingReviews = upcomingReviews,
            weekOverview = weekOverview.map { day ->
                HabitDayOverviewBridge(
                    date = day.date,
                    completedCount = day.completedCount,
                    totalCount = day.totalCount,
                    completionRatio = day.completionRatio,
                )
            },
            reminderPlans = habits.map { habit ->
                HabitReminderPlanBridge(
                    habitUid = habit.uid,
                    scheduledLocalDateTimes = HabitReminderPlanner.plan(
                        habit,
                        records.filter { it.habitUid == habit.uid },
                        from,
                    ),
                )
            },
        )
    }

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

/**
 * A NAPI caller first asks for the required size and then calls again with a buffer. Category
 * mutations must run only once, so retain their serialized response between those two calls.
 */
private fun writeCategoryMutationJson(
    operation: String,
    requestPayload: String,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
    execute: () -> String?,
): Int {
    val writeResult = writeCallerOwnedJson(
        PersistenceRuntime.resolveCategoryMutationJson(operation, requestPayload, execute),
        output,
        outputCapacity,
    )
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) {
        PersistenceRuntime.clearPendingCategoryMutation()
    }
    return writeResult
}

/** Writes a task or habit mutation response without re-running the mutation during NAPI's size probe. */
private fun writeStateMutationJson(
    operation: String,
    requestPayload: String,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
    execute: () -> String?,
): Int {
    val writeResult = writeCallerOwnedJson(
        PersistenceRuntime.resolveStateMutationJson(operation, requestPayload, execute),
        output,
        outputCapacity,
    )
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) {
        PersistenceRuntime.clearPendingStateMutation()
    }
    return writeResult
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

@CName("deadliner_persistence_last_initialization_error")
fun deadlinerPersistenceLastInitializationError(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.initializationError(), output, outputCapacity)

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
    val writeResult = runBlocking {
        writeCallerOwnedJson(PersistenceRuntime.extractLifiTasksJson(resolvedConfig, resolvedText), output, outputCapacity)
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
}

@CName("deadliner_lifi_extract_habits_json")
fun deadlinerLifiExtractHabitsJson(
    configPayload: CPointer<ByteVar>?,
    text: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedText = text?.toKString() ?: return 0
    val writeResult = runBlocking {
        writeCallerOwnedJson(PersistenceRuntime.extractLifiHabitsJson(resolvedConfig, resolvedText), output, outputCapacity)
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
}

@CName("deadliner_lifi_generate_monthly_analysis_json")
fun deadlinerLifiGenerateMonthlyAnalysisJson(
    configPayload: CPointer<ByteVar>?,
    requestPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedConfig = configPayload?.toKString() ?: return 0
    val resolvedRequest = requestPayload?.toKString() ?: return 0
    val writeResult = runBlocking {
        writeCallerOwnedJson(
            PersistenceRuntime.generateLifiMonthlyAnalysisJson(resolvedConfig, resolvedRequest),
            output,
            outputCapacity,
        )
    }
    if (output != null && writeResult > 0 && outputCapacity >= writeResult) PersistenceRuntime.clearPendingLifiUtility()
    return writeResult
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

@CName("deadliner_persistence_configure_automatic_webdav_sync")
fun deadlinerPersistenceConfigureAutomaticWebDavSync(
    baseUrl: CPointer<ByteVar>?,
    username: CPointer<ByteVar>?,
    password: CPointer<ByteVar>?,
): Int {
    val resolvedBaseUrl = baseUrl?.toKString() ?: return 0
    val resolvedUsername = username?.toKString() ?: return 0
    val resolvedPassword = password?.toKString() ?: return 0
    return PersistenceRuntime.configureAutomaticWebDavSync(
        resolvedBaseUrl,
        resolvedUsername,
        resolvedPassword,
    )
}

@CName("deadliner_persistence_task_list_view_model_json")
fun deadlinerPersistenceTaskListViewModelJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.taskListViewModelJson(), output, outputCapacity)

@CName("deadliner_persistence_category_catalog_json")
fun deadlinerPersistenceCategoryCatalogJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.categoryCatalogJson(), output, outputCapacity)

@CName("deadliner_persistence_category_create_json")
fun deadlinerPersistenceCategoryCreateJson(
    payload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedPayload = payload?.toKString() ?: return 0
    return writeCategoryMutationJson("create", resolvedPayload, output, outputCapacity) {
        PersistenceRuntime.createCategoryJson(resolvedPayload)
    }
}

@CName("deadliner_persistence_category_update_json")
fun deadlinerPersistenceCategoryUpdateJson(
    payload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedPayload = payload?.toKString() ?: return 0
    return writeCategoryMutationJson("update", resolvedPayload, output, outputCapacity) {
        PersistenceRuntime.updateCategoryJson(resolvedPayload)
    }
}

@CName("deadliner_persistence_category_delete_json")
fun deadlinerPersistenceCategoryDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val resolvedUpdatedAt = updatedAt?.toKString() ?: return 0
    val requestPayload = resolvedUid + "\u001F" + resolvedUpdatedAt
    return writeCategoryMutationJson("delete", requestPayload, output, outputCapacity) {
        PersistenceRuntime.deleteCategoryJson(resolvedUid, resolvedUpdatedAt)
    }
}

@CName("deadliner_persistence_category_restore_presets_json")
fun deadlinerPersistenceCategoryRestorePresetsJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCategoryMutationJson("restore_presets", "", output, outputCapacity) {
    PersistenceRuntime.restorePresetCategoriesJson()
}

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
): Int {
    val payload = taskPayload?.toKString() ?: return 0
    return writeStateMutationJson("task_create", payload, output, outputCapacity) {
        PersistenceRuntime.createTaskJson(payload)
    }
}

@CName("deadliner_persistence_task_update_json")
fun deadlinerPersistenceTaskUpdateJson(
    taskPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = taskPayload?.toKString() ?: return 0
    return writeStateMutationJson("task_update", payload, output, outputCapacity) {
        PersistenceRuntime.updateTaskJson(payload)
    }
}

@CName("deadliner_persistence_task_action_json")
fun deadlinerPersistenceTaskActionJson(
    uid: CPointer<ByteVar>?,
    actionPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = actionPayload?.toKString() ?: return 0
    return writeStateMutationJson("task_action", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.applyTaskActionJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_task_delete_json")
fun deadlinerPersistenceTaskDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val timestamp = updatedAt?.toKString() ?: return 0
    return writeStateMutationJson("task_delete", resolvedUid + "\u001F" + timestamp, output, outputCapacity) {
        PersistenceRuntime.deleteTaskJson(resolvedUid, timestamp)
    }
}

@CName("deadliner_persistence_habit_state_json")
fun deadlinerPersistenceHabitStateJson(
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(PersistenceRuntime.habitStateJson(), output, outputCapacity)

@CName("deadliner_persistence_habit_state_for_date_json")
fun deadlinerPersistenceHabitStateForDateJson(
    selectedDate: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val date = selectedDate?.toKString() ?: return 0
    return writeCallerOwnedJson(PersistenceRuntime.habitStateJson(date), output, outputCapacity)
}

@CName("deadliner_persistence_habit_create_json")
fun deadlinerPersistenceHabitCreateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = habitPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_create", payload, output, outputCapacity) {
        PersistenceRuntime.createHabitJson(payload)
    }
}

@CName("deadliner_persistence_habit_update_json")
fun deadlinerPersistenceHabitUpdateJson(
    habitPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = habitPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_update", payload, output, outputCapacity) {
        PersistenceRuntime.updateHabitJson(payload)
    }
}

@CName("deadliner_persistence_habit_category_update_json")
fun deadlinerPersistenceHabitCategoryUpdateJson(
    payload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int = writeCallerOwnedJson(payload?.toKString()?.let(PersistenceRuntime::updateHabitCategoryJson), output, outputCapacity)

@CName("deadliner_persistence_habit_status_action_json")
fun deadlinerPersistenceHabitStatusActionJson(
    uid: CPointer<ByteVar>?,
    actionPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = actionPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_status_action", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.applyHabitStatusJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_toggle_record_json")
fun deadlinerPersistenceHabitToggleRecordJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_toggle_record", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.toggleHabitRecordJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_save_record_json")
fun deadlinerPersistenceHabitSaveRecordJson(
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_save_record", payload, output, outputCapacity) {
        PersistenceRuntime.saveHabitRecordJson(payload)
    }
}

@CName("deadliner_persistence_habit_clear_records_json")
fun deadlinerPersistenceHabitClearRecordsJson(
    uid: CPointer<ByteVar>?,
    recordPayload: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val payload = recordPayload?.toKString() ?: return 0
    return writeStateMutationJson("habit_clear_records", resolvedUid + "\u001F" + payload, output, outputCapacity) {
        PersistenceRuntime.clearHabitRecordsJson(resolvedUid, payload)
    }
}

@CName("deadliner_persistence_habit_delete_json")
fun deadlinerPersistenceHabitDeleteJson(
    uid: CPointer<ByteVar>?,
    updatedAt: CPointer<ByteVar>?,
    output: CPointer<ByteVar>?,
    outputCapacity: Int,
): Int {
    val resolvedUid = uid?.toKString() ?: return 0
    val timestamp = updatedAt?.toKString() ?: return 0
    return writeStateMutationJson("habit_delete", resolvedUid + "\u001F" + timestamp, output, outputCapacity) {
        PersistenceRuntime.deleteHabitJson(resolvedUid, timestamp)
    }
}

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
fun deadlinerPersistenceBridgeVersion(): Int = 12

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
