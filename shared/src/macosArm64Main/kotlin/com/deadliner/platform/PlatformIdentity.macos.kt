package com.deadliner.platform

import platform.Foundation.NSUUID

actual object PlatformIdentity {
    actual fun deviceId(): String = MacosPlatformIdentity.deviceId()
    actual fun newId(): String = NSUUID().UUIDString
}

internal object MacosPlatformIdentity {
    private var configuredDeviceId: String? = null

    fun configure(deviceId: String) {
        if (configuredDeviceId == null) configuredDeviceId = deviceId
    }

    fun deviceId(): String = checkNotNull(configuredDeviceId) {
        "Create MacosDatabaseDriverFactory before using repositories."
    }
}
