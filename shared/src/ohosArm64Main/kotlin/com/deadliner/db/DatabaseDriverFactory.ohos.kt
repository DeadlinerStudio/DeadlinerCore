package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.deadliner.platform.OhosPlatformIdentity

/**
 * Native HarmonyOS driver supplied by the CPF SQLDelight fork.
 *
 * [databaseDirectory] must be the app-owned persistent directory obtained from the HarmonyOS
 * native host. Do not use a cache directory or fall back to an in-memory driver: either would
 * make a successful-looking write disappear after the process or cache is cleared.
 */
class OhosDatabaseDriverFactory(
    private val databaseDirectory: String,
    private val deviceId: String,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        require(databaseDirectory.startsWith('/')) {
            "HarmonyOS databaseDirectory must be an absolute app-owned path."
        }
        require(deviceId.isNotBlank()) { "HarmonyOS deviceId must not be blank." }
        OhosPlatformIdentity.configure(deviceId)
        return NativeSqliteDriver(
            schema = AppDatabase.Schema,
            name = DATABASE_NAME,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(
                        basePath = "${databaseDirectory.trimEnd('/')}/",
                    ),
                )
            },
        )
    }

    private companion object {
        const val DATABASE_NAME = "deadliner_new_era.db"
    }
}
