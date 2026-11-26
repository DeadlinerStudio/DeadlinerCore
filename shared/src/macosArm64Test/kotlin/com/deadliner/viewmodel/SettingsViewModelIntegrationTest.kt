package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.viewmodel.settings.ProfileCreateConfig
import com.deadliner.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class SettingsViewModelIntegrationTest {
    @Test
    fun createAndUpdateProfileKeepOneSharedState() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-settings-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "settings-test-device"))
        try {
            val viewModel = SettingsViewModel(database.profiles, CoroutineScope(coroutineContext))
            viewModel.initialize()
            await { !viewModel.uiState.value.isLoading }

            viewModel.createProfile(ProfileCreateConfig(nickname = "Deadliner"))
            await { viewModel.uiState.value.profile != null }
            viewModel.updateNickname("KMP")
            await { viewModel.uiState.value.profile?.nickname == "KMP" }
            viewModel.updateAvatar("avatar.jpg")
            await { viewModel.uiState.value.profile?.avatarFileName == "avatar.jpg" }

            val stored = database.profiles.list().single()
            assertEquals("KMP", stored.nickname)
            assertEquals("avatar.jpg", stored.avatarFileName)
        } finally {
            database.close()
            remove(path)
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
