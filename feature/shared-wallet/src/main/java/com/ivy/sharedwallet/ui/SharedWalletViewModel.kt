package com.ivy.sharedwallet.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.workspace.WorkspaceRegistry
import com.ivy.sharedwallet.R
import com.ivy.sharedwallet.data.SharedWorkspacesManager
import com.ivy.sharedwallet.data.WorkspaceInfo
import com.ivy.sharedwallet.engine.SharedLedgerException
import com.ivy.sharedwallet.work.SharedLedgerSyncScheduler
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@SuppressLint("StaticFieldLeak")
@HiltViewModel
class SharedWalletViewModel @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val manager: SharedWorkspacesManager,
    private val scheduler: SharedLedgerSyncScheduler,
) : ComposeViewModel<SharedWalletUiState, SharedWalletUiEvent>() {

    private var workspaces by mutableStateOf<ImmutableList<WorkspaceItem>>(persistentListOf())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var joined by mutableStateOf<WorkspaceItem?>(null)
    private var memberName by mutableStateOf("")

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        memberName = prefs.getString(KEY_MEMBER_NAME, null).orEmpty()
        refresh()
    }

    @Composable
    override fun uiState(): SharedWalletUiState = SharedWalletUiState(
        workspaces = workspaces,
        busy = busy,
        message = message,
        memberName = memberName,
        joined = joined,
    )

    override fun onEvent(event: SharedWalletUiEvent) {
        when (event) {
            is SharedWalletUiEvent.Create -> launchAction { manager.createWorkspace(event.name) }
            is SharedWalletUiEvent.Rename -> launchAction { manager.renameWorkspace(event.id, event.name) }
            is SharedWalletUiEvent.Delete -> launchAction { manager.deleteWorkspace(event.id) }
            is SharedWalletUiEvent.SwitchTo -> manager.switchTo(event.id)
            is SharedWalletUiEvent.Share -> launchAction {
                rememberMemberName(event.memberName)
                manager.share(event.id, event.location, event.memberName)
                scheduler.schedulePeriodic()
                message = context.getString(R.string.sw_shared_ok)
            }

            is SharedWalletUiEvent.Join -> launchAction {
                rememberMemberName(event.memberName)
                val workspace = manager.join(event.location, event.memberName)
                scheduler.schedulePeriodic()
                joined = load().firstOrNull { it.id == workspace.id }
            }

            is SharedWalletUiEvent.StopSharing -> launchAction { manager.stopSharing(event.id) }
            is SharedWalletUiEvent.Sync -> launchAction {
                val report = manager.sync(event.id)
                message = context.getString(R.string.sw_synced, report.received, report.deleted, report.sent)
            }

            SharedWalletUiEvent.DismissMessage -> message = null
            SharedWalletUiEvent.DismissJoined -> joined = null
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                action()
            } catch (e: Exception) {
                Timber.e(e, "Shared workspace action failed")
                message = e.toUserMessage()
            } finally {
                refresh()
                busy = false
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            workspaces = load().toImmutableList()
        }
    }

    private suspend fun load(): List<WorkspaceItem> = withContext(Dispatchers.IO) {
        manager.workspaces().map { it.toItem() }
    }

    private fun WorkspaceInfo.toItem(): WorkspaceItem {
        val sharing = sharing
        return WorkspaceItem(
            id = workspace.id,
            name = workspace.name,
            isActive = isActive,
            canDelete = !isActive && workspace.id != WorkspaceRegistry.DEFAULT_WORKSPACE_ID,
            isShared = sharing != null,
            fileName = fileName ?: sharing?.ledgerName,
            members = sharing?.members?.takeIf { it.isNotEmpty() }?.joinToString { it.name },
            lastSync = sharing?.lastSyncAt?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            },
            error = sharing?.lastError,
        )
    }

    private fun rememberMemberName(name: String) {
        memberName = name
        prefs.edit().putString(KEY_MEMBER_NAME, name).apply()
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is SharedLedgerException.InvalidDocument -> context.getString(R.string.sw_error_invalid)
        is SharedLedgerException.WrongLedger -> context.getString(R.string.sw_error_wrong)
        is SharedLedgerException.AlreadyShared -> context.getString(R.string.sw_error_already)
        is SharedLedgerException.UnsupportedVersion -> context.getString(R.string.sw_error_version)
        else -> context.getString(R.string.sw_error_generic, message ?: javaClass.simpleName)
    }

    companion object {
        private const val PREFS = "shared_wallet"
        private const val KEY_MEMBER_NAME = "member_name"
    }
}
