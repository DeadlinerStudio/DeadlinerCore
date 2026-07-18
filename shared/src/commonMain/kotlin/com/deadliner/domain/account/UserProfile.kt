package com.deadliner.domain.account

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val uid: String,
    val nickname: String = "",
    val avatarFileName: String? = null,
    val tier: UserTier = UserTier.FREE,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false,
)
