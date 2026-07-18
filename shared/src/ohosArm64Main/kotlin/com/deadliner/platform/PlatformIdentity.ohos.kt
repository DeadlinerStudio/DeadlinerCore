package com.deadliner.platform

import kotlin.random.Random

actual object PlatformIdentity {
    actual fun deviceId(): String = OhosPlatformIdentity.deviceId()

    actual fun newId(): String = buildString {
        append("ohos-")
        append(Random.nextLong().toString(16))
        append('-')
        append(Random.nextLong().toString(16))
    }
}

internal object OhosPlatformIdentity {
    private var configuredDeviceId: String? = null

    fun configure(deviceId: String) {
        if (configuredDeviceId == null) {
            configuredDeviceId = deviceId
        }
    }

    fun deviceId(): String = checkNotNull(configuredDeviceId) {
        "Create DatabaseDriverFactory with the HarmonyOS installation ID before using repositories."
    }
}
