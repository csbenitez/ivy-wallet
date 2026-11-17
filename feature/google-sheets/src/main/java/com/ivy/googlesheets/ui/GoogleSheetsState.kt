package com.ivy.googlesheets.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.runtime.Immutable
import com.ivy.googlesheets.GoogleSheetsSync
import com.ivy.googlesheets.data.SheetLink
import com.ivy.googlesheets.sync.SheetMapping
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class GoogleSheetsState(
    val step: Step,
    /** The Google Picker page to show, if the user is picking a spreadsheet. */
    val pickerHtml: String?,
    val loading: Boolean,
    val error: SheetsError?,
    val spreadsheetTitle: String?,
    val tabs: ImmutableList<String>,
    val selectedTab: String?,
    val header: ImmutableList<String>,
    val sampleRows: ImmutableList<ImmutableList<String>>,
    val mapping: SheetMapping,
    val missingFields: ImmutableList<SheetMapping.Field>,
    val link: SheetLink?,
    val lastSyncEpochMillis: Long?,
    val lastReport: GoogleSheetsSync.Report?,
    val consentRequest: PendingIntent?,
) {
    enum class Step { PICK_SHEET, MAPPING, LINKED }

    companion object {
        val Initial = GoogleSheetsState(
            step = Step.PICK_SHEET,
            pickerHtml = null,
            loading = true,
            error = null,
            spreadsheetTitle = null,
            tabs = persistentListOf(),
            selectedTab = null,
            header = persistentListOf(),
            sampleRows = persistentListOf(),
            mapping = SheetMapping.Empty,
            missingFields = persistentListOf(),
            link = null,
            lastSyncEpochMillis = null,
            lastReport = null,
            consentRequest = null,
        )
    }
}

enum class SheetsError {
    PICKER_NOT_CONFIGURED, PICKER_FAILED, NOT_FOUND, PERMISSION_DENIED, NETWORK, CONSENT_DENIED, EMPTY_SHEET, UNKNOWN
}

sealed interface GoogleSheetsEvent {
    data object OpenPicker : GoogleSheetsEvent
    data class SheetPicked(val spreadsheetId: String, val name: String) : GoogleSheetsEvent
    data object PickerCancelled : GoogleSheetsEvent
    data class PickerFailed(val message: String) : GoogleSheetsEvent
    data class TabSelected(val tab: String) : GoogleSheetsEvent
    data class FieldMapped(val field: SheetMapping.Field, val column: Int) : GoogleSheetsEvent
    data class TypeModeChanged(val mode: SheetMapping.TypeMode) : GoogleSheetsEvent
    data class DateOrderChanged(val order: SheetMapping.DateOrder) : GoogleSheetsEvent
    data class FixedAccountChanged(val name: String) : GoogleSheetsEvent
    data class KeywordsChanged(
        val income: String,
        val expense: String,
        val transfer: String,
    ) : GoogleSheetsEvent

    data object SaveMappingAndSync : GoogleSheetsEvent
    data object SyncNow : GoogleSheetsEvent
    data object EditMapping : GoogleSheetsEvent
    data object Unlink : GoogleSheetsEvent
    data object ConsentLaunched : GoogleSheetsEvent
    data class ConsentResult(val granted: Boolean, val data: Intent?) : GoogleSheetsEvent
    data object DismissError : GoogleSheetsEvent
}
