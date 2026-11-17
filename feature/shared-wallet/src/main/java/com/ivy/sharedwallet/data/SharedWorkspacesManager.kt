package com.ivy.sharedwallet.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.workspace.Workspace
import com.ivy.data.workspace.WorkspaceRegistry
import com.ivy.sharedwallet.engine.LedgerSynchronizer
import com.ivy.sharedwallet.engine.SharedLedgerException
import com.ivy.sharedwallet.engine.SharingState
import com.ivy.sharedwallet.engine.SyncReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

data class WorkspaceInfo(
    val workspace: Workspace,
    val isActive: Boolean,
    val sharing: SharingState?,
    val fileName: String?,
)

/**
 * Entry point for workspaces and their sharing:
 * create/switch/delete workspaces, share a workspace through a file,
 * join a workspace somebody shared and sync them.
 */
@Singleton
class SharedWorkspacesManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val registry: WorkspaceRegistry,
    private val activeDb: IvyRoomDatabase,
    private val transports: LedgerTransportFactory,
    private val dataObserver: DataObserver,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val dir: File get() = File(context.filesDir, "shared_ledgers")

    fun workspaces(): List<WorkspaceInfo> {
        val active = registry.active().id
        return registry.all().map { workspace ->
            val sharing = stateStore(workspace.id).loadBlocking()
            WorkspaceInfo(
                workspace = workspace,
                isActive = workspace.id == active,
                sharing = sharing,
                fileName = sharing?.let { transports.displayName(it.location) },
            )
        }
    }

    fun hasSharedWorkspaces(): Boolean = registry.all().any { stateStore(it.id).exists() }

    suspend fun createWorkspace(name: String): Workspace = withContext(Dispatchers.IO) {
        val workspace = registry.create(name)
        seedSettings(workspace)
        workspace
    }

    fun renameWorkspace(id: String, name: String) = registry.rename(id, name)

    suspend fun deleteWorkspace(id: String) = withContext(Dispatchers.IO) {
        registry.delete(id)
        stateStore(id).clear()
    }

    /**
     * Activates a workspace. The app restarts so that every screen and cache
     * reads the database of the new workspace.
     */
    fun switchTo(id: String) {
        registry.setActive(id)
        restartApp()
    }

    /**
     * Starts sharing [workspaceId] through the (empty) file at [location].
     */
    suspend fun share(
        workspaceId: String,
        location: String,
        memberName: String,
    ): SyncReport = mutex.withLock {
        val workspace = registry.find(workspaceId) ?: error("Unknown workspace")
        val existing = LedgerSynchronizer.readDocument(transports.create(location), json)
        if (existing != null) throw SharedLedgerException.AlreadyShared()
        stateStore(workspaceId).save(
            SharingState(
                ledgerId = UUID.randomUUID().toString(),
                ledgerName = workspace.name,
                location = location,
                deviceId = deviceId(),
                memberName = memberName,
            )
        )
        syncLocked(workspace)
    }

    /**
     * Joins a workspace somebody else shared: creates a new local workspace
     * and downloads its data into it.
     */
    suspend fun join(location: String, memberName: String): Workspace = mutex.withLock {
        val document = LedgerSynchronizer.readDocument(transports.create(location), json)
            ?: throw SharedLedgerException.InvalidDocument("the file is empty")
        registry.all().firstOrNull { stateStore(it.id).loadBlocking()?.ledgerId == document.ledgerId }
            ?.let { return@withLock it }

        val workspace = withContext(Dispatchers.IO) { registry.create(document.name) }
        seedSettings(workspace)
        stateStore(workspace.id).save(
            SharingState(
                ledgerId = document.ledgerId,
                ledgerName = document.name,
                location = location,
                deviceId = deviceId(),
                memberName = memberName,
            )
        )
        syncLocked(workspace)
        workspace
    }

    suspend fun stopSharing(workspaceId: String) = mutex.withLock {
        stateStore(workspaceId).clear()
    }

    suspend fun sync(workspaceId: String): SyncReport = mutex.withLock {
        val workspace = registry.find(workspaceId) ?: error("Unknown workspace")
        syncLocked(workspace)
    }

    /**
     * Syncs every shared workspace.
     * @return true if all of them synced successfully.
     */
    suspend fun syncAll(): Boolean {
        var success = true
        registry.all()
            .filter { stateStore(it.id).exists() }
            .forEach { workspace ->
                try {
                    sync(workspace.id)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync workspace ${workspace.name}")
                    success = false
                }
            }
        return success
    }

    private suspend fun syncLocked(workspace: Workspace): SyncReport {
        val stateStore = stateStore(workspace.id)
        val state = stateStore.load() ?: throw SharedLedgerException.NotShared()
        return try {
            val report = withDatabase(workspace) { db ->
                LedgerSynchronizer(
                    transport = transports.create(state.location),
                    store = RoomLocalLedgerStore(db, json),
                    stateStore = stateStore,
                    json = json,
                ).sync()
            }
            if (registry.active().id == workspace.id && (report.received > 0 || report.deleted > 0)) {
                dataObserver.post(DataWriteEvent.AllDataChange)
            }
            report
        } catch (e: Exception) {
            stateStore.load()?.let { stateStore.save(it.copy(lastError = e.message ?: e.javaClass.simpleName)) }
            throw e
        }
    }

    private suspend fun <T> withDatabase(
        workspace: Workspace,
        block: suspend (IvyRoomDatabase) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (workspace.id == registry.active().id) {
            block(activeDb)
        } else {
            val db = IvyRoomDatabase.create(context, workspace.dbName)
            try {
                block(db)
            } finally {
                db.close()
            }
        }
    }

    /**
     * A new workspace starts with the settings (base currency, etc.) of the active one.
     */
    private suspend fun seedSettings(workspace: Workspace) = withContext(Dispatchers.IO) {
        val settings = activeDb.settingsDao.findAll()
        withDatabase(workspace) { db ->
            if (db.settingsDao.findAll().isEmpty()) {
                db.writeSettingsDao.saveMany(settings)
            }
            Unit
        }
    }

    private fun stateStore(workspaceId: String) =
        FileSharingStateStore(File(dir, "$workspaceId.json"), json)

    @Synchronized
    private fun deviceId(): String {
        val file = File(dir, "device_id")
        if (file.exists()) {
            file.readText().trim().takeIf { it.isNotBlank() }?.let { return it }
        }
        dir.mkdirs()
        return UUID.randomUUID().toString().also { file.writeText(it) }
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) {
            context.startActivity(Intent.makeRestartActivityTask(intent.component))
        }
        exitProcess(0)
    }

    companion object {
        fun persistUriPermission(context: Context, uri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                Timber.w(e, "Can't persist access to $uri")
            }
        }
    }
}
