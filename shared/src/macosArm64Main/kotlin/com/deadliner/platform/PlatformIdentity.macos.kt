package com.deadliner.platform

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

actual object PlatformIdentity {
    actual fun deviceId(): String = MacosPlatformIdentity.deviceId()
    actual fun newId(): String = NSUUID().UUIDString
}

internal object MacosPlatformIdentity {
    private var configuredDeviceId: String? = null

    // Shared with the macOS host's DeviceIdentity.current().
    private const val DEVICE_ID_KEY = "deadliner.deviceId"

    fun persistedDeviceId(): String {
        val defaults = NSUserDefaults.standardUserDefaults
        return defaults.stringForKey(DEVICE_ID_KEY)?.takeIf { it.isNotEmpty() }
            ?: ("macos-" + NSUUID().UUIDString.lowercase()).also { defaults.setObject(it, forKey = DEVICE_ID_KEY) }
    }

    fun configure(deviceId: String) {
        if (configuredDeviceId == null) configuredDeviceId = deviceId
    }

    fun deviceId(): String = checkNotNull(configuredDeviceId) {
        "Create MacosDatabaseDriverFactory before using repositories."
    }
}
