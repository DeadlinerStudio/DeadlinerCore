@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.platform

import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.EventKit.EKEventStore
import platform.EventKit.EKEntityType
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatusRestricted

/**
 * iOS PermissionPort actual 实现。
 *
 * 使用 UNUserNotificationCenter 和 EKEventStore 实现权限管理。
 */
actual class PermissionPort {
    actual suspend fun checkPermission(type: PermissionType): PermissionStatus {
        return when (type) {
            PermissionType.NOTIFICATION -> checkNotificationPermission()
            PermissionType.CALENDAR -> checkCalendarPermission()
            PermissionType.MICROPHONE -> PermissionStatus.NOT_DETERMINED // iOS 需要 AVAudioSession
            PermissionType.SPEECH_RECOGNITION -> PermissionStatus.NOT_DETERMINED // iOS 需要 SFSpeechRecognizer
            PermissionType.BATTERY_OPTIMIZATION -> PermissionStatus.GRANTED // iOS 无电池优化限制
        }
    }

    actual suspend fun requestPermission(type: PermissionType): Boolean {
        return when (type) {
            PermissionType.NOTIFICATION -> requestNotificationPermission()
            PermissionType.CALENDAR -> requestCalendarPermission()
            PermissionType.MICROPHONE -> false // iOS 需要 AVAudioSession
            PermissionType.SPEECH_RECOGNITION -> false // iOS 需要 SFSpeechRecognizer
            PermissionType.BATTERY_OPTIMIZATION -> true // iOS 无电池优化限制
        }
    }

    actual suspend fun shouldShowRationale(type: PermissionType): Boolean {
        // iOS 无权限说明弹窗机制
        return false
    }

    // ===== 辅助方法 =====

    private suspend fun checkNotificationPermission(): PermissionStatus {
        val settings = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings ->
                    continuation.resumeWith(Result.success(settings))
                }
        }

        return when (settings?.authorizationStatus) {
            platform.UserNotifications.UNAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            platform.UserNotifications.UNAuthorizationStatusDenied -> PermissionStatus.DENIED
            platform.UserNotifications.UNAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
            else -> PermissionStatus.NOT_DETERMINED
        }
    }

    private suspend fun requestNotificationPermission(): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(
                    options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
                ) { granted, error ->
                    continuation.resumeWith(Result.success(granted))
                }
        }
    }

    private fun checkCalendarPermission(): PermissionStatus {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        return when (status) {
            EKAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            EKAuthorizationStatusDenied -> PermissionStatus.DENIED
            EKAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
            EKAuthorizationStatusRestricted -> PermissionStatus.RESTRICTED
            else -> PermissionStatus.NOT_DETERMINED
        }
    }

    private suspend fun requestCalendarPermission(): Boolean {
        val eventStore = EKEventStore()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            eventStore.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, error ->
                continuation.resumeWith(Result.success(granted))
            }
        }
    }
}
