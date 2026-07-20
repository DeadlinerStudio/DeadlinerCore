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
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
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
    val isLoading: Boolean = true,
    val error: String? = null,
)
