package com.deadliner.lifi.memory

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.memory.MemorySnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class KmpMemoryStoreAdapterIntegrationTest {
    @Test
    fun memoryUserProfileDoesNotOverwriteSettingsUser() = runBlocking {
        withDatabase { database ->
            val settingsUser = UserProfile(
                uid = "settings-user",
                nickname = "Aritx",
                createdAt = NOW,
                updatedAt = NOW,
            )
            database.profiles.create(settingsUser)
            val store = KmpMemoryStoreAdapter(database.memory)

            store.saveUserProfile("Prefers short actionable answers")

            assertEquals("Prefers short actionable answers", store.getUserProfile())
            assertEquals("Aritx", database.profiles.find(settingsUser.uid)?.nickname)
        }
    }

    @Test
    fun memorySnapshotRoundTripsItsOwnUserProfile() = runBlocking {
        withDatabase { database ->
            val store = KmpMemoryStoreAdapter(database.memory)
            store.replaceSnapshot(
                MemorySnapshot(
                    revision = 1,
                    fragments = emptyList(),
                    userProfile = "Uses weekly planning",
                ),
            )

            assertEquals("Uses weekly planning", store.currentSnapshot().userProfile)

            store.saveUserProfile("")
            assertEquals("", store.currentSnapshot().userProfile)
        }
    }

    private inline fun withDatabase(block: (DeadlinerDatabase) -> Unit) {
        val path = "${NSTemporaryDirectory()}deadliner-memory-user-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "memory-user-test-device"))
        try {
            block(database)
        } finally {
            database.close()
            remove(path)
        }
    }

    private companion object {
        const val NOW = "2026-08-14T00:00:00Z"
    }
}
