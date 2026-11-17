package com.ivy.googlesheets.sync

/**
 * Extracts a Google Sheets spreadsheet id from whatever the user pasted:
 * a full URL (https://docs.google.com/spreadsheets/d/<id>/edit#gid=0) or the raw id.
 */
object SpreadsheetRef {
    private val UrlRegex = Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)")
    private val IdRegex = Regex("^[a-zA-Z0-9-_]{20,}$")

    fun parseId(input: String): String? {
        val trimmed = input.trim()
        return UrlRegex.find(trimmed)?.groupValues?.get(1)
            ?: trimmed.takeIf { IdRegex.matches(it) }
    }

    fun url(spreadsheetId: String): String =
        "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
}

/** A Google Sheets spreadsheet (Drive file) id. */
@JvmInline
value class SpreadsheetId(val value: String)
