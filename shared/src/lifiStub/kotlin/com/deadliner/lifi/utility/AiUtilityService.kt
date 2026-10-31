@file:Suppress("UNUSED_PARAMETER")
package com.deadliner.lifi.utility

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiMonthlyAnalysis
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.port.LlmGateway
import com.deadliner.interfaces.LifiUnavailableException

class AiUtilityService(config: AIConfig, llmGateway: LlmGateway) {
    suspend fun extractTasks(text: String, preferredLang: String = "zh-CN"): List<AiTaskProposal> = throw LifiUnavailableException()
    suspend fun extractHabits(text: String, preferredLang: String = "zh-CN"): List<AiHabitProposal> = throw LifiUnavailableException()
    suspend fun generateMonthlyAnalysis(monthName: String, metricsSummary: String,
        completedTaskNames: List<String>, preferredLang: String = "zh-CN"): AiMonthlyAnalysis = throw LifiUnavailableException()
    suspend fun validateConfig(): Unit = throw LifiUnavailableException()
}
