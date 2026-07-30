package com.deadliner.domain.ai

import kotlinx.serialization.Serializable

/**
 * Structured result used by add/edit forms on every platform.
 * It intentionally contains only AI proposals and never persists data.
 */
@Serializable
data class AiEditorExtraction(
    val tasks: List<AiTaskProposal> = emptyList(),
    val habits: List<AiHabitProposal> = emptyList(),
)

/** KMP-owned result for the Overview monthly-analysis presentation. */
@Serializable
data class AiMonthlyAnalysis(
    val summary: String,
    val keywords: List<String>,
)
