package com.deadliner.db.repository.focus

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.domain.focus.management.FocusManagementEngine
import com.deadliner.domain.focus.management.FocusManagementSnapshot
import kotlinx.datetime.*

/** Read-only module: no schema, sync, active-timer or statistics cache changes. */
class FocusManagementRepository(
    private val core: DeadlinerDatabase,
    private val now: () -> Instant = { Clock.System.now() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    fun snapshot(): FocusManagementSnapshot = core.database.transactionWithResult {
        val zone = timeZone()
        FocusManagementEngine(core.focusSessions.list(), core.tasks.list(), core.habits.list(), core.categories.list(), zone)
            .snapshot(now().toLocalDateTime(zone).date)
    }
}
