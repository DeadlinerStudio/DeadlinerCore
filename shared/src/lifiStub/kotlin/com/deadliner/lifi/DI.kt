@file:Suppress("UNUSED_PARAMETER")
package com.deadliner.lifi

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.AgentRole
import com.deadliner.domain.ai.model.ToolCapability
import com.deadliner.domain.ai.model.ToolMetadata
import com.deadliner.domain.ai.port.*
import com.deadliner.interfaces.AIComponent
import com.deadliner.interfaces.LifiUnavailableException
import com.deadliner.lifi.llm.KtorLlmClient

/** API-compatible component for public builds. Never performs provider IO or tools. */
class LifiAIComponent(config: AIConfig, private val memoryStore: MemoryStorePort) : AIComponent {
    private val gateway = KtorLlmClient(config)
    override fun orchestrator(): AgentOrchestrator = AgentOrchestrator { throw LifiUnavailableException() }
    override fun toolExecutor(): ToolExecutor = ToolExecutor { _, _ -> throw LifiUnavailableException() }
    override fun memoryStore(): MemoryStorePort = memoryStore
    override fun llmGateway(): LlmGateway = gateway
    override fun skillRegistry(): SkillRegistryPort = object : SkillRegistryPort {
        override fun forRoleAndPlatform(role: AgentRole, platform: String): List<ToolMetadata> = emptyList()
        override fun resolveExecutionPlan(capability: ToolCapability): ToolMetadata? = null
    }
}
