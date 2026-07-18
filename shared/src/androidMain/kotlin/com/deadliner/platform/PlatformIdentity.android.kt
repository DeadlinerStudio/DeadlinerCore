package com.deadliner.platform

import android.content.Context
import java.util.UUID

actual object PlatformIdentity {
    actual fun deviceId(): String = AndroidPlatformIdentity.deviceId()
    actual fun newId(): String = UUID.randomUUID().toString()
}

internal object AndroidPlatformIdentity {
    private const val PREFERENCES = "deadliner_new_era"
    private const val KEY_DEVICE_ID = "sync_device_id"
    private var configuredDeviceId: String? = null

    fun configure(context: Context) {
        if (configuredDeviceId != null) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        configuredDeviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    fun deviceId(): String = checkNotNull(configuredDeviceId) {
        "Create DatabaseDriverFactory before using the NEW ERA repositories."
    }
}
