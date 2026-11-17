@file:Suppress("DataClassTypedIDs")

package com.ivy.sharedwallet.ui

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class SharedWalletUiState(
    val workspaces: ImmutableList<WorkspaceItem>,
    val busy: Boolean,
    val message: String?,
    val memberName: String,
    val joined: WorkspaceItem?,
)

@Immutable
data class WorkspaceItem(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val canDelete: Boolean,
    val isShared: Boolean,
    val fileName: String?,
    val members: String?,
    val lastSync: String?,
    val error: String?,
)

sealed interface SharedWalletUiEvent {
    data class Create(val name: String) : SharedWalletUiEvent
    data class Rename(val id: String, val name: String) : SharedWalletUiEvent
    data class Delete(val id: String) : SharedWalletUiEvent
    data class SwitchTo(val id: String) : SharedWalletUiEvent
    data class Share(val id: String, val location: String, val memberName: String) : SharedWalletUiEvent
    data class Join(val location: String, val memberName: String) : SharedWalletUiEvent
    data class StopSharing(val id: String) : SharedWalletUiEvent
    data class Sync(val id: String) : SharedWalletUiEvent
    data object DismissMessage : SharedWalletUiEvent
    data object DismissJoined : SharedWalletUiEvent
}
