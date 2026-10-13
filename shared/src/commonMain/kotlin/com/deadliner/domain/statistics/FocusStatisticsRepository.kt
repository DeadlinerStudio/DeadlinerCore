package com.deadliner.domain.statistics

import kotlinx.coroutines.flow.Flow

/** FocusSession is the time fact; Task, Habit and Category supply current display metadata only. */
interface FocusStatisticsRepository {
    fun snapshot(query: FocusStatisticsQuery): FocusStatisticsSnapshot
    fun overview(): FocusOverviewStatistics
    /** Re-emits after session edits/sync or Task/Habit/Category metadata changes. */
    fun observeOverview(): Flow<FocusOverviewStatistics>
}
