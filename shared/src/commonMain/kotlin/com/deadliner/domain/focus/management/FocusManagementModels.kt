package com.deadliner.domain.focus.management

import com.deadliner.domain.focus.*
import com.deadliner.domain.statistics.*
import kotlinx.serialization.Serializable

@Serializable
enum class FocusManagedTargetStatus { ACTIVE, COMPLETED, ARCHIVED, ABANDONED, DELETED }

@Serializable
data class FocusStartTargetOption(val target: FocusTargetRef, val title: String)

@Serializable
data class FocusManagementRecord(
    val session: FocusSession,
    val title: String,
    val targetStatus: FocusManagedTargetStatus,
    val localStartedDate: String,
    val maximumAdjustmentSeconds: Long,
)

@Serializable
data class FocusManagementDay(
    val date: String,
    /** Complete day totals, even if this page contains only part of the day. */
    val durationSeconds: Long,
    val sessionCount: Int,
    val records: List<FocusManagementRecord>,
)

@Serializable
data class FocusManagementHistoryPage(
    val days: List<FocusManagementDay>,
    val totalCount: Int,
    val totalSeconds: Long,
    val hasMore: Boolean,
)

/** Immutable read-transaction projection. Paging is confined to this exact snapshot. */
@Serializable
data class FocusManagementSnapshot(
    val asOfDate: String,
    val timeZoneId: String,
    val today: FocusStatisticsSummary,
    val allTime: FocusStatisticsSnapshot,
    val recentSevenDays: FocusStatisticsSnapshot,
    val records: List<FocusManagementRecord>,
    val startTargets: List<FocusStartTargetOption>,
) {
    fun historyPage(limit: Int = 50, target: FocusTargetRef? = null): FocusManagementHistoryPage {
        require(limit > 0)
        val selected = if (target == null) records else records.filter { it.session.boundTarget == target }
        val totals = selected.groupBy { it.localStartedDate }
        val page = selected.take(limit).groupBy { it.localStartedDate }.map { (date, rows) ->
            val day = totals.getValue(date)
            FocusManagementDay(date, day.sumOf { it.session.durationSeconds }, day.size, rows)
        }
        return FocusManagementHistoryPage(page, selected.size, selected.sumOf { it.session.durationSeconds }, selected.size > limit)
    }
}

/** Same guard used by historical records and all platform mutation forms. */
fun maximumFocusAdjustment(session: FocusSession): Long = minOf(session.durationSeconds,
    (kotlinx.datetime.Instant.parse(session.endedAt) - kotlinx.datetime.Instant.parse(session.startedAt)).inWholeSeconds,
    MAX_ADJUSTED_DURATION_SECONDS)
