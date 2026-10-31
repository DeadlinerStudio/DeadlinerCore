package com.deadliner.domain.focus.management

import com.deadliner.domain.focus.*
import com.deadliner.domain.task.*
import com.deadliner.domain.habit.*
import com.deadliner.domain.category.Category
import com.deadliner.domain.statistics.*
import kotlinx.datetime.*

class FocusManagementEngine(
    private val sessions: List<FocusSession>,
    private val tasks: List<Task>,
    private val habits: List<Habit>,
    private val categories: List<Category>,
    private val zone: TimeZone,
) {
    fun snapshot(today: LocalDate): FocusManagementSnapshot {
        val taskById = tasks.filterNot { it.isDeleted }.associateBy { it.uid }
        val habitById = habits.filterNot { it.isDeleted }.associateBy { it.uid }
        val records = sessions.filterNot { it.isDeleted }.sortedWith(
            compareByDescending<FocusSession> { Instant.parse(it.startedAt) }
                .thenByDescending { Instant.parse(it.createdAt) }.thenByDescending { it.uid }
        ).map { session ->
            val target = session.boundTarget
            val task = if (target.kind == FocusTargetKind.TASK) taskById[target.uid] else null
            val habit = if (target.kind == FocusTargetKind.HABIT) habitById[target.uid] else null
            val status = when {
                task != null -> when (task.state) {
                    TaskState.ACTIVE -> FocusManagedTargetStatus.ACTIVE
                    TaskState.COMPLETED -> FocusManagedTargetStatus.COMPLETED
                    TaskState.ARCHIVED, TaskState.ABANDONED_ARCHIVED -> FocusManagedTargetStatus.ARCHIVED
                    TaskState.ABANDONED -> FocusManagedTargetStatus.ABANDONED
                }
                habit != null -> if (habit.status == HabitStatus.ACTIVE) FocusManagedTargetStatus.ACTIVE else FocusManagedTargetStatus.ARCHIVED
                else -> FocusManagedTargetStatus.DELETED
            }
            FocusManagementRecord(session, task?.title ?: habit?.name ?: session.targetTitleSnapshot, status,
                Instant.parse(session.startedAt).toLocalDateTime(zone).date.toString(), maximumFocusAdjustment(session))
        }
        val statistics = FocusStatisticsEngine(sessions, tasks, categories, zone, habits)
        val tomorrow = today.plus(DatePeriod(days = 1))
        fun range(start: LocalDate) = FocusStatisticsQuery(FocusStatisticsDateRange(start.toString(), tomorrow.toString()))
        val candidates = tasks.filter { !it.isDeleted && it.state == TaskState.ACTIVE }
            .map { FocusStartTargetOption(FocusTargetRef(FocusTargetKind.TASK, it.uid), it.title) } +
            habits.filter(HabitFocusPolicy::canFocus)
                .map { FocusStartTargetOption(FocusTargetRef(FocusTargetKind.HABIT, it.uid), it.name) }
        return FocusManagementSnapshot(today.toString(), zone.id, statistics.snapshot(range(today)).summary,
            statistics.snapshot(FocusStatisticsQuery()), statistics.snapshot(range(today.minus(DatePeriod(days = 6)))), records,
            candidates.sortedWith(compareBy<FocusStartTargetOption> { it.target.kind.name }.thenBy { it.title }.thenBy { it.target.uid }))
    }
}
