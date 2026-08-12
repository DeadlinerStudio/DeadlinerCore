package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.viewmodel.capture.CaptureViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class CaptureViewModelIntegrationTest {
    @Test
    fun editMergeAndDeleteSelectedStayInSharedState() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-capture-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "capture-test-device"))
        try {
            val viewModel = CaptureViewModel(database.captures, CoroutineScope(coroutineContext))
            viewModel.createCapture("first")
            viewModel.createCapture("second")
            await { viewModel.uiState.value.items.size == 2 }

            val first = viewModel.uiState.value.items.first { it.text == "first" }
            viewModel.openDetail(first.uid)
            viewModel.updateEditingText("edited")
            viewModel.saveEditing()
            await { viewModel.uiState.value.items.any { it.text == "edited" } }

            viewModel.uiState.value.items.forEach { viewModel.toggleSelection(it.uid) }
            assertEquals("edited\n\n---\n\nsecond", viewModel.selectedText())
            viewModel.mergeSelected()
            await { viewModel.uiState.value.items.size == 1 }
            assertEquals("edited\n\n---\n\nsecond", viewModel.uiState.value.items.single().text)

            viewModel.toggleSelection(viewModel.uiState.value.items.single().uid)
            viewModel.deleteSelected()
            await { viewModel.uiState.value.items.isEmpty() }
            assertTrue(database.captures.listUnconsumed().isEmpty())
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
