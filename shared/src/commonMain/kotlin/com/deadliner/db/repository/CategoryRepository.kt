package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.category.Category
import com.deadliner.model.ChangeOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class CategoryRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun list(): List<Category> = database.categoryQueries.selectAll(::toCategory).executeAsList()

    fun find(uid: String): Category? = database.categoryQueries.selectByUid(uid, ::toCategory).executeAsOneOrNull()

    fun create(category: Category) = save(category, ChangeOperation.INSERT)

    fun update(category: Category) = save(category, ChangeOperation.UPDATE)

    fun softDelete(uid: String, updatedAt: String) {
        database.transaction {
            database.categoryQueries.softDeleteCategory(updatedAt, uid)
            changeLog.appendLocal("category", uid, ChangeOperation.DELETE, null)
        }
    }

    private fun save(category: Category, operation: ChangeOperation) {
        database.transaction {
            database.categoryQueries.insertCategory(
                category.uid, category.name, category.iconKey, category.colorHex, category.isPreset.asLong(),
                category.sortOrder.toLong(), category.createdAt, category.updatedAt, category.isDeleted.asLong(),
            )
            database.categoryQueries.updateCategory(
                category.name, category.iconKey, category.colorHex, category.isPreset.asLong(), category.sortOrder.toLong(),
                category.updatedAt, category.isDeleted.asLong(), category.uid,
            )
            changeLog.appendLocal("category", category.uid, operation, json.encodeToJsonElement(category).jsonObject)
        }
    }

    private fun toCategory(
        uid: String, name: String, iconKey: String, colorHex: String, isPreset: Long, sortOrder: Long,
        createdAt: String, updatedAt: String, isDeleted: Long,
    ) = Category(uid, name, iconKey, colorHex, isPreset.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
}

internal fun Boolean.asLong(): Long = if (this) 1 else 0
internal fun Long.asBoolean(): Boolean = this != 0L
