package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.category.Category

/**
 * Ensures that an entity's category foreign key has a backing row before the
 * entity is written. It is only a compatibility placeholder for malformed or
 * legacy references; normal preset rows are seeded during database creation.
 */
internal fun AppDatabase.ensureCategoryReference(categoryUid: String?, timestamp: String) {
    if (categoryUid == null) return
    val category = Category(
        uid = categoryUid,
        name = "未命名分类",
        iconKey = "folder",
        colorHex = "#808080",
        isPreset = false,
        sortOrder = Int.MAX_VALUE,
        createdAt = timestamp,
        updatedAt = timestamp,
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
