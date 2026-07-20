package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.model.ChangeOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class MemoryRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun list(): List<MemoryFragment> = database.memoryQueries.selectAll(::toMemory).executeAsList()

    fun find(uid: String): MemoryFragment? =
        database.memoryQueries.selectByUid(uid, ::toMemory).executeAsOneOrNull()

    fun create(fragment: MemoryFragment) = save(fragment, ChangeOperation.INSERT)

    fun update(fragment: MemoryFragment) = save(fragment, ChangeOperation.UPDATE)

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.memoryQueries.softDeleteMemory(updatedAt, uid)
            changeLog.appendLocal("memory_fragment", uid, ChangeOperation.DELETE, null)
        }
    }

    private fun save(fragment: MemoryFragment, operation: ChangeOperation) {
        database.transaction {
            database.memoryQueries.insertMemory(
                fragment.uid, fragment.content, fragment.category, fragment.createdAt, fragment.importance.toLong(),
                fragment.updatedAt, fragment.isDeleted.asLong(),
            )
            database.memoryQueries.updateMemory(
                fragment.content, fragment.category, fragment.importance.toLong(), fragment.updatedAt,
                fragment.isDeleted.asLong(), fragment.uid,
            )
            changeLog.appendLocal("memory_fragment", fragment.uid, operation, json.encodeToJsonElement(fragment).jsonObject)
        }
    }

    private fun toMemory(
        uid: String, content: String, category: String, createdAt: String, importance: Long, updatedAt: String, isDeleted: Long,
    ) = MemoryFragment(uid, content, category, createdAt, importance.toInt(), updatedAt, isDeleted.asBoolean())
}
