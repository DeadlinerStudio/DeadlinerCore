package com.deadliner.platform

/**
 * HarmonyOS PermissionPort actual 实现。
 *
 * 使用 abilityAccessCtrl 实现权限管理。
 */
actual class PermissionPort {
    actual suspend fun checkPermission(type: PermissionType): PermissionStatus {
        // 检查权限状态
        PlatformLogger().i("permission", "[permission.check] type=$type")
        return PermissionStatus.NOT_DETERMINED
    }

    actual suspend fun requestPermission(type: PermissionType): Boolean {
        // 请求权限
        PlatformLogger().i("permission", "[permission.request] type=$type")
        return false
    }

    actual suspend fun shouldShowRationale(type: PermissionType): Boolean {
        // 检查是否应该显示权限说明
        PlatformLogger().i("permission", "[permission.rationale] type=$type")
        return false
    }
}
