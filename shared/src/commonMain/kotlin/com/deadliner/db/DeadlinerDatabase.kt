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
import com.deadliner.sync.RepositorySyncCoordinator
import com.deadliner.sync.WebDavChangeLogSyncFacade
import com.deadliner.sync.WebDavChangeLogSyncOutcome
import com.deadliner.sync.WebDavV2SyncFacade
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import com.deadliner.platform.NotificationService
import com.deadliner.service.NotificationOrchestrator

/** Single lifecycle entry point for the NEW ERA preview database. */
class DeadlinerDatabase private constructor(
    internal val database: AppDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    private val changeLogRepository = ChangeLogRepository(database)
    private val repositorySync = RepositorySyncCoordinator()

    val categories = CategoryRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val tasks = TaskRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val habits = HabitRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val captures = CaptureRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val memory = MemoryRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val profiles = UserProfileRepository(database, changeLogRepository, mutationNotifier = repositorySync)
    val changeLog = changeLogRepository
    val sync = ChangeLogSyncFacade(database, changeLogRepository)
    val webDavChangeLogSync = WebDavChangeLogSyncFacade(sync)
    val legacyV2Sync = LegacyV2SyncFacade(database)
    /** Cross-platform WebDAV V2 façade; it does not alter business schema or payload format. */
    val webDavV2Sync = WebDavV2SyncFacade(legacyV2Sync)

    fun createNotificationOrchestrator(notificationService: NotificationService): NotificationOrchestrator =
        NotificationOrchestrator(notificationService)

    /** Installs process-local WebDAV auto-sync. Credentials remain owned by the platform. */
    fun configureAutomaticWebDavSync(
        configuration: WebDavTransportConfiguration,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) {
        repositorySync.configure(
            operation = { webDavChangeLogSync.synchronizeSafely(configuration) },
            onComplete = onComplete,
        )
    }

    /** Installs provider-neutral auto-sync, used by native providers such as iCloud Drive. */
    fun configureAutomaticTransportSync(
        transport: SyncBlobTransport,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) {
        repositorySync.configure(
            operation = { webDavChangeLogSync.synchronizeSafely(transport) },
            onComplete = onComplete,
        )
    }

    fun disableAutomaticSync() {
        repositorySync.disable()
    }

    override fun close() {
        repositorySync.close()
        driver.close()
    }

    companion object {
        fun create(factory: DatabaseDriverFactory): DeadlinerDatabase {
            val driver = factory.createDriver()
            val database = AppDatabase(driver).also { it.appDatabaseQueries.enableForeignKeys() }
            return DeadlinerDatabase(database, driver).also { it.categories.seedPresetsIfCategoryTableEmpty() }
        }
    }
}
