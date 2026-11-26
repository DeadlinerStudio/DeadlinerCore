package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.viewmodel.habit.HabitEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class HabitEditorViewModelIntegrationTest {
    @Test
    fun createAndEditKeepFormAndRepositoryUnderSharedAuthority() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-habit-editor-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val create = HabitEditorViewModel(database.habits, database.categories, scope = CoroutineScope(coroutineContext))
            create.updateName("Read")
            create.updatePeriod(HabitPeriod.WEEKLY)
            create.updateTimesPerPeriod(3)
            create.updateReminder(HabitReminder("21:30"))
            create.save()
            await { create.uiState.value.savedHabit != null }

            val saved = assertNotNull(create.uiState.value.savedHabit)
            assertEquals(saved, database.habits.find(saved.uid))

            val edit = HabitEditorViewModel(database.habits, database.categories, saved.uid, CoroutineScope(coroutineContext))
            edit.initialize()
            await { !edit.uiState.value.isLoading }
            edit.updateName("Read deeply")
            edit.save()
            await { edit.uiState.value.savedHabit?.name == "Read deeply" }
            assertEquals("Read deeply", database.habits.find(saved.uid)?.name)
        } finally {
            database.close()
            remove(path)
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(10) }
    }
}
