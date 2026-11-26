package com.ivy.googlesheets.remote

import com.ivy.googlesheets.sync.SheetTable

/** Minimal Google Sheets operations needed for sync. Kept as an interface to swap/fake it. */
interface SheetsApi {
    suspend fun spreadsheetInfo(spreadsheetId: String): SpreadsheetInfo

    suspend fun readTable(spreadsheetId: String, tab: String): SheetTable

    /** Appends rows after the last row of the tab's table. */
    suspend fun appendRows(spreadsheetId: String, tab: String, rows: List<List<Any>>)
}

data class SpreadsheetInfo(
    val title: String,
    val tabs: List<String>,
)

sealed class SheetsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user needs to (re)grant access in a Google consent screen. */
    class NeedsConsent(val pendingIntent: android.app.PendingIntent) :
        SheetsException("Google Sheets access needs user consent")

    class NotFound : SheetsException("Spreadsheet or tab not found")
    class PermissionDenied : SheetsException("The Google account has no access to this spreadsheet")
    class Unauthorized : SheetsException("Google authorization expired")
    class Network(cause: Throwable) : SheetsException(cause.message ?: "Network error", cause)
    class Api(val code: Int, body: String) : SheetsException("Google Sheets API error $code: $body")
}
