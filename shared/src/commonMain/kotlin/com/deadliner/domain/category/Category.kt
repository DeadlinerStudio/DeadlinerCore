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
        preset("preset.work", "工作", "briefcase.fill", "#3B82F6", 10),
        preset("preset.study", "学习", "book.closed.fill", "#8B5CF6", 20),
        preset("preset.life", "生活", "house.fill", "#10B981", 30),
        preset("preset.health", "健康", "heart.fill", "#EF4444", 40),
        preset("preset.finance", "财务", "creditcard.fill", "#F59E0B", 50),
        preset("preset.family", "家庭", "person.2.fill", "#EC4899", 60),
        preset("preset.creative", "创作", "paintbrush.pointed.fill", "#06B6D4", 70),
        preset("preset.reading", "阅读", "text.book.closed.fill", "#6366F1", 80),
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
