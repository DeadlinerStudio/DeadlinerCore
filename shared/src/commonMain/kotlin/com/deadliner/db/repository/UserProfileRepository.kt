package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
import com.deadliner.model.ChangeOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class UserProfileRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun list(): List<UserProfile> = database.userProfileQueries.selectAll(::toProfile).executeAsList()

    fun find(uid: String): UserProfile? =
        database.userProfileQueries.selectByUid(uid, ::toProfile).executeAsOneOrNull()

    fun create(profile: UserProfile) = save(profile, ChangeOperation.INSERT)

    fun update(profile: UserProfile) = save(profile, ChangeOperation.UPDATE)

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.userProfileQueries.softDeleteUserProfile(updatedAt, uid)
            changeLog.appendLocal("user_profile", uid, ChangeOperation.DELETE, null)
        }
    }

    private fun save(profile: UserProfile, operation: ChangeOperation) {
        database.transaction {
            database.userProfileQueries.insertUserProfile(
                profile.uid, profile.nickname, profile.avatarFileName, profile.tier.name, profile.createdAt,
                profile.updatedAt, profile.isDeleted.asLong(),
            )
            database.userProfileQueries.updateUserProfile(
                profile.nickname, profile.avatarFileName, profile.tier.name, profile.updatedAt,
                profile.isDeleted.asLong(), profile.uid,
            )
            changeLog.appendLocal("user_profile", profile.uid, operation, json.encodeToJsonElement(profile).jsonObject)
        }
    }

    private fun toProfile(
        uid: String, nickname: String, avatarFileName: String?, tier: String, createdAt: String, updatedAt: String, isDeleted: Long,
    ) = UserProfile(uid, nickname, avatarFileName, UserTier.valueOf(tier), createdAt, updatedAt, isDeleted.asBoolean())
}
