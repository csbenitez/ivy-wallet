package com.ivy.plugins.gdrive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ivy.plugins.api.IvyPlugin
import com.ivy.plugins.api.PluginCategory
import com.ivy.plugins.api.PluginMetadata
import com.ivy.plugins.api.PluginPreferences
import com.ivy.plugins.api.SnapshotSyncEngine
import com.ivy.plugins.api.SyncMode
import com.ivy.plugins.api.SyncPlugin
import com.ivy.plugins.api.SyncResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDrivePlugin @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val auth: GoogleDriveAuth,
    private val api: GoogleDriveApi,
    private val syncEngine: SnapshotSyncEngine,
    private val preferences: PluginPreferences,
) : SyncPlugin {

    override val metadata = PluginMetadata(
        id = ID,
        name = "Google Drive",
        description = context.getString(R.string.gdrive_plugin_description),
        author = "Ivy Wallet",
        version = "1.0.0",
        category = PluginCategory.SYNC,
    )

    @Composable
    override fun SettingsContent(modifier: Modifier) {
        GoogleDriveSettings(modifier = modifier)
    }

    override suspend fun isConnected(): Boolean =
        preferences.getBoolean(ID, KEY_CONNECTED, default = false)

    override suspend fun sync(mode: SyncMode): SyncResult {
        val token = if (isConnected()) auth.silentAccessToken() else null
        return if (token != null) {
            syncEngine.sync(
                pluginId = ID,
                store = GoogleDriveSnapshotStore(api, token),
                mode = mode,
            )
        } else {
            SyncResult.NotConnected
        }
    }

    /** Call once the user granted access and we have an [accessToken]. */
    suspend fun onConnected(accessToken: String) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val email = try {
            api.userEmail(accessToken)
        } catch (e: Exception) {
            null
        }
        preferences.setBoolean(ID, KEY_CONNECTED, true)
        preferences.setString(ID, KEY_ACCOUNT_EMAIL, email)
    }

    suspend fun disconnect() {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            auth.silentAccessToken()?.let { api.revoke(it) }
        } catch (e: Exception) {
            // Best effort: the user can also remove access from their Google account
        }
        preferences.setBoolean(ID, KEY_CONNECTED, false)
        preferences.setString(ID, KEY_ACCOUNT_EMAIL, null)
        syncEngine.reset(ID)
    }

    companion object {
        const val ID = "google-drive"
        const val KEY_CONNECTED = "connected"
        const val KEY_ACCOUNT_EMAIL = "account_email"
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface GoogleDrivePluginModule {
    @Binds
    @IntoSet
    fun bindPlugin(plugin: GoogleDrivePlugin): IvyPlugin
}
