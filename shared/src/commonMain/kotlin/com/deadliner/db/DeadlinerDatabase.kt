package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository

/** Single lifecycle entry point for the NEW ERA preview database. */
class DeadlinerDatabase private constructor(
    internal val database: AppDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    private val changeLogRepository = ChangeLogRepository(database)

    val categories = CategoryRepository(database, changeLogRepository)
    val tasks = TaskRepository(database, changeLogRepository)
    val habits = HabitRepository(database, changeLogRepository)
    val changeLog = changeLogRepository

    override fun close() {
        driver.close()
    }

    companion object {
        fun create(factory: DatabaseDriverFactory): DeadlinerDatabase {
            val driver = factory.createDriver()
            val database = AppDatabase(driver).also { it.appDatabaseQueries.enableForeignKeys() }
            return DeadlinerDatabase(database, driver)
        }
    }
}
