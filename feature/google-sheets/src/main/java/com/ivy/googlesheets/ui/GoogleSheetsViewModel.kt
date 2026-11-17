package com.ivy.googlesheets.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.googlesheets.GoogleSheetsSync
import com.ivy.googlesheets.auth.GoogleSheetsAuth
import com.ivy.googlesheets.data.GoogleSheetsSyncStore
import com.ivy.googlesheets.data.SheetLink
import com.ivy.googlesheets.remote.SheetsException
import com.ivy.googlesheets.sync.SheetMapping
import com.ivy.googlesheets.sync.SheetTable
import com.ivy.googlesheets.sync.SpreadsheetRef
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GoogleSheetsViewModel @Inject constructor(
    private val sheetsSync: GoogleSheetsSync,
    private val store: GoogleSheetsSyncStore,
    private val auth: GoogleSheetsAuth,
) : ComposeViewModel<GoogleSheetsState, GoogleSheetsEvent>() {

    private var state by mutableStateOf(GoogleSheetsState.Initial)
    private var spreadsheetId: String? = null
    private var table: SheetTable? = null

    /** The action to retry once the user grants Google access. */
    private var pendingAction: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            val link = store.currentLink()
            state = if (link != null) {
                spreadsheetId = link.spreadsheetId
                state.copy(
                    step = GoogleSheetsState.Step.LINKED,
                    link = link,
                    mapping = link.mapping,
                    urlInput = SpreadsheetRef.url(link.spreadsheetId),
                    spreadsheetTitle = link.spreadsheetTitle,
                    selectedTab = link.tab,
                    lastSyncEpochMillis = store.ledger().lastSyncEpochMillis,
                    loading = false,
                )
            } else {
                state.copy(loading = false)
            }
        }
    }

    @Composable
    override fun uiState(): GoogleSheetsState = state

    override fun onEvent(event: GoogleSheetsEvent) {
        when (event) {
            is GoogleSheetsEvent.UrlChanged -> state = state.copy(urlInput = event.value, error = null)
            GoogleSheetsEvent.Connect -> connect()
            is GoogleSheetsEvent.TabSelected -> loadPreview(event.tab)
            is GoogleSheetsEvent.FieldMapped -> updateMapping { it.with(event.field, event.column) }
            is GoogleSheetsEvent.TypeModeChanged -> updateMapping { it.copy(typeMode = event.mode) }
            is GoogleSheetsEvent.DateOrderChanged -> updateMapping { it.copy(dateOrder = event.order) }
            is GoogleSheetsEvent.FixedAccountChanged ->
                updateMapping { it.copy(fixedAccountName = event.name) }

            is GoogleSheetsEvent.KeywordsChanged -> updateMapping {
                it.copy(
                    incomeKeyword = event.income,
                    expenseKeyword = event.expense,
                    transferKeyword = event.transfer,
                )
            }

            GoogleSheetsEvent.SaveMappingAndSync -> saveMappingAndSync()
            GoogleSheetsEvent.SyncNow -> runGuarded { syncNow() }
            GoogleSheetsEvent.EditMapping -> loadPreview(state.link?.tab)
            GoogleSheetsEvent.Unlink -> viewModelScope.launch {
                sheetsSync.unlink()
                spreadsheetId = null
                table = null
                state = GoogleSheetsState.Initial.copy(loading = false)
            }

            GoogleSheetsEvent.ConsentLaunched -> state = state.copy(consentRequest = null)
            is GoogleSheetsEvent.ConsentResult -> onConsentResult(event)
            GoogleSheetsEvent.DismissError -> state = state.copy(error = null)
        }
    }

    private fun connect() {
        val id = SpreadsheetRef.parseId(state.urlInput)
        if (id == null) {
            state = state.copy(error = SheetsError.INVALID_URL)
            return
        }
        spreadsheetId = id
        loadPreview(tab = null)
    }

    private fun loadPreview(tab: String?) {
        val id = spreadsheetId ?: return
        runGuarded {
            val preview = sheetsSync.preview(id, tab)
            table = preview.table
            val savedMapping = state.link?.mapping?.takeIf {
                state.link?.spreadsheetId == id && state.link?.tab == preview.tab
            }
            val mapping = savedMapping ?: preview.suggestedMapping
            state = state.copy(
                step = GoogleSheetsState.Step.MAPPING,
                spreadsheetTitle = preview.info.title,
                tabs = preview.info.tabs.toImmutableList(),
                selectedTab = preview.tab,
                header = preview.table.header.toImmutableList(),
                sampleRows = preview.table.sample()
                    .map { it.toImmutableList() }
                    .toImmutableList(),
                mapping = mapping,
                missingFields = mapping.missingFields().toImmutableList(),
                error = if (preview.table.header.isEmpty()) SheetsError.EMPTY_SHEET else null,
            )
        }
    }

    private fun updateMapping(transform: (SheetMapping) -> SheetMapping) {
        val mapping = transform(state.mapping)
        state = state.copy(
            mapping = mapping,
            missingFields = mapping.missingFields().toImmutableList(),
        )
    }

    private fun saveMappingAndSync() {
        val id = spreadsheetId
        val tab = state.selectedTab
        if (id == null || tab == null || !state.mapping.isComplete) return
        runGuarded {
            val link = SheetLink(
                spreadsheetId = id,
                spreadsheetTitle = state.spreadsheetTitle.orEmpty(),
                tab = tab,
                mapping = state.mapping,
            )
            sheetsSync.link(link)
            state = state.copy(step = GoogleSheetsState.Step.LINKED, link = link)
            syncNow()
        }
    }

    private suspend fun syncNow() {
        val report = sheetsSync.sync()
        state = state.copy(
            lastReport = report,
            lastSyncEpochMillis = store.ledger().lastSyncEpochMillis,
        )
    }

    private fun onConsentResult(event: GoogleSheetsEvent.ConsentResult) {
        val action = pendingAction
        pendingAction = null
        val granted = event.granted && auth.handleConsentResult(event.data)
        if (granted && action != null) {
            runGuarded(action)
        } else {
            state = state.copy(loading = false, error = SheetsError.CONSENT_DENIED)
        }
    }

    private fun runGuarded(action: suspend () -> Unit) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                action()
                state = state.copy(loading = false)
            } catch (e: SheetsException.NeedsConsent) {
                pendingAction = action
                state = state.copy(consentRequest = e.pendingIntent)
            } catch (e: SheetsException) {
                Timber.w(e, "Google Sheets sync failed")
                state = state.copy(loading = false, error = e.toUiError())
            } catch (e: IllegalStateException) {
                Timber.e(e, "Google Sheets sync failed")
                state = state.copy(loading = false, error = SheetsError.UNKNOWN)
            } catch (e: java.io.IOException) {
                Timber.w(e, "Google Sheets network error")
                state = state.copy(loading = false, error = SheetsError.NETWORK)
            }
        }
    }

    private fun SheetsException.toUiError(): SheetsError = when (this) {
        is SheetsException.NotFound -> SheetsError.NOT_FOUND
        is SheetsException.PermissionDenied -> SheetsError.PERMISSION_DENIED
        is SheetsException.Network -> SheetsError.NETWORK
        is SheetsException.Unauthorized, is SheetsException.NeedsConsent -> SheetsError.CONSENT_DENIED
        is SheetsException.Api -> SheetsError.UNKNOWN
    }
}
