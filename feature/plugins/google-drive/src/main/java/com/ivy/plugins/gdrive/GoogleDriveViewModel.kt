package com.ivy.plugins.gdrive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.plugins.api.PluginPreferences
import com.ivy.plugins.api.SnapshotSyncEngine
import com.ivy.plugins.api.SyncMode
import com.ivy.plugins.api.SyncResult
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@Immutable
data class GoogleDriveUiState(
    val connected: Boolean,
    val accountEmail: String?,
    val lastSync: String?,
    val lastError: String?,
    val busy: Boolean,
    val message: String?,
    val pendingConsent: PendingIntent?,
)

sealed interface GoogleDriveUiEvent {
    data object Connect : GoogleDriveUiEvent
    data object ConsentLaunched : GoogleDriveUiEvent
    data class ConsentResult(val data: Intent?) : GoogleDriveUiEvent
    data object Disconnect : GoogleDriveUiEvent
    data class Sync(val mode: SyncMode) : GoogleDriveUiEvent
}

@Stable
@SuppressLint("StaticFieldLeak")
@HiltViewModel
class GoogleDriveViewModel @Inject constructor(
    private val plugin: GoogleDrivePlugin,
    private val auth: GoogleDriveAuth,
    private val preferences: PluginPreferences,
    @ApplicationContext
    private val context: Context,
) : ComposeViewModel<GoogleDriveUiState, GoogleDriveUiEvent>() {

    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var pendingConsent by mutableStateOf<PendingIntent?>(null)

    private val id = GoogleDrivePlugin.ID

    @Composable
    override fun uiState(): GoogleDriveUiState {
        val connected by remember {
            preferences.booleanFlow(id, GoogleDrivePlugin.KEY_CONNECTED, default = false)
        }.collectAsState(initial = false)
        val email by remember {
            preferences.stringFlow(id, GoogleDrivePlugin.KEY_ACCOUNT_EMAIL)
        }.collectAsState(initial = null)
        val lastSyncAt by remember {
            preferences.longFlow(id, SnapshotSyncEngine.KEY_LAST_SYNC_AT)
        }.collectAsState(initial = null)
        val lastError by remember {
            preferences.stringFlow(id, SnapshotSyncEngine.KEY_LAST_ERROR)
        }.collectAsState(initial = null)

        return GoogleDriveUiState(
            connected = connected,
            accountEmail = email,
            lastSync = lastSyncAt?.let(::formatTime),
            lastError = lastError,
            busy = busy,
            message = message,
            pendingConsent = pendingConsent,
        )
    }

    override fun onEvent(event: GoogleDriveUiEvent) {
        when (event) {
            GoogleDriveUiEvent.Connect -> runBusy { connect() }
            GoogleDriveUiEvent.ConsentLaunched -> pendingConsent = null
            is GoogleDriveUiEvent.ConsentResult -> runBusy {
                finishConnect(auth.resultFromIntent(event.data).accessToken)
            }

            GoogleDriveUiEvent.Disconnect -> runBusy {
                plugin.disconnect()
                message = null
            }

            is GoogleDriveUiEvent.Sync -> runBusy { sync(event.mode) }
        }
    }

    private suspend fun connect() {
        val result = auth.authorize()
        val consentIntent = result.pendingIntent
        if (result.hasResolution() && consentIntent != null) {
            pendingConsent = consentIntent
        } else {
            finishConnect(result.accessToken)
        }
    }

    private suspend fun finishConnect(accessToken: String?) {
        requireNotNull(accessToken) { "Google didn't return an access token" }
        plugin.onConnected(accessToken)
        // First sync right away so the user sees it working
        sync(SyncMode.TWO_WAY)
    }

    private suspend fun sync(mode: SyncMode) {
        message = when (val result = plugin.sync(mode)) {
            is SyncResult.Success -> context.getString(
                when (mode) {
                    SyncMode.TWO_WAY -> R.string.gdrive_sync_done
                    SyncMode.PUSH_ONLY -> R.string.gdrive_backup_done
                    SyncMode.PULL_ONLY -> R.string.gdrive_restore_done
                }
            )

            SyncResult.NotConnected -> context.getString(R.string.gdrive_needs_reconnect)
            SyncResult.NothingToRestore -> context.getString(R.string.gdrive_nothing_to_restore)
            is SyncResult.Error -> context.getString(R.string.gdrive_sync_failed, result.message)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runBusy(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = context.getString(
                    R.string.gdrive_connect_failed,
                    e.message ?: e::class.java.simpleName
                )
            } finally {
                busy = false
            }
        }
    }

    private fun formatTime(epochMillis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
}
