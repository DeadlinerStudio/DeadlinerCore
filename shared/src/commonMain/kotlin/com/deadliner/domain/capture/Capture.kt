package com.deadliner.domain.capture

import kotlinx.serialization.Serializable

@Serializable
data class CaptureItem(
    val uid: String,
    val text: String,
    val source: CaptureSource = CaptureSource.MANUAL,
    val createdAt: String,
    val updatedAt: String,
    val isConsumed: Boolean = false,
    val isDeleted: Boolean = false,
)

@Serializable
enum class CaptureSource {
    MANUAL,
    SHARE_EXTENSION,
    AI,
}

@Serializable
enum class CaptureConversionTarget {
    TASK,
    HABIT,
    AI_TASK,
    AI_HABIT,
}
