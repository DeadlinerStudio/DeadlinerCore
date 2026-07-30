package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.AgentRole
import com.deadliner.domain.ai.model.ToolCapability
import com.deadliner.domain.ai.model.ToolMetadata

interface SkillRegistryPort {
    fun forRoleAndPlatform(role: AgentRole, platform: String): List<ToolMetadata>
    fun resolveExecutionPlan(capability: ToolCapability): ToolMetadata?
}
