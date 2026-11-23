package com.deadliner.lifi

import com.deadliner.db.DatabaseDriverFactory
import com.deadliner.db.MacosDatabaseDriverFactory

internal actual fun appleLifiDatabaseDriverFactory(databasePath: String): DatabaseDriverFactory =
    MacosDatabaseDriverFactory(databasePath)
