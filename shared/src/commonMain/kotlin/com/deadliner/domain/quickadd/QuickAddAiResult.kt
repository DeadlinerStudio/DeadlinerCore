package com.deadliner.domain.quickadd

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal

/** Reject multiple results: quick add is deliberately one confirmed item. */
object QuickAddAiResult {
    fun tasks(proposals: List<AiTaskProposal>): QuickAddProposal =
        if (proposals.size == 1) QuickAddProposal.fromTask(proposals.single())
        else QuickAddProposal(source = "AI", message = if (proposals.isEmpty()) "未识别出任务，请展开补充" else "识别到多个任务，请展开后拆分添加")

    fun habits(proposals: List<AiHabitProposal>): QuickAddProposal =
        if (proposals.size == 1) QuickAddProposal.fromHabit(proposals.single())
        else QuickAddProposal(kind = "HABIT", source = "AI", message = if (proposals.isEmpty()) "未识别出习惯，请展开补充" else "识别到多个习惯，请展开后拆分添加")
}
