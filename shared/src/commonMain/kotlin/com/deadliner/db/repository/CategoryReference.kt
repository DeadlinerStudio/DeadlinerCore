package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.category.Category

/**
 * Ensures that an entity's category foreign key has a backing row before the
 * entity is written. It is only a compatibility placeholder for malformed or
 * legacy references; normal preset rows are seeded during database creation.
 *
 * The placeholder uses the epoch instead of the entity's timestamp: snapshot export derives an
 * unstored category version from `updatedAt`, and a placeholder must never outrank the real
 * category, which would otherwise be renamed to the placeholder on every device.
 */
@Suppress("UNUSED_PARAMETER")
internal fun AppDatabase.ensureCategoryReference(categoryUid: String?, timestamp: String) {
    if (categoryUid == null) return
    val category = Category(
        uid = categoryUid,
        name = "未命名分类",
        iconKey = "folder",
        colorHex = "#808080",
        isPreset = false,
        sortOrder = Int.MAX_VALUE,
        createdAt = PLACEHOLDER_CATEGORY_TIMESTAMP,
        updatedAt = PLACEHOLDER_CATEGORY_TIMESTAMP,
    )
    categoryQueries.insertCategory(
        category.uid,
        category.name,
        category.iconKey,
        category.colorHex,
        category.isPreset.asLong(),
        category.sortOrder.toLong(),
        category.createdAt,
        category.updatedAt,
        category.isDeleted.asLong(),
    )
}

internal const val PLACEHOLDER_CATEGORY_TIMESTAMP = "1970-01-01T00:00:00Z"
