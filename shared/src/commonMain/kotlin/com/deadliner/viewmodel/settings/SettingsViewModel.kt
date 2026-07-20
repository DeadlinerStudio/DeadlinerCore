package com.deadliner.viewmodel.settings

import com.deadliner.db.repository.UserProfileRepository
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
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
 * 设置页 ViewModel。
 *
 * 核心设计：
 * 1. 管理用户配置
 * 2. 使用内存缓存，零 IO 成本
 * 3. 支持主题、通知等设置
 */
class SettingsViewModel(
    private val userProfileRepository: UserProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // ===== 初始化 =====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profiles = userProfileRepository.list()
                val profile = profiles.firstOrNull()
                _uiState.update {
                    it.copy(
                        profile = profile,
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

    // ===== 更新昵称 =====
    fun updateNickname(nickname: String) {
        scope.launch {
            try {
                val profile = _uiState.value.profile ?: return@launch
                val updated = profile.copy(
                    nickname = nickname,
                    updatedAt = Clock.System.now().toString(),
                )
                userProfileRepository.update(updated)
                _uiState.update { it.copy(profile = updated, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 更新头像 =====
    fun updateAvatar(avatarFileName: String?) {
        scope.launch {
            try {
                val profile = _uiState.value.profile ?: return@launch
                val updated = profile.copy(
                    avatarFileName = avatarFileName,
                    updatedAt = Clock.System.now().toString(),
                )
                userProfileRepository.update(updated)
                _uiState.update { it.copy(profile = updated, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 创建用户配置（如果不存在）=====
    fun createProfile(config: ProfileCreateConfig) {
        scope.launch {
            try {
                val profile = UserProfile(
                    uid = PlatformIdentity.newId(),
                    nickname = config.nickname,
                    avatarFileName = config.avatarFileName,
                    tier = config.tier,
                    createdAt = Clock.System.now().toString(),
                    updatedAt = Clock.System.now().toString(),
                )
                userProfileRepository.create(profile)
                _uiState.update { it.copy(profile = profile, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}

// ===== UiState 定义 =====
data class SettingsUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

// ===== 配置对象 =====
data class ProfileCreateConfig(
    val nickname: String,
    val avatarFileName: String? = null,
    val tier: UserTier = UserTier.FREE,
)
