package com.deadliner.domain.category

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val uid: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val isPreset: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false,
)

object CategoryPresets {
    val all: List<Category> = listOf(
        preset("preset.work", "工作", "briefcase", "#3B82F6", 10),
        preset("preset.study", "学习", "book", "#8B5CF6", 20),
        preset("preset.life", "生活", "home", "#10B981", 30),
        preset("preset.health", "健康", "health", "#EF4444", 40),
        preset("preset.finance", "财务", "payment_card", "#F59E0B", 50),
        preset("preset.family", "家庭", "group", "#EC4899", 60),
        preset("preset.creative", "创作", "creativity", "#06B6D4", 70),
        preset("preset.reading", "阅读", "reading", "#6366F1", 80),
    )

    private fun preset(uid: String, name: String, iconKey: String, colorHex: String, sortOrder: Int): Category =
        Category(
            uid = uid,
            name = name,
            iconKey = iconKey,
            colorHex = colorHex,
            isPreset = true,
            sortOrder = sortOrder,
            createdAt = "1970-01-01T00:00:00Z",
            updatedAt = "1970-01-01T00:00:00Z",
        )
}
