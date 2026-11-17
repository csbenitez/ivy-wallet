package com.ivy.plugins.gdrive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.plugins.api.SyncMode

@Composable
internal fun GoogleDriveSettings(
    modifier: Modifier = Modifier,
    viewModel: GoogleDriveViewModel = screenScopedViewModel(),
) {
    val uiState = viewModel.uiState()
    val onEvent = viewModel::onEvent

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onEvent(GoogleDriveUiEvent.ConsentResult(result.data))
    }
    LaunchedEffect(uiState.pendingConsent) {
        val pendingIntent = uiState.pendingConsent ?: return@LaunchedEffect
        onEvent(GoogleDriveUiEvent.ConsentLaunched)
        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    GoogleDriveSettingsContent(
        uiState = uiState,
        onEvent = onEvent,
        modifier = modifier,
    )
}

@Composable
private fun GoogleDriveSettingsContent(
    uiState: GoogleDriveUiState,
    onEvent: (GoogleDriveUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.connected) {
            ConnectedContent(uiState = uiState, onEvent = onEvent)
        } else {
            Text(
                text = stringResource(R.string.gdrive_not_connected),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { onEvent(GoogleDriveUiEvent.Connect) },
                enabled = !uiState.busy,
            ) {
                Text(stringResource(R.string.gdrive_connect))
            }
        }

        if (uiState.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.message?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectedContent(
    uiState: GoogleDriveUiState,
    onEvent: (GoogleDriveUiEvent) -> Unit,
    modifier: Modifier = Modifier,
): Unit = Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(
        text = uiState.accountEmail
            ?.let { stringResource(R.string.gdrive_connected_as, it) }
            ?: stringResource(R.string.gdrive_connected),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = uiState.lastSync
            ?.let { stringResource(R.string.gdrive_last_sync, it) }
            ?: stringResource(R.string.gdrive_never_synced),
        style = MaterialTheme.typography.bodySmall,
    )
    uiState.lastError?.let {
        Text(
            text = stringResource(R.string.gdrive_last_error, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        text = stringResource(R.string.gdrive_auto_sync_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onEvent(GoogleDriveUiEvent.Sync(SyncMode.TWO_WAY)) },
        enabled = !uiState.busy,
    ) {
        Text(stringResource(R.string.gdrive_sync_now))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = { onEvent(GoogleDriveUiEvent.Sync(SyncMode.PUSH_ONLY)) },
            enabled = !uiState.busy,
        ) {
            Text(stringResource(R.string.gdrive_backup_now))
        }
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = { onEvent(GoogleDriveUiEvent.Sync(SyncMode.PULL_ONLY)) },
            enabled = !uiState.busy,
        ) {
            Text(stringResource(R.string.gdrive_restore))
        }
    }
    TextButton(
        onClick = { onEvent(GoogleDriveUiEvent.Disconnect) },
        enabled = !uiState.busy,
    ) {
        Text(stringResource(R.string.gdrive_disconnect))
    }
}
