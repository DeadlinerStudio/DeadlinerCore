package com.deadliner.domain.category

import kotlinx.serialization.Serializable

/** Input accepted by native clients when creating or editing a category. */
@Serializable
data class CategoryMutationRequest(
    val uid: String? = null,
    val name: String,
    val iconKey: String = CategoryMutation.DEFAULT_ICON_KEY,
    val colorHex: String = CategoryMutation.DEFAULT_COLOR_HEX,
)

/** Assigns or clears the optional category on a habit and its compatibility task carrier. */
@Serializable
data class HabitCategoryAssignmentRequest(
    val uid: String,
    val categoryUid: String? = null,
)

/** Stable mutation response so native clients can select a newly created category without guessing. */
@Serializable
data class CategoryMutationResult(
    val category: Category? = null,
    val categories: List<Category>,
)

/** Shared validation and fallback rules for category mutations on every client. */
object CategoryMutation {
    const val DEFAULT_ICON_KEY: String = "folder"
    const val DEFAULT_COLOR_HEX: String = "#3B82F6"

    private val supportedIconKeys: Set<String> = setOf(
        "folder", "inbox_tray", "briefcase", "office_building", "book", "education", "edit", "home",
        "shopping", "dining", "coffee", "health", "medical", "running", "fitness", "payment_card",
        "cash", "analytics", "group", "family", "creativity", "camera", "music", "reading", "inspiration",
        "calendar", "time", "flag", "idea", "travel", "flight", "gaming", "tools", "construction", "nature", "tag",
    )
    private val hexColorPattern: Regex = Regex("^#[0-9A-Fa-f]{6}$")

    fun create(request: CategoryMutationRequest, uid: String, sortOrder: Int, now: String): Category = Category(
        uid = uid,
        name = normalizedName(request.name),
        iconKey = normalizedIconKey(request.iconKey),
        colorHex = normalizedColorHex(request.colorHex),
        isPreset = false,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )

    fun update(existing: Category, request: CategoryMutationRequest, now: String): Category = existing.copy(
        name = normalizedName(request.name),
        iconKey = normalizedIconKey(request.iconKey),
        colorHex = normalizedColorHex(request.colorHex),
        updatedAt = now,
    )

    private fun normalizedName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "category name must not be blank" }
    }

    /**
     * Snapshot values use only these semantic IDs. The aliases are retained solely to import
     * historical iOS SF Symbol values, never to emit platform resource names again.
     */
    fun normalizedIconKey(value: String): String = when (value) {
        "tag.fill" -> "tag"
        "folder.fill" -> "folder"
        "tray.full.fill" -> "inbox_tray"
        "briefcase.fill" -> "briefcase"
        "building.2.fill" -> "office_building"
        "book.closed.fill" -> "book"
        "graduationcap.fill" -> "education"
        "pencil.and.outline" -> "edit"
        "house.fill" -> "home"
        "cart.fill" -> "shopping"
        "fork.knife" -> "dining"
        "cup.and.saucer.fill" -> "coffee"
        "heart.fill" -> "health"
        "cross.case.fill" -> "medical"
        "figure.run" -> "running"
        "dumbbell.fill" -> "fitness"
        "creditcard.fill" -> "payment_card"
        "banknote.fill" -> "cash"
        "chart.line.uptrend.xyaxis" -> "analytics"
        "person.2.fill" -> "group"
        "figure.2.and.child.holdinghands" -> "family"
        "paintbrush.pointed.fill" -> "creativity"
        "camera.fill" -> "camera"
        "music.note" -> "music"
        "text.book.closed.fill" -> "reading"
        "sparkles" -> "inspiration"
        "calendar" -> "calendar"
        "clock.fill" -> "time"
        "flag.fill" -> "flag"
        "lightbulb.fill" -> "idea"
        "globe.asia.australia.fill" -> "travel"
        "airplane" -> "flight"
        "gamecontroller.fill" -> "gaming"
        "wrench.and.screwdriver.fill" -> "tools"
        "hammer.fill" -> "construction"
        "leaf.fill" -> "nature"
        else -> value.takeIf { it in supportedIconKeys } ?: DEFAULT_ICON_KEY
    }

    fun normalizedColorHex(value: String): String =
        value.takeIf { hexColorPattern.matches(it) }?.uppercase() ?: DEFAULT_COLOR_HEX
}
