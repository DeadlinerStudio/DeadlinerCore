package com.deadliner.lifi

import com.deadliner.db.repository.MemoryRepository
import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiMonthlyAnalysis
import com.deadliner.domain.ai.AiTaskProposal
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
 * OHOS counterpart of the iOS Lifi facade.
 *
 * It deliberately owns only an AI session and the Flow collector; the caller owns the shared
 * KMP database lifecycle. This keeps Lifi's orchestration, memory port, and Ktor transport
 * identical to the iOS path while exposing a native-friendly event listener to the C ABI.
 */
interface OhosLifiEventListener {
    fun onEvent(event: CoreEvent)
}

class OhosLifiCore private constructor(
    private val core: DeadlinerCore,
    private val utility: AiUtilityService,
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listener: OhosLifiEventListener? = null
    private var eventJob: Job? = null
    private var closed = false

    fun start(listener: OhosLifiEventListener) {
        check(!closed) { "OhosLifiCore has been closed." }
        this.listener = listener
        if (eventJob == null) {
            eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                core.events.collect { event ->
                    this@OhosLifiCore.listener?.onEvent(event)
                }
            }
        }
    }

    suspend fun processInput(text: String) {
        check(!closed) { "OhosLifiCore has been closed." }
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

    suspend fun submitToolResult(toolCallId: String, tool: String, payload: String) {
        check(!closed) { "OhosLifiCore has been closed." }
        core.submitToolResult(toolCallId, ToolResult(tool = tool, payload = payload))
    }

    suspend fun extractTasks(text: String): List<AiTaskProposal> {
        check(!closed) { "OhosLifiCore has been closed." }
        return utility.extractTasks(text)
    }

    suspend fun extractHabits(text: String): List<AiHabitProposal> {
        check(!closed) { "OhosLifiCore has been closed." }
        return utility.extractHabits(text)
    }

    suspend fun generateMonthlyAnalysis(
        monthName: String,
        metricsSummary: String,
        completedTaskNames: List<String>,
    ): AiMonthlyAnalysis {
        check(!closed) { "OhosLifiCore has been closed." }
        return utility.generateMonthlyAnalysis(monthName, metricsSummary, completedTaskNames)
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        scope.cancel()
        core.close()
    }

    companion object {
        fun create(
            apiKey: String,
            baseUrl: String,
            model: String,
            timezone: String,
            memoryRepository: MemoryRepository,
            executePersistedTool: ((String, String, String) -> String)? = null,
            preparePersistedTool: ((String, com.deadliner.domain.ai.model.ToolCall) -> Unit)? = null,
            readPersistedReceipt: ((String) -> com.deadliner.domain.ai.agent.AgentActionReceipt?)? = null,
        ): OhosLifiCore {
            val config = AIConfig(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                platform = "harmony",
                timezone = timezone,
            )
            val memoryStore = KmpMemoryStoreAdapter(memoryRepository)
            val component = LifiAIComponent(config, memoryStore)
            val core = DeadlinerCore(
                config = config,
                orchestrator = component.orchestrator(),
                toolExecutor = component.toolExecutor(),
                executePersistedTool = executePersistedTool,
                preparePersistedTool = preparePersistedTool,
                readPersistedReceipt = readPersistedReceipt,
            )
            return OhosLifiCore(core, AiUtilityService(config, component.llmGateway()))
        }
    }
}
