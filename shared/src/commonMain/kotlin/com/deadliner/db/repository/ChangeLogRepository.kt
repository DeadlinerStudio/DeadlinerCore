package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.model.ChangeLogEntry
import com.deadliner.model.ChangeOperation
import com.deadliner.platform.PlatformIdentity
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ChangeLogRepository(
    private val database: AppDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun appendLocal(
        tableName: String,
        recordId: String,
        operation: ChangeOperation,
        changedFields: JsonObject?,
    ): ChangeLogEntry {
        val entry = ChangeLogEntry(
            id = PlatformIdentity.newId(),
            tableName = tableName,
            recordId = recordId,
            operation = operation,
            changedFields = changedFields,
            clientTimestamp = Clock.System.now().toEpochMilliseconds(),
            serverTimestamp = null,
            deviceId = PlatformIdentity.deviceId(),
            synced = false,
        )
        insert(entry)
        return entry
    }

    fun pending(): List<ChangeLogEntry> = database.changeLogQueries.selectPending(::toEntry).executeAsList()

    fun latestFor(tableName: String, recordId: String): ChangeLogEntry? =
        database.changeLogQueries.selectLatestForRecord(tableName, recordId, ::toEntry).executeAsOneOrNull()

    fun latestServerTimestamp(): Long? =
        database.changeLogQueries.selectLatestServerTimestamp().executeAsOne().MAX

    fun markSynced(changeIds: List<String>, serverTimestamp: Long) {
        if (changeIds.isNotEmpty()) {
            database.changeLogQueries.markSynced(serverTimestamp, changeIds)
        }
    }

    fun recordRemote(entry: ChangeLogEntry) {
        database.changeLogQueries.insertRemoteChangeIfAbsent(
            id = entry.id,
            table_name = entry.tableName,
            record_id = entry.recordId,
            operation = entry.operation.name,
            changed_fields = entry.changedFields?.toString(),
            client_timestamp = entry.clientTimestamp,
            server_timestamp = entry.serverTimestamp,
            device_id = entry.deviceId,
        )
    }

    private fun insert(entry: ChangeLogEntry) {
        database.changeLogQueries.insertChange(
            id = entry.id,
            table_name = entry.tableName,
            record_id = entry.recordId,
            operation = entry.operation.name,
            changed_fields = entry.changedFields?.toString(),
            client_timestamp = entry.clientTimestamp,
            server_timestamp = entry.serverTimestamp,
            device_id = entry.deviceId,
            synced = if (entry.synced) 1 else 0,
        )
    }

    private fun toEntry(
        id: String,
        tableName: String,
        recordId: String,
        operation: String,
        changedFields: String?,
        clientTimestamp: Long,
        serverTimestamp: Long?,
        deviceId: String,
        synced: Long,
    ): ChangeLogEntry = ChangeLogEntry(
        id = id,
        tableName = tableName,
        recordId = recordId,
        operation = ChangeOperation.valueOf(operation),
        changedFields = changedFields?.let(json::parseToJsonElement)?.jsonObject,
        clientTimestamp = clientTimestamp,
        serverTimestamp = serverTimestamp,
        deviceId = deviceId,
        synced = synced != 0L,
    )
}
