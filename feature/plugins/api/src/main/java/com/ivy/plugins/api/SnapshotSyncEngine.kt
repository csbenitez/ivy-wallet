package com.ivy.plugins.api

import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.backup.BackupDataUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A remote location that stores a single snapshot of the whole wallet,
 * encoded with the same JSON format as Ivy Wallet's backups.
 */
interface RemoteSnapshotStore {
    /** Metadata of the current remote snapshot, or null if there's none yet. */
    suspend fun current(): RemoteSnapshot?

    suspend fun download(snapshot: RemoteSnapshot): String

    /**
     * Uploads [content], replacing [existing] if not null.
     * @return the metadata of the snapshot after the upload.
     */
    suspend fun upload(content: String, existing: RemoteSnapshot?): RemoteSnapshot
}

@JvmInline
value class RemoteSnapshotId(val value: String)

/**
 * @param revision changes every time the remote snapshot changes.
 */
// Already a typed id (value class); the rule only looks at the property name.
@Suppress("DataClassTypedIDs")
data class RemoteSnapshot(
    val id: RemoteSnapshotId,
    val revision: String,
)

/**
 * Reusable sync strategy for plugins that store the whole wallet as one file
 * (Google Drive, Dropbox, WebDAV, ...).
 *
 * Two-way sync works like this:
 * 1. If the remote snapshot changed since this device last saw it (another
 *    device uploaded), it's downloaded and merged into the local database.
 *    Merging is an upsert by id, the same as restoring a backup.
 * 2. The merged local data is uploaded, so the remote copy has both sides.
 *
 * Known limitation: deletions aren't tracked, so an item deleted on one
 * device can come back if another device still has it and syncs.
 */
@Singleton
class SnapshotSyncEngine @Inject constructor(
    private val backupDataUseCase: BackupDataUseCase,
    private val dataObserver: DataObserver,
    private val preferences: PluginPreferences,
) {
    private val mutex = Mutex()

    @Suppress("TooGenericExceptionCaught")
    suspend fun sync(
        pluginId: PluginId,
        store: RemoteSnapshotStore,
        mode: SyncMode,
    ): SyncResult = mutex.withLock {
        try {
            val result = when (mode) {
                SyncMode.TWO_WAY -> twoWay(pluginId, store)
                SyncMode.PUSH_ONLY -> push(pluginId, store, store.current())
                SyncMode.PULL_ONLY -> pull(pluginId, store)
            }
            if (result is SyncResult.Success) {
                preferences.setLong(pluginId, KEY_LAST_SYNC_AT, System.currentTimeMillis())
                preferences.setString(pluginId, KEY_LAST_ERROR, null)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Plugin '$pluginId' sync failed")
            val message = e.message ?: e::class.java.simpleName
            preferences.setString(pluginId, KEY_LAST_ERROR, message)
            SyncResult.Error(message)
        }
    }

    private suspend fun twoWay(pluginId: PluginId, store: RemoteSnapshotStore): SyncResult {
        val remote = store.current()
        val lastSeenRevision = preferences.getString(pluginId, KEY_LAST_REVISION)
        val pulled = if (remote != null && remote.revision != lastSeenRevision) {
            merge(store.download(remote))
            true
        } else {
            false
        }
        push(pluginId, store, remote)
        return SyncResult.Success(pulledRemoteChanges = pulled, pushedLocalChanges = true)
    }

    private suspend fun push(
        pluginId: PluginId,
        store: RemoteSnapshotStore,
        existing: RemoteSnapshot?,
    ): SyncResult {
        val uploaded = store.upload(backupDataUseCase.generateJsonBackup(), existing)
        preferences.setString(pluginId, KEY_LAST_REVISION, uploaded.revision)
        return SyncResult.Success(pulledRemoteChanges = false, pushedLocalChanges = true)
    }

    private suspend fun pull(pluginId: PluginId, store: RemoteSnapshotStore): SyncResult {
        val remote = store.current() ?: return SyncResult.NothingToRestore
        merge(store.download(remote))
        preferences.setString(pluginId, KEY_LAST_REVISION, remote.revision)
        return SyncResult.Success(pulledRemoteChanges = true, pushedLocalChanges = false)
    }

    private suspend fun merge(json: String) {
        try {
            backupDataUseCase.importJson(json)
        } finally {
            dataObserver.post(DataWriteEvent.AllDataChange)
        }
    }

    /** Forgets what this device knows about the remote copy (e.g. on disconnect). */
    suspend fun reset(pluginId: PluginId) {
        preferences.setString(pluginId, KEY_LAST_REVISION, null)
        preferences.setString(pluginId, KEY_LAST_ERROR, null)
        preferences.setLong(pluginId, KEY_LAST_SYNC_AT, null)
    }

    companion object {
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_REVISION = "last_remote_revision"
    }
}
