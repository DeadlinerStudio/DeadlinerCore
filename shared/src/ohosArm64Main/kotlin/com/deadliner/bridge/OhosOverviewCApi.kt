@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package com.deadliner.bridge

import com.deadliner.viewmodel.overview.OverviewViewModel
import com.deadliner.domain.statistics.FocusStatisticsQuery
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val overviewJson = Json { encodeDefaults = true }
private fun overviewResponse(payload: String?, output: CPointer<ByteVar>?, capacity: Int): Int {
    if (payload == null) return 0
    val bytes = payload.encodeToByteArray()
    if (output == null || capacity < bytes.size + 1) return bytes.size + 1
    bytes.forEachIndexed { index, value -> output[index] = value }
    output[bytes.size] = 0
    return bytes.size + 1
}

@CName("deadliner_persistence_overview_month_json")
fun deadlinerOverviewMonth(month: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = runCatching {
        val db = requireNotNull(PersistenceRuntime.database)
        overviewJson.encodeToString(OverviewViewModel(db.tasks, db.habits).loadMonthNow(requireNotNull(month).toKString()))
    }.getOrNull()
    return overviewResponse(payload, output, capacity)
}

@CName("deadliner_persistence_overview_focus_query_json")
fun deadlinerOverviewFocusQuery(query: CPointer<ByteVar>?, output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = runCatching {
        val db = requireNotNull(PersistenceRuntime.database)
        val request = overviewJson.decodeFromString<FocusStatisticsQuery>(requireNotNull(query).toKString())
        overviewJson.encodeToString(db.focusStatistics.snapshot(request))
    }.getOrNull()
    return overviewResponse(payload, output, capacity)
}

@CName("deadliner_persistence_overview_focus_sessions_json")
fun deadlinerOverviewFocusSessions(output: CPointer<ByteVar>?, capacity: Int): Int {
    val payload = runCatching {
        val db = requireNotNull(PersistenceRuntime.database)
        overviewJson.encodeToString(db.focusSessions.list().filterNot { it.isDeleted }.sortedByDescending { it.startedAt })
    }.getOrNull()
    return overviewResponse(payload, output, capacity)
}
