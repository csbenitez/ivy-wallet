package com.ivy.plugins.gdrive

import com.ivy.plugins.api.RemoteSnapshot
import com.ivy.plugins.api.RemoteSnapshotStore

/** Stores the wallet as a single JSON file in the Drive app data folder. */
class GoogleDriveSnapshotStore(
    private val api: GoogleDriveApi,
    private val accessToken: String,
) : RemoteSnapshotStore {

    override suspend fun current(): RemoteSnapshot? =
        api.findFile(accessToken, FILE_NAME)?.toSnapshot()

    override suspend fun download(snapshot: RemoteSnapshot): String =
        api.download(accessToken, snapshot.id)

    override suspend fun upload(content: String, existing: RemoteSnapshot?): RemoteSnapshot {
        val file = if (existing != null) {
            api.update(accessToken, existing.id, content)
        } else {
            api.create(accessToken, FILE_NAME, content)
        }
        return file.toSnapshot()
    }

    private fun DriveFile.toSnapshot() = RemoteSnapshot(
        id = id,
        revision = version ?: modifiedTime ?: id,
    )

    companion object {
        const val FILE_NAME = "ivy-wallet-sync.json"
    }
}
