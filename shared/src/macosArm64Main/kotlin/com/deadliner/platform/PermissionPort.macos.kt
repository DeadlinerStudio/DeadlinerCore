package com.deadliner.platform

/** macOS database tooling cannot request product-app permissions. */
actual class PermissionPort {
    actual suspend fun checkPermission(type: PermissionType): PermissionStatus = PermissionStatus.NOT_DETERMINED
    actual suspend fun requestPermission(type: PermissionType): Boolean = false
    actual suspend fun shouldShowRationale(type: PermissionType): Boolean = false
}
