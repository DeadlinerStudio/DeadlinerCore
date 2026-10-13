package com.deadliner.domain.quickadd

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitScheduleKind
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A single reviewable draft. Parsing never writes to persistence. */
@Serializable
data class QuickAddProposal(
    val kind: String = "TASK",
    val title: String = "",
    val dueAt: String? = null,
    val note: String = "",
    val period: String = "DAILY",
    val targetCount: Int = 1,
    val weekdaysMask: Int = 127,
    val reminderTime: String? = null,
    val needsAI: Boolean = false,
    val ready: Boolean = false,
    val source: String = "LOCAL",
    val message: String = "",
    val recognitionState: String = "",
) {
    fun toJson(): String = Json { encodeDefaults = true }.encodeToString(this)

    companion object {
        fun fromTask(task: AiTaskProposal): QuickAddProposal {
            val due = task.dueAt?.trim()?.ifBlank { null }?.replace(' ', 'T')
            val validDate = due == null || runCatching { LocalDateTime.parse(due) }.isSuccess
            return QuickAddProposal(title = task.title.trim(), dueAt = due, note = task.note.orEmpty(),
                ready = task.title.isNotBlank() && validDate, source = "AI",
                message = if (validDate) "" else "截止时间无效，请展开修正")
        }

        fun fromHabit(habit: AiHabitProposal): QuickAddProposal {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
            val supported = habit.schedule.kind == HabitScheduleKind.WEEKLY_DAYS &&
                habit.schedule.startOn == today && habit.schedule.endOn == null && habit.tracking.unit == null
            return QuickAddProposal(
                kind = "HABIT", title = habit.name.trim(),
                period = when (habit.goal.window) {
                    HabitGoalWindow.WEEK -> "WEEKLY"
                    HabitGoalWindow.MONTH -> "MONTHLY"
                    HabitGoalWindow.LIFETIME -> "TOTAL"
                    else -> "DAILY"
                },
                targetCount = habit.goal.targetCount,
                weekdaysMask = habit.schedule.weekdaysMask ?: 127,
                reminderTime = habit.reminder?.takeIf { it.isEnabled }?.localTime,
                ready = habit.name.isNotBlank() && supported, source = "AI",
                message = if (supported) "" else "提案包含高级日程或计量单位，请展开确认",
            )
        }
    }
}
