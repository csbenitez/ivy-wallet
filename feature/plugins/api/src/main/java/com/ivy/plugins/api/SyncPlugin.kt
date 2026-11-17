package com.ivy.plugins.api

/**
 * A plugin that synchronises Ivy Wallet's data with an external service
 * (Google Drive, Google Sheets, a shared household wallet, etc.).
 *
 * Enabled sync plugins are run periodically in the background by the plugin
 * manager and can also be triggered manually from their settings UI.
 */
interface SyncPlugin : IvyPlugin {
    /** Whether the plugin has everything it needs (e.g. a linked account) to sync. */
    suspend fun isConnected(): Boolean

    suspend fun sync(mode: SyncMode = SyncMode.TWO_WAY): SyncResult
}

enum class SyncMode {
    /** Merge remote changes into the device, then upload the merged data. */
    TWO_WAY,

    /** Only upload the device's data, replacing the remote copy. */
    PUSH_ONLY,

    /** Only merge the remote copy into the device. */
    PULL_ONLY,
}

sealed interface SyncResult {
    data class Success(
        val pulledRemoteChanges: Boolean,
        val pushedLocalChanges: Boolean,
    ) : SyncResult

    /** The plugin isn't linked to an account (or access was revoked). */
    data object NotConnected : SyncResult

    /** There's no remote copy yet (only possible for [SyncMode.PULL_ONLY]). */
    data object NothingToRestore : SyncResult

    data class Error(val message: String) : SyncResult
}
