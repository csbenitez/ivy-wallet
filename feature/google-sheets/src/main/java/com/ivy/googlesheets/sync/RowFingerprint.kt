package com.ivy.googlesheets.sync

import java.security.MessageDigest

/**
 * Identifies a sheet row by its content so that a re-sync imports only rows
 * that haven't been imported before, even if the user sorts or inserts rows.
 *
 * Only the mapped columns take part, so edits to unrelated columns
 * (e.g. a "notes" or running-balance column) don't duplicate transactions.
 * Identical rows (same date, amount, ...) are told apart by their occurrence number.
 */
object RowFingerprint {
    private const val FINGERPRINT_LENGTH = 24

    fun fingerprints(rows: List<List<String>>, columns: List<Int>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return rows.map { row ->
            val key = columns.joinToString(separator = "\u001F") { index ->
                row.getOrElse(index) { "" }.trim().lowercase()
            }
            val occurrence = seen.getOrElse(key) { 0 }
            seen[key] = occurrence + 1
            sha256("$key\u001E$occurrence").take(FINGERPRINT_LENGTH)
        }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
