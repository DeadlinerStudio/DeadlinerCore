package com.deadliner.platform

actual class PermissionPort {
    actual suspend fun checkPermission(type: PermissionType): PermissionStatus = PermissionStatus.NOT_DETERMINED
    actual suspend fun requestPermission(type: PermissionType): Boolean = false
    actual suspend fun shouldShowRationale(type: PermissionType): Boolean = false
}
