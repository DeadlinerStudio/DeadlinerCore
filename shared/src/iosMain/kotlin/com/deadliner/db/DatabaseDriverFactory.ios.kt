package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosDatabaseDriverFactory(
    private val databasePath: String? = null,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val path = databasePath?.takeIf { it.isNotBlank() }
            ?: return NativeSqliteDriver(schema = AppDatabase.Schema, name = DATABASE_NAME)
        require(path.startsWith('/')) { "iOS databasePath must be absolute." }

        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = path.substringAfterLast('/')
        require(directory.isNotEmpty() && fileName.isNotEmpty()) {
            "iOS databasePath must include a file name."
        }

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

    private companion object {
        const val DATABASE_NAME = "deadliner_new_era.db"
    }
}
