package com.deadliner.db

import app.cash.sqldelight.db.SqlDriver
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.CaptureRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.MemoryRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.UserProfileRepository
import com.deadliner.sync.ChangeLogSyncFacade
import com.deadliner.sync.LegacyV2SyncFacade
import com.deadliner.sync.WebDavChangeLogSyncFacade
import com.deadliner.sync.WebDavV2SyncFacade
import com.deadliner.platform.NotificationService
import com.deadliner.service.NotificationOrchestrator

/** Single lifecycle entry point for the NEW ERA preview database. */
class DeadlinerDatabase private constructor(
    internal val database: AppDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    private val changeLogRepository = ChangeLogRepository(database)

    val categories = CategoryRepository(database, changeLogRepository)
    val tasks = TaskRepository(database, changeLogRepository)
    val habits = HabitRepository(database, changeLogRepository)
    val captures = CaptureRepository(database, changeLogRepository)
    val memory = MemoryRepository(database, changeLogRepository)
    val profiles = UserProfileRepository(database, changeLogRepository)
    val changeLog = changeLogRepository
    val sync = ChangeLogSyncFacade(database, changeLogRepository)
    val webDavChangeLogSync = WebDavChangeLogSyncFacade(sync)
    val legacyV2Sync = LegacyV2SyncFacade(database)
    /** Cross-platform WebDAV V2 façade; it does not alter business schema or payload format. */
    val webDavV2Sync = WebDavV2SyncFacade(legacyV2Sync)

    fun createNotificationOrchestrator(notificationService: NotificationService): NotificationOrchestrator =
        NotificationOrchestrator(notificationService)

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
