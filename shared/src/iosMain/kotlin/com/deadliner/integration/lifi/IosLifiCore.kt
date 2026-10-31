package com.deadliner.lifi

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.IosDatabaseDriverFactory
import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.CoreEvent
import com.deadliner.domain.ai.port.ToolResult
import com.deadliner.interfaces.DeadlinerCore
import com.deadliner.lifi.llm.KtorLlmClient
import com.deadliner.lifi.memory.KmpMemoryStoreAdapter
import com.deadliner.lifi.utility.AiUtilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Native-friendly event sink for the iOS AI bridge.
 *
 * The facade owns Kotlin Flow collection so Swift never has to manage a
 * coroutine collector or expose Kotlin cancellation errors to SwiftUI.
 */
interface IosLifiEventListener {
    fun onEvent(event: CoreEvent)
}

/**
 * iOS lifecycle facade for the private Lifi KMP implementation.
 *
 * It opens the supplied KMP database path, creates the private AI component,
 * and forwards typed [CoreEvent] values through [IosLifiEventListener].
 */
class IosLifiCore private constructor(
    private val database: DeadlinerDatabase,
    private val core: DeadlinerCore,
    private val utility: AiUtilityService,
    private val ownsDatabase: Boolean = true,
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listener: IosLifiEventListener? = null
    private var eventJob: Job? = null
    private var closed = false

    /** Starts typed event delivery. Calling it again replaces the listener. */
    fun start(listener: IosLifiEventListener) {
        check(!closed) { "IosLifiCore has been closed." }
        this.listener = listener
        if (eventJob == null) {
            // Start collecting before start() returns. MutableSharedFlow has
            // no replay, so a Swift caller may otherwise send its first input
            // before this listener becomes an active subscriber.
            eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                core.events.collect { event ->
                    this@IosLifiCore.listener?.onEvent(event)
                }
            }
        }
    }

    suspend fun processInput(text: String) {
        check(!closed) { "IosLifiCore has been closed." }
        core.processInput(text)
    }

    suspend fun startRunV2(text: String): String = core.startRunV2(text)
    suspend fun snapshotV2(): com.deadliner.domain.ai.agent.AgentV2Snapshot = core.snapshotV2()
    suspend fun readEventsV2(consumerId: String, afterSeq: Long): com.deadliner.domain.ai.agent.AgentV2Batch = core.readEventsV2(consumerId, afterSeq)
    suspend fun ackEventsV2(consumerId: String, batchId: String, lastSeq: Long): Boolean = core.ackEventsV2(consumerId, batchId, lastSeq)
    suspend fun detachV2(consumerId: String) { core.detachV2(consumerId) }
    suspend fun submitToolResultV2(toolCallId: String, tool: String, payload: String): Boolean =
        core.submitToolResultV2(toolCallId, ToolResult(tool, payload))

    suspend fun cancelRun() { core.cancelRun() }
    suspend fun executeApprovedTool(actionId: String, confirmedArguments: String?): String =
        core.executeApprovedTool(actionId, confirmedArguments)

    suspend fun submitToolResult(
        toolCallId: String,
        tool: String,
        payload: String,
    ) {
        check(!closed) { "IosLifiCore has been closed." }
        core.submitToolResult(toolCallId, ToolResult(tool = tool, payload = payload))
    }

    suspend fun extractTasks(text: String): List<com.deadliner.domain.ai.AiTaskProposal> {
        check(!closed) { "IosLifiCore has been closed." }
        return utility.extractTasks(text)
    }

    suspend fun extractHabits(text: String): List<com.deadliner.domain.ai.AiHabitProposal> {
        check(!closed) { "IosLifiCore has been closed." }
        return utility.extractHabits(text)
    }

    suspend fun generateMonthlyAnalysis(
        monthName: String,
        metricsSummary: String,
        completedTaskNames: List<String>,
    ): com.deadliner.domain.ai.AiMonthlyAnalysis {
        check(!closed) { "IosLifiCore has been closed." }
        return utility.generateMonthlyAnalysis(monthName, metricsSummary, completedTaskNames)
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        scope.cancel()
        core.close()
        if (ownsDatabase) database.close()
    }

    companion object {
        /**
         * Creates a single iOS AI session backed by the app's existing KMP
         * SQLite database. [databasePath] must be an absolute file path.
         */
        fun create(
            apiKey: String,
            baseUrl: String,
            model: String,
            timezone: String,
            databasePath: String,
        ): IosLifiCore {
            require(databasePath.startsWith('/')) {
                "iOS databasePath must be an absolute database file path."
            }

            val database = DeadlinerDatabase.create(IosDatabaseDriverFactory(databasePath))
            return createWithDatabase(apiKey, baseUrl, model, timezone, database, ownsDatabase = true)
        }

        /** Shares the app's database and invalidation graph without taking ownership. */
        fun createWithDatabase(
            apiKey: String, baseUrl: String, model: String, timezone: String,
            database: DeadlinerDatabase, ownsDatabase: Boolean = false,
        ): IosLifiCore {
            val memoryStore = KmpMemoryStoreAdapter(database.memory)
            val config = AIConfig(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                platform = "ios",
                timezone = timezone,
            )
            val component = LifiAIComponent(config, memoryStore)
            val core = DeadlinerCore(
                config = config,
                orchestrator = component.orchestrator(),
                toolExecutor = component.toolExecutor(),
                executePersistedTool = database::executeLifiTool,
                preparePersistedTool = database::prepareLifiTool,
                readPersistedReceipt = database::readLifiReceipt,
            )
            return IosLifiCore(
                database = database,
                core = core,
                utility = AiUtilityService(config, component.llmGateway()),
                ownsDatabase = ownsDatabase,
            )
        }

        suspend fun validateConfiguration(
            apiKey: String,
            baseUrl: String,
            model: String,
            timezone: String,
        ) {
            val config = AIConfig(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                platform = "ios",
                timezone = timezone,
            )
            val client = KtorLlmClient(config)
            try { AiUtilityService(config, client).validateConfig() } finally { client.close() }
        }
    }
}
