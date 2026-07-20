package com.deadliner.platform

/**
 * 平台权限管理抽象。
 *
 * 各平台 actual 实现：
 * - iOS: UNUserNotificationCenter + LAContext
 * - Android: ActivityCompat
 * - HarmonyOS: abilityAccessCtrl
 */
expect class PermissionPort {
    /** 检查权限状态 */
    suspend fun checkPermission(type: PermissionType): PermissionStatus

    /** 请求权限 */
    suspend fun requestPermission(type: PermissionType): Boolean

    /** 检查是否应该显示权限说明 */
    suspend fun shouldShowRationale(type: PermissionType): Boolean
}

/** 权限类型（密封类，编译时类型安全） */
enum class PermissionType {
    NOTIFICATION, CALENDAR, MICROPHONE, SPEECH_RECOGNITION, BATTERY_OPTIMIZATION
}

/** 权限状态（密封类，编译时类型安全） */
enum class PermissionStatus {
    GRANTED, DENIED, NOT_DETERMINED, RESTRICTED
}
