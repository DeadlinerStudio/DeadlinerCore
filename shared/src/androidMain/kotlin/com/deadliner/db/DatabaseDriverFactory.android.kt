package com.deadliner.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.deadliner.platform.AndroidPlatformIdentity

class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        AndroidPlatformIdentity.configure(context.applicationContext)
        return AndroidSqliteDriver(
            schema = AppDatabase.Schema,
            context = context.applicationContext,
            name = DATABASE_NAME,
        )
    }

    private companion object {
        const val DATABASE_NAME = "deadliner_new_era.db"
    }
}
