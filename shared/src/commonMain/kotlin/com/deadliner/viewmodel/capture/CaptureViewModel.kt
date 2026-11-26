package com.deadliner.viewmodel.capture

import com.deadliner.db.repository.CaptureRepository
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.platform.PlatformIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 快速捕获页 ViewModel。
 *
 * 核心设计：
 * 1. 支持快速创建捕获项
 * 2. 支持标记已消费和删除
 * 3. 使用内存缓存，零 IO 成本
 */
class CaptureViewModel(
    private val captureRepository: CaptureRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun updateQuery(text: String) {
        _uiState.update { it.copy(query = text) }
    }

    // ===== 初始化 =====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val items = captureRepository.listUnconsumed()
                    .sortedByDescending { it.createdAt }
                _uiState.update {
                    it.copy(
                        items = items,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                    )
                }
            }
        }
    }

    // ===== 创建捕获项 =====
    fun createCapture(text: String, source: CaptureSource = CaptureSource.MANUAL) {
        scope.launch {
            try {
                val item = CaptureItem(
                    uid = PlatformIdentity.newId(),
                    text = text,
                    source = source,
                    createdAt = Clock.System.now().toString(),
                    updatedAt = Clock.System.now().toString(),
                )
                captureRepository.create(item)
                // 直接更新内存缓存
                _uiState.update { state ->
                    state.copy(
                        items = listOf(item) + state.items,
                        draftText = "",
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun saveDraft() {
        val text = _uiState.value.draftText.trim()
        if (text.isNotEmpty()) createCapture(text)
    }

    fun openDetail(uid: String) {
        val item = _uiState.value.items.firstOrNull { it.uid == uid } ?: return
        _uiState.update { it.copy(editingItemUid = uid, editingText = item.text) }
    }

    fun closeDetail() {
        _uiState.update { it.copy(editingItemUid = null, editingText = "") }
    }

    fun updateEditingText(text: String) {
        _uiState.update { it.copy(editingText = text) }
    }

    fun saveEditing() {
        val state = _uiState.value
        val uid = state.editingItemUid ?: return
        val text = state.editingText.trim()
        if (text.isEmpty()) return
        scope.launch {
            try {
                val current = captureRepository.find(uid) ?: return@launch
                val updated = current.copy(text = text, updatedAt = Clock.System.now().toString())
                captureRepository.update(updated)
                _uiState.update { currentState ->
                    currentState.copy(
                        items = currentState.items.map { if (it.uid == uid) updated else it }
                            .sortedByDescending(CaptureItem::updatedAt),
                        error = null,
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message) }
            }
        }
    }

    fun enterMultiSelect() {
        _uiState.update { it.copy(isMultiSelectMode = true) }
    }

    fun exitMultiSelect() {
        _uiState.update { it.copy(isMultiSelectMode = false, selectedUids = emptySet()) }
    }

    fun toggleSelection(uid: String) {
        _uiState.update { state ->
            val selected = state.selectedUids.toMutableSet()
            if (!selected.add(uid)) selected.remove(uid)
            state.copy(selectedUids = selected)
        }
    }

    fun selectedText(): String? {
        val state = _uiState.value
        return state.items
            .filter { it.uid in state.selectedUids }
            .sortedBy(CaptureItem::createdAt)
            .joinToString(MERGE_SEPARATOR) { it.text.trim() }
            .trim()
            .takeIf(String::isNotEmpty)
    }

    fun deleteSelected() {
        val selected = _uiState.value.selectedUids
        if (selected.isEmpty()) return
        scope.launch {
            try {
                val now = Clock.System.now().toString()
                selected.forEach { captureRepository.delete(it, now) }
                _uiState.update { state ->
                    state.copy(
                        items = state.items.filterNot { it.uid in selected },
                        selectedUids = emptySet(),
                        isMultiSelectMode = false,
                        error = null,
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message) }
            }
        }
    }

    /** Replaces two or more selected captures with one shared capture and returns its text. */
    fun mergeSelected(): String? {
        val state = _uiState.value
        if (state.selectedUids.size < 2) return selectedText()
        val mergedText = selectedText() ?: return null
        val selected = state.selectedUids
        scope.launch {
            try {
                val now = Clock.System.now().toString()
                val merged = CaptureItem(
                    uid = PlatformIdentity.newId(),
                    text = mergedText,
                    createdAt = now,
                    updatedAt = now,
                )
                captureRepository.create(merged)
                selected.forEach { captureRepository.delete(it, now) }
                _uiState.update { current ->
                    current.copy(
                        items = listOf(merged) + current.items.filterNot { it.uid in selected },
                        selectedUids = emptySet(),
                        isMultiSelectMode = false,
                        error = null,
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message) }
            }
        }
        return mergedText
    }

    // ===== 标记已消费 =====
    fun markConsumed(uid: String) {
        scope.launch {
            try {
                val item = captureRepository.find(uid) ?: return@launch
                val updated = item.copy(
                    isConsumed = true,
                    updatedAt = Clock.System.now().toString(),
                )
                captureRepository.update(updated)
                // 从列表中移除
                _uiState.update { state ->
                    state.copy(
                        items = state.items.filter { it.uid != uid },
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 删除捕获项 =====
    fun deleteCapture(uid: String) {
        scope.launch {
            try {
                captureRepository.delete(uid, Clock.System.now().toString())
                // 从列表中移除
                _uiState.update { state ->
                    state.copy(
                        items = state.items.filter { it.uid != uid },
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}

// ===== UiState 定义 =====
data class CaptureUiState(
    val items: List<CaptureItem> = emptyList(),
    val draftText: String = "",
    val query: String = "",
    val isMultiSelectMode: Boolean = false,
    val selectedUids: Set<String> = emptySet(),
    val editingItemUid: String? = null,
    val editingText: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val filteredItems: List<CaptureItem>
        get() = if (query.isBlank()) items else items.filter { it.text.contains(query, ignoreCase = true) }
}

private const val MERGE_SEPARATOR = "\n\n---\n\n"
