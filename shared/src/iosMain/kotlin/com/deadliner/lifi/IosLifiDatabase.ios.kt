package com.deadliner.lifi

import com.deadliner.db.DatabaseDriverFactory
import com.deadliner.db.IosDatabaseDriverFactory

internal actual fun appleLifiDatabaseDriverFactory(databasePath: String): DatabaseDriverFactory =
    IosDatabaseDriverFactory(databasePath)
