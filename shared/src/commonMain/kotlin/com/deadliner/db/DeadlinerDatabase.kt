package com.deadliner.db

import app.cash.sqldelight.coroutines.asFlow
import kotlinx.coroutines.flow.flowOn
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.PlatformLogger
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.CaptureRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.MemoryRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.UserProfileRepository
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.db.repository.statistics.LocalFocusStatisticsRepository
import com.deadliner.sync.ChangeLogSyncFacade
import com.deadliner.sync.LegacyV2SyncFacade
import com.deadliner.sync.RepositorySyncCoordinator
import com.deadliner.sync.WidgetAwareRepositoryMutationNotifier
import com.deadliner.sync.WebDavChangeLogSyncFacade
import com.deadliner.sync.WebDavChangeLogSyncOutcome
import com.deadliner.sync.WebDavV2SyncFacade
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import com.deadliner.sync.server.engine.DeadlinerServerSyncConfiguration
import com.deadliner.sync.server.engine.DeadlinerServerSyncEngine
import com.deadliner.sync.server.transport.DeadlinerServerTransport
import com.deadliner.platform.NotificationService
import com.deadliner.platform.FocusAlarmPort
import com.deadliner.service.NotificationOrchestrator
import com.deadliner.service.BackupService
import com.deadliner.service.focus.FocusTimerService

/** Single lifecycle entry point for the NEW ERA preview database. */
class DeadlinerDatabase private constructor(
    internal val database: AppDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    private val changeLogRepository = ChangeLogRepository(database)
    private val repositorySync = RepositorySyncCoordinator()
    private val widgetAwareRepositoryMutation = WidgetAwareRepositoryMutationNotifier(repositorySync)
    private val activeFocusTimerStore = ActiveFocusTimerStore(database)

    val categories = CategoryRepository(database, changeLogRepository, mutationNotifier = widgetAwareRepositoryMutation)
    val tasks = TaskRepository(
        database,
        changeLogRepository,
        mutationNotifier = widgetAwareRepositoryMutation,
        activeTimers = activeFocusTimerStore,
    )
    val habits = HabitRepository(database, changeLogRepository, mutationNotifier = widgetAwareRepositoryMutation, activeFocusTimers = activeFocusTimerStore)
    val focusSessions = FocusSessionRepository(
        database,
        changeLogRepository,
        mutationNotifier = widgetAwareRepositoryMutation,
    )
    /** Read-only KMP statistics aggregation; it introduces no schema or sync surface. */
    val focusStatistics = LocalFocusStatisticsRepository(database, tasks, categories, focusSessions, habits = habits)
    /** Raw invalidation, independent of Focus projection equality or computation failures. */
    fun observeOverviewChanges(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.combine(
        database.taskQueries.selectAll().asFlow(),
        database.habitQueries.selectAll().asFlow(),
        database.habitRecordQueries.selectAll().asFlow(),
        database.focusSessionQueries.selectAllIncludingDeleted().asFlow(),
        database.categoryQueries.selectAll().asFlow(),
    ) { _, _, _, _, _ -> Unit }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    val captures = CaptureRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val memory = MemoryRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    /** Capture the target revision before presenting an approval card. */
    fun prepareLifiTool(actionId: String, call: com.deadliner.domain.ai.model.ToolCall) {
        if (call !is com.deadliner.domain.ai.model.ToolCall.UpdateDeadline) return
        val target = tasks.find(call.taskId)
        val guard = kotlinx.serialization.json.buildJsonObject {
            put("taskId", kotlinx.serialization.json.JsonPrimitive(call.taskId))
            put("updatedAt", kotlinx.serialization.json.JsonPrimitive(target?.updatedAt.orEmpty()))
        }
        database.aiSessionQueries.saveSession("guard_" + actionId, guard.toString())
    }

    fun readLifiReceipt(actionId: String): com.deadliner.domain.ai.agent.AgentActionReceipt? =
        database.aiSessionQueries.selectReceipt(actionId).executeAsOneOrNull()?.let {
            com.deadliner.domain.ai.agent.AgentActionReceipt(actionId, it.tool_name, it.payload)
        }

    /** Business mutation and its durable receipt commit together; retries reuse the result. */
    fun executeLifiTool(actionId: String, toolName: String, argumentsJson: String): String = database.transactionWithResult {
        require(actionId.isNotBlank()) { "MISSING_ACTION_ID" }
        val arguments = com.deadliner.domain.ai.agent.canonicalAgentJson(argumentsJson)
        val previous = database.aiSessionQueries.selectReceipt(actionId).executeAsOneOrNull()
        if (previous != null) {
            require(previous.tool_name == toolName && previous.arguments == arguments) { "ACTION_ARGUMENTS_CHANGED" }
            previous.payload
        } else {
            val guard = database.aiSessionQueries.selectSession("guard_" + actionId).executeAsOneOrNull()
            val validTarget = if (toolName != "update_deadline" || guard == null) true else {
                val expected = kotlinx.serialization.json.Json.parseToJsonElement(guard) as kotlinx.serialization.json.JsonObject
                val args = kotlinx.serialization.json.Json.parseToJsonElement(arguments) as kotlinx.serialization.json.JsonObject
                val uid = (args["taskId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val expectedId = (expected["taskId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val expectedRevision = (expected["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val target = tasks.find(uid)
                uid == expectedId && target != null && !target.isDeleted && target.updatedAt == expectedRevision
            }
            val result = if (validTarget) com.deadliner.service.LifiToolExecutor(tasks, habits).execute(toolName, arguments)
                else """{"ok":false,"errorCode":"TARGET_CHANGED","message":"任务已被修改，请重新读取并确认改期。"}"""
            database.aiSessionQueries.saveReceipt(actionId, toolName, arguments, result)
            result
        }
    }
    val profiles = UserProfileRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val backups = BackupService(database)
    val changeLog = changeLogRepository
    val sync = ChangeLogSyncFacade(database, changeLogRepository)
    val webDavChangeLogSync = WebDavChangeLogSyncFacade(sync)
    val legacyV2Sync = LegacyV2SyncFacade(database)
    /** Cross-platform WebDAV V2 façade; it does not alter business schema or payload format. */
    val webDavV2Sync = WebDavV2SyncFacade(legacyV2Sync)

    private var notificationOrchestrator: NotificationOrchestrator? = null
    private val notificationQueries by lazy {
        listOf(database.taskQueries.selectAll(), database.habitQueries.selectAll(), database.habitRecordQueries.selectAll(),
            database.focusSessionQueries.selectAllIncludingDeleted(), database.activeFocusTimerQueries.selectActive())
    }
    private val notificationListener = Query.Listener {
        // Native scheduling failure must not turn a committed business mutation into a failed save.
        PlatformLogger().i("notification.database.changed", "source=committed_query_listener")
        runCatching { triggerNotificationInfoUpdate() }.onFailure {
            PlatformLogger().e("NotificationUpdate", "Committed state needs notification retry", it)
        }
    }

    /** Configure once per database lifetime; listeners also cover sync merges and backup restore. */
    fun configureNotificationUpdates(gateway: NotificationGateway,
        policyProvider: (() -> com.deadliner.platform.NotificationPolicy)? = null,
        interactionsProvider: () -> com.deadliner.platform.NotificationInteractionState = { com.deadliner.platform.NotificationInteractionState() }): NotificationOrchestrator {
        notificationQueries.forEach { it.removeListener(notificationListener) }
        val orchestrator = NotificationOrchestrator(
            gateway,
            tasksProvider = { tasks.list() },
            habitsProvider = { habits.list() },
            recordsProvider = { habits.recordsIncludingDeleted(it) },
            policyProvider = policyProvider,
            interactionsProvider = interactionsProvider,
            focusResultProvider = { focusResultNotificationInfo() },
        )
        gateway.cancelAll() // Retire orphaned registrations from earlier process lifetimes.
        notificationOrchestrator = orchestrator
        notificationQueries.forEach { it.addListener(notificationListener) }
        orchestrator.triggerNotificationInfoUpdate()
        return orchestrator
    }

    fun triggerNotificationInfoUpdate(force: Boolean = false) {
        notificationOrchestrator?.triggerNotificationInfoUpdate(force)
    }

    // Legacy native hosts may still own scheduling; opt into listeners via configureNotificationUpdates.
    fun createNotificationOrchestrator(notificationService: NotificationService): NotificationOrchestrator =
        NotificationOrchestrator(notificationService, recordsProvider = { habits.recordsIncludingDeleted(it) })

    fun createFocusTimerService(alarmPort: FocusAlarmPort = FocusAlarmPort.NONE): FocusTimerService =
        FocusTimerService(
            database = database,
            tasks = tasks,
            sessions = focusSessions,
            activeTimers = activeFocusTimerStore,
            alarmPort = alarmPort,
            mutationNotifier = widgetAwareRepositoryMutation,
            habits = habits,
        )

    /** Platform-independent completion card; available even to hosts not using the scheduler yet. */
    fun focusResultNotificationInfo(now: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()): com.deadliner.platform.NotificationMetadataEntry? {
        val session = focusSessions.latestForNotification()
        if (session?.boundTarget?.kind == com.deadliner.domain.focus.FocusTargetKind.HABIT) {
            return com.deadliner.service.FocusResultNotificationPlanner.planHabit(session,
                habits.find(session.boundTarget.uid), activeFocusTimerStore.current(), now)
        }
        return com.deadliner.service.FocusResultNotificationPlanner.plan(session,
            session?.let { tasks.find(it.taskUid) }, activeFocusTimerStore.current(), now)
    }

    /** Read-only native notification metadata; safe to re-read inside a synchronous publication callback. */
    fun focusTimerNotificationInfo(now: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()):
        com.deadliner.service.focus.FocusTimerResult {
        val active = activeFocusTimerStore.current()
            ?: return com.deadliner.service.focus.FocusTimerResult(com.deadliner.domain.focus.FocusTimerOutcome.TIMER_NOT_FOUND)
        val task = tasks.find(active.taskUid)
        val calculation = com.deadliner.domain.focus.FocusTimerEngine.calculate(active, now)
        if (active.boundTarget.kind == com.deadliner.domain.focus.FocusTargetKind.HABIT) {
            val habit = habits.find(active.boundTarget.uid)
            if (!com.deadliner.domain.focus.HabitFocusPolicy.canFocus(habit) || calculation.countdownElapsed) {
                return com.deadliner.service.focus.FocusTimerResult(com.deadliner.domain.focus.FocusTimerOutcome.INVALID_STATE)
            }
            return com.deadliner.service.focus.FocusTimerResult(com.deadliner.domain.focus.FocusTimerOutcome.NO_OP,
                activeTimer = active, snapshot = com.deadliner.domain.focus.FocusTimerEngine.snapshot(active, now, habit!!.name, true), habit = habit)
        }
        if (task == null || task.isDeleted || task.state != com.deadliner.domain.task.TaskState.ACTIVE || calculation.countdownElapsed) {
            return com.deadliner.service.focus.FocusTimerResult(com.deadliner.domain.focus.FocusTimerOutcome.INVALID_STATE)
        }
        val snapshot = com.deadliner.domain.focus.FocusTimerEngine.snapshot(active, now, task.title, taskIsActive = true)
        return com.deadliner.service.focus.FocusTimerResult(
            com.deadliner.domain.focus.FocusTimerOutcome.NO_OP, activeTimer = active, snapshot = snapshot, task = task,
        )
    }

    /** Creates one offline-first BYOS engine for a stable server/account scope. */
    fun createDeadlinerServerSyncEngine(
        configuration: DeadlinerServerSyncConfiguration,
        transport: DeadlinerServerTransport,
    ): DeadlinerServerSyncEngine = DeadlinerServerSyncEngine(database, transport, configuration)

    /** Installs process-local WebDAV auto-sync. Credentials remain owned by the platform. */
    fun configureAutomaticWebDavSync(
        configuration: WebDavTransportConfiguration,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) {
        repositorySync.configure(
            operation = { webDavChangeLogSync.synchronizeSafely(configuration) },
            onComplete = onComplete,
        )
    }

    /** Installs provider-neutral auto-sync, used by native providers such as iCloud Drive. */
    fun configureAutomaticTransportSync(
        transport: SyncBlobTransport,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) {
        repositorySync.configure(
            operation = { webDavChangeLogSync.synchronizeSafely(transport) },
            onComplete = onComplete,
        )
    }

    /** Platform lifecycle events are inputs to the shared sync scheduling policy. */
    fun setSyncForeground(active: Boolean) = repositorySync.setForeground(active)

    fun setSyncNetworkAvailable(available: Boolean) = repositorySync.setNetworkAvailable(available)

    @Throws(kotlinx.coroutines.CancellationException::class)
    suspend fun requestSync(trigger: com.deadliner.sync.SyncTrigger): WebDavChangeLogSyncOutcome =
        repositorySync.requestSync(trigger)

    fun startSync(trigger: com.deadliner.sync.SyncTrigger,
        onComplete: (WebDavChangeLogSyncOutcome) -> Unit): com.deadliner.sync.SyncRequest =
        repositorySync.startSync(trigger, onComplete)

    fun cancelBackgroundSync() = repositorySync.cancelBackgroundSync()

    fun disableAutomaticSync() {
        repositorySync.disable()
    }

    override fun close() {
        notificationQueries.forEach { it.removeListener(notificationListener) }
        notificationOrchestrator = null
        repositorySync.close()
        driver.close()
    }

    companion object {
        fun create(factory: DatabaseDriverFactory): DeadlinerDatabase {
            val driver = factory.createDriver()
            val database = AppDatabase(driver).also { it.appDatabaseQueries.enableForeignKeys() }
            return DeadlinerDatabase(database, driver).also { it.categories.seedPresetsIfCategoryTableEmpty() }
        }
    }
}
