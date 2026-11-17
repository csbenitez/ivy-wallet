package com.ivy.sharedwallet.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel
import com.ivy.sharedwallet.R
import com.ivy.sharedwallet.data.SharedWorkspacesManager

@Composable
fun SharedWalletScreenImpl(
    modifier: Modifier = Modifier,
    viewModel: SharedWalletViewModel = screenScopedViewModel(),
) {
    SharedWalletUi(
        uiState = viewModel.uiState(),
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/**
 * Dialogs of the screen. Only one can be open at a time.
 */
private sealed interface DialogState {
    data object Create : DialogState
    data class Rename(val item: WorkspaceItem) : DialogState
    data class Delete(val item: WorkspaceItem) : DialogState
    data class Switch(val item: WorkspaceItem) : DialogState
    data class StopSharing(val item: WorkspaceItem) : DialogState
    data class ShareName(val item: WorkspaceItem) : DialogState
    data object JoinName : DialogState
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedWalletUi(
    uiState: SharedWalletUiState,
    onEvent: (SharedWalletUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var pendingShareId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMemberName by rememberSaveable { mutableStateOf("") }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val id = pendingShareId
        if (uri != null && id != null) {
            SharedWorkspacesManager.persistUriPermission(context, uri)
            onEvent(SharedWalletUiEvent.Share(id, uri.toString(), pendingMemberName))
        }
        pendingShareId = null
    }
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SharedWorkspacesManager.persistUriPermission(context, uri)
            onEvent(SharedWalletUiEvent.Join(uri.toString(), pendingMemberName))
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.showSnackbar(it)
            currentOnEvent(SharedWalletUiEvent.DismissMessage)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.sw_title)) },
                navigationIcon = { BackButton() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            if (uiState.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(R.string.sw_intro), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sw_how_to), style = MaterialTheme.typography.bodySmall)
                }
                items(uiState.workspaces, key = { it.id }) { item ->
                    WorkspaceCard(
                        item = item,
                        enabled = !uiState.busy,
                        onOpen = { dialog = DialogState.Switch(item) },
                        onShare = { dialog = DialogState.ShareName(item) },
                        onSync = { onEvent(SharedWalletUiEvent.Sync(item.id)) },
                        onStopSharing = { dialog = DialogState.StopSharing(item) },
                        onRename = { dialog = DialogState.Rename(item) },
                        onDelete = { dialog = DialogState.Delete(item) },
                    )
                }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.busy,
                        onClick = { dialog = DialogState.Create },
                    ) { Text(stringResource(R.string.sw_new_workspace)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.busy,
                        onClick = { dialog = DialogState.JoinName },
                    ) { Text(stringResource(R.string.sw_join)) }
                }
            }
        }

        val dismiss = { dialog = null }
        when (val current = dialog) {
            null -> Unit
            DialogState.Create -> TextInputDialog(
                title = stringResource(R.string.sw_new_workspace),
                label = stringResource(R.string.sw_workspace_name),
                initial = "",
                confirm = stringResource(R.string.sw_create),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    onEvent(SharedWalletUiEvent.Create(it))
                },
            )

            is DialogState.Rename -> TextInputDialog(
                title = stringResource(R.string.sw_rename),
                label = stringResource(R.string.sw_workspace_name),
                initial = current.item.name,
                confirm = stringResource(R.string.sw_ok),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    onEvent(SharedWalletUiEvent.Rename(current.item.id, it))
                },
            )

            is DialogState.ShareName -> TextInputDialog(
                title = stringResource(R.string.sw_member_name_title),
                label = stringResource(R.string.sw_member_name_hint),
                initial = uiState.memberName,
                confirm = stringResource(R.string.sw_continue),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    pendingMemberName = it
                    pendingShareId = current.item.id
                    createFile.launch("${current.item.name}.ivyshared.json")
                },
            )

            DialogState.JoinName -> TextInputDialog(
                title = stringResource(R.string.sw_member_name_title),
                label = stringResource(R.string.sw_member_name_hint),
                initial = uiState.memberName,
                confirm = stringResource(R.string.sw_continue),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    pendingMemberName = it
                    openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                },
            )

            is DialogState.Switch -> ConfirmDialog(
                title = stringResource(R.string.sw_switch_title, current.item.name),
                text = stringResource(R.string.sw_switch_text),
                confirm = stringResource(R.string.sw_open),
                onDismiss = dismiss,
                onConfirm = { onEvent(SharedWalletUiEvent.SwitchTo(current.item.id)) },
            )

            is DialogState.Delete -> ConfirmDialog(
                title = stringResource(R.string.sw_delete_title, current.item.name),
                text = stringResource(R.string.sw_delete_text),
                confirm = stringResource(R.string.sw_delete),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    onEvent(SharedWalletUiEvent.Delete(current.item.id))
                },
            )

            is DialogState.StopSharing -> ConfirmDialog(
                title = stringResource(R.string.sw_stop_title, current.item.name),
                text = stringResource(R.string.sw_stop_text),
                confirm = stringResource(R.string.sw_stop_sharing),
                onDismiss = dismiss,
                onConfirm = {
                    dismiss()
                    onEvent(SharedWalletUiEvent.StopSharing(current.item.id))
                },
            )
        }

        uiState.joined?.let { joined ->
            ConfirmDialog(
                title = stringResource(R.string.sw_joined_title, joined.name),
                text = stringResource(R.string.sw_joined_text),
                confirm = stringResource(R.string.sw_open),
                onDismiss = { onEvent(SharedWalletUiEvent.DismissJoined) },
                onConfirm = { onEvent(SharedWalletUiEvent.SwitchTo(joined.id)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceCard(
    item: WorkspaceItem,
    enabled: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSync: () -> Unit,
    onStopSharing: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (item.isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sw_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (item.isShared) {
                    stringResource(R.string.sw_shared_in, item.fileName.orEmpty())
                } else {
                    stringResource(R.string.sw_not_shared)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.isShared) {
                item.members?.let {
                    Text(stringResource(R.string.sw_members, it), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = item.lastSync?.let { stringResource(R.string.sw_last_sync, it) }
                        ?: stringResource(R.string.sw_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                )
                item.error?.let {
                    Text(
                        text = stringResource(R.string.sw_sync_error, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!item.isActive) {
                    TextButton(enabled = enabled, onClick = onOpen) { Text(stringResource(R.string.sw_open)) }
                }
                if (item.isShared) {
                    TextButton(enabled = enabled, onClick = onSync) { Text(stringResource(R.string.sw_sync_now)) }
                    TextButton(enabled = enabled, onClick = onStopSharing) {
                        Text(stringResource(R.string.sw_stop_sharing))
                    }
                } else {
                    TextButton(enabled = enabled, onClick = onShare) { Text(stringResource(R.string.sw_share)) }
                }
                TextButton(enabled = enabled, onClick = onRename) { Text(stringResource(R.string.sw_rename)) }
                if (item.canDelete) {
                    TextButton(enabled = enabled, onClick = onDelete) { Text(stringResource(R.string.sw_delete)) }
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sw_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sw_cancel)) } },
    )
}

@Composable
private fun BackButton() {
    val nav = navigation()
    IconButton(onClick = { nav.back() }) {
        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
    }
}
