package com.deadliner.db.repository.statistics

import app.cash.sqldelight.coroutines.asFlow
import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.domain.statistics.FocusOverviewStatistics
import com.deadliner.domain.statistics.FocusStatisticsEngine
import com.deadliner.domain.statistics.FocusStatisticsQuery
import com.deadliner.domain.statistics.FocusStatisticsRepository
import com.deadliner.domain.statistics.FocusStatisticsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class LocalFocusStatisticsRepository(
    private val database: AppDatabase,
    private val tasks: TaskRepository,
    private val categories: CategoryRepository,
    private val sessions: FocusSessionRepository,
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val habits: com.deadliner.db.repository.HabitRepository? = null,
) : FocusStatisticsRepository {
    override fun snapshot(query: FocusStatisticsQuery): FocusStatisticsSnapshot = database.transactionWithResult {
        engine(timeZoneProvider()).snapshot(query)
    }

    override fun overview(): FocusOverviewStatistics = database.transactionWithResult {
        val timeZone = timeZoneProvider()
        engine(timeZone).overview(nowProvider().toLocalDateTime(timeZone).date)
    }

    override fun observeOverview(): Flow<FocusOverviewStatistics> = combine(
        database.focusSessionQueries.selectAll().asFlow(),
        database.taskQueries.selectAll().asFlow(),
        database.categoryQueries.selectAll().asFlow(),
        database.habitQueries.selectAll().asFlow(),
    ) { _, _, _, _ -> overview() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private fun engine(timeZone: TimeZone) =
        FocusStatisticsEngine(sessions.list(), tasks.list(), categories.list(), timeZone, habits?.list().orEmpty())
}
