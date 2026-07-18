package com.deadliner.android

import android.content.Context
import com.deadliner.db.AndroidDatabaseDriverFactory
import com.deadliner.db.DeadlinerDatabase

/** Place this provider in the Android app's data layer; no UI dependency is required. */
class NewEraDatabaseProvider(context: Context) : AutoCloseable {
    val database = DeadlinerDatabase.create(AndroidDatabaseDriverFactory(context))

    override fun close() {
        database.close()
    }
}
