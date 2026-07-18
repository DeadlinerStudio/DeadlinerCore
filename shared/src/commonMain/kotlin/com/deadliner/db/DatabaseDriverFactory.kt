package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver

/** Platform code supplies the driver; common code owns every SQL statement. */
interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createAppDatabase(factory: DatabaseDriverFactory): AppDatabase =
    AppDatabase(factory.createDriver()).also { it.appDatabaseQueries.enableForeignKeys() }
