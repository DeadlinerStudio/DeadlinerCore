package com.deadliner.domain.account

import kotlinx.serialization.Serializable

@Serializable
enum class UserTier {
    FREE,
    GEEK,
    PRO,
}
