package com.deadliner.interfaces

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.ChatRequest
import com.deadliner.domain.ai.port.AgentOrchestrator
import com.deadliner.domain.ai.port.ToolExecutor
import com.deadliner.lifi.llm.KtorLlmClient
import com.deadliner.lifi.utility.AiUtilityService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifiStubTest {
    private val config = AIConfig("", platform = "test", timezone = "UTC")

    @Test fun unavailableRuntimeNeverInvokesProviderOrPersistedTools() = runBlocking {
        val core = DeadlinerCore(config,
            AgentOrchestrator { error("Provider must not run") },
            ToolExecutor { _, _ -> error("Tool must not run") },
            executePersistedTool = { _, _, _ -> error("Database must not be written") })
        assertEquals("unavailable", core.snapshotV2().stage)
        assertFailsWith<LifiUnavailableException> { core.startRunV2("Create a task") }
        assertFailsWith<LifiUnavailableException> { core.processInput("Hello") }
        assertFailsWith<LifiUnavailableException> { core.executeApprovedTool("action") }
        core.cancelRun()
        core.close()
        core.close()
    }

    @Test fun transportAndUtilitiesReportUnavailableInsteadOfFakeSuccess() = runBlocking {
        val client = KtorLlmClient(config)
        val utility = AiUtilityService(config, client)
        assertFailsWith<LifiUnavailableException> { client.chat(ChatRequest("model", emptyList())) }
        assertFailsWith<LifiUnavailableException> { utility.validateConfig() }
        assertFailsWith<LifiUnavailableException> { utility.extractTasks("a task") }
        assertFailsWith<LifiUnavailableException> { utility.extractHabits("a habit") }
        assertFailsWith<LifiUnavailableException> { utility.generateMonthlyAnalysis("October", "", emptyList()) }
        client.close()
    }
}
