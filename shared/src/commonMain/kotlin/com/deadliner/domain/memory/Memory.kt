package com.deadliner.domain.memory

import com.deadliner.domain.account.UserProfile
import kotlinx.serialization.Serializable

@Serializable
data class MemoryFragment(
    val uid: String,
    val content: String,
    val category: String = "Auto",
    val createdAt: String,
    val importance: Int = 3,
    val updatedAt: String,
    val isDeleted: Boolean = false,
)

@Serializable
data class MemorySnapshot(
    val revision: Long,
    val fragments: List<MemoryFragment>,
    val userProfile: UserProfile? = null,
)
