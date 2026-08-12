package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.category.Category
import com.deadliner.domain.category.CategoryPresets
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class CategoryRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
) {
    fun list(): List<Category> = database.categoryQueries.selectAll(::toCategory).executeAsList()

    fun find(uid: String): Category? = database.categoryQueries.selectByUid(uid, ::toCategory).executeAsOneOrNull()

    fun create(category: Category) = save(category, ChangeOperation.INSERT)

    fun update(category: Category) = save(category, ChangeOperation.UPDATE)

    /** Seeds presets once for a brand-new database. Tombstones count as existing user state. */
    fun seedPresetsIfCategoryTableEmpty() {
        database.transaction {
            if (database.categoryQueries.selectAllIncludingDeleted(::toCategory).executeAsList().isEmpty()) {
                CategoryPresets.all.forEach(::insertWithoutChangeLog)
            }
        }
    }

    /** Restores only missing or deleted presets; live categories keep all user edits. */
    fun restorePresets(): List<Category> {
        val now = Clock.System.now().toString()
        database.transaction {
            CategoryPresets.all.forEach { preset ->
                val existing = find(preset.uid)
                when {
                    existing == null -> insertWithoutChangeLog(preset.copy(createdAt = now, updatedAt = now))
                    existing.isDeleted -> database.categoryQueries.updateCategory(
                        preset.name, preset.iconKey, preset.colorHex, preset.isPreset.asLong(),
                        preset.sortOrder.toLong(), now, false.asLong(), preset.uid,
                    )
                }
            }
        }
        mutationNotifier.notifyMutation()
        return list()
    }

    fun softDelete(uid: String, updatedAt: String) {
        database.transaction {
            database.categoryQueries.softDeleteCategory(updatedAt, uid)
            changeLog.appendLocal("category", uid, ChangeOperation.DELETE, null)
        }
        mutationNotifier.notifyMutation()
    }

    private fun save(category: Category, operation: ChangeOperation) {
        database.transaction {
            insertWithoutChangeLog(category)
            database.categoryQueries.updateCategory(
                category.name, category.iconKey, category.colorHex, category.isPreset.asLong(), category.sortOrder.toLong(),
                category.updatedAt, category.isDeleted.asLong(), category.uid,
            )
            changeLog.appendLocal("category", category.uid, operation, json.encodeToJsonElement(category).jsonObject)
        }
        mutationNotifier.notifyMutation()
    }

    private fun insertWithoutChangeLog(category: Category) {
        database.categoryQueries.insertCategory(
            category.uid, category.name, category.iconKey, category.colorHex, category.isPreset.asLong(),
            category.sortOrder.toLong(), category.createdAt, category.updatedAt, category.isDeleted.asLong(),
        )
    }

    private fun toCategory(
        uid: String, name: String, iconKey: String, colorHex: String, isPreset: Long, sortOrder: Long,
        createdAt: String, updatedAt: String, isDeleted: Long,
    ) = Category(uid, name, iconKey, colorHex, isPreset.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
}

internal fun Boolean.asLong(): Long = if (this) 1 else 0
internal fun Long.asBoolean(): Boolean = this != 0L
