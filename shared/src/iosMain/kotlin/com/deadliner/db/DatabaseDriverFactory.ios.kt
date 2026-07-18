package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = AppDatabase.Schema,
        name = DATABASE_NAME,
    )

    private companion object {
        const val DATABASE_NAME = "deadliner_new_era.db"
    }
}
