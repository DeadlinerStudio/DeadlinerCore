package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.model.ChangeOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class CaptureRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun list(): List<CaptureItem> = database.captureQueries.selectAll(::toCapture).executeAsList()

    fun listUnconsumed(): List<CaptureItem> = database.captureQueries.selectUnconsumed(::toCapture).executeAsList()

    fun find(uid: String): CaptureItem? =
        database.captureQueries.selectByUid(uid, ::toCapture).executeAsOneOrNull()

    fun create(item: CaptureItem) = save(item, ChangeOperation.INSERT)

    fun update(item: CaptureItem) = save(item, ChangeOperation.UPDATE)

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.captureQueries.softDeleteCapture(updatedAt, uid)
            changeLog.appendLocal("capture_item", uid, ChangeOperation.DELETE, null)
        }
    }

    private fun save(item: CaptureItem, operation: ChangeOperation) {
        database.transaction {
            database.captureQueries.insertCapture(
                item.uid, item.text, item.source.name, item.createdAt, item.updatedAt,
                item.isConsumed.asLong(), item.isDeleted.asLong(),
            )
            database.captureQueries.updateCapture(
                item.text, item.source.name, item.updatedAt, item.isConsumed.asLong(), item.isDeleted.asLong(), item.uid,
            )
            changeLog.appendLocal("capture_item", item.uid, operation, json.encodeToJsonElement(item).jsonObject)
        }
    }

    private fun toCapture(
        uid: String, text: String, source: String, createdAt: String, updatedAt: String, isConsumed: Long, isDeleted: Long,
    ) = CaptureItem(uid, text, CaptureSource.valueOf(source), createdAt, updatedAt, isConsumed.asBoolean(), isDeleted.asBoolean())
}
