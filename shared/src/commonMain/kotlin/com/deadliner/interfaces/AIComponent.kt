package com.deadliner.interfaces

import com.deadliner.domain.ai.port.AgentOrchestrator
import com.deadliner.domain.ai.port.LlmGateway
import com.deadliner.domain.ai.port.MemoryStorePort
import com.deadliner.domain.ai.port.SkillRegistryPort
import com.deadliner.domain.ai.port.ToolExecutor

interface AIComponent {
    fun orchestrator(): AgentOrchestrator
    fun toolExecutor(): ToolExecutor
    fun memoryStore(): MemoryStorePort
    fun llmGateway(): LlmGateway
    fun skillRegistry(): SkillRegistryPort
}
