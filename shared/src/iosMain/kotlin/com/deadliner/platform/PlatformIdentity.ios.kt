package com.deadliner.platform

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

actual object PlatformIdentity {
    private const val DEVICE_ID_KEY = "deadliner.newEra.syncDeviceId"

    actual fun deviceId(): String {
        val defaults = NSUserDefaults.standardUserDefaults
        return defaults.stringForKey(DEVICE_ID_KEY) ?: NSUUID().UUIDString.also {
            defaults.setObject(it, forKey = DEVICE_ID_KEY)
        }
    }

    actual fun newId(): String = NSUUID().UUIDString
}
