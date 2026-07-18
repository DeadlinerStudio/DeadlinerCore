package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.deadliner.platform.MacosPlatformIdentity

/** Development-machine factory used by macOS integration tests and local tooling. */
class MacosDatabaseDriverFactory(
    private val databasePath: String,
    private val deviceId: String = "macos-development",
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        require(databasePath.startsWith('/')) { "macOS databasePath must be absolute." }
        val directory = databasePath.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = databasePath.substringAfterLast('/')
        require(directory.isNotEmpty() && fileName.isNotEmpty()) { "macOS databasePath must include a file name." }
        MacosPlatformIdentity.configure(deviceId)
        return NativeSqliteDriver(
            schema = AppDatabase.Schema,
            name = fileName,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(basePath = "$directory/"),
                )
            },
        )
    }
}
