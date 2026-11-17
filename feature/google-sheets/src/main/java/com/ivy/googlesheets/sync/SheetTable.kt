package com.ivy.googlesheets.sync

import java.math.BigDecimal

/**
 * A plain, Android-free snapshot of a sheet tab: the first non-empty row is the header.
 * Every row is padded to the header width so that column indexes are stable.
 */
data class SheetTable(
    val header: List<String>,
    val rows: List<List<String>>,
) {
    val width: Int get() = header.size

    fun sample(size: Int = SAMPLE_SIZE): List<List<String>> = rows.take(size)

    companion object {
        const val SAMPLE_SIZE = 5
        private const val ALPHABET_SIZE = 26

        /**
         * Builds a table from raw cell values, as returned by the Sheets API
         * (`values.get` with `valueRenderOption=UNFORMATTED_VALUE`).
         * Cells may be strings, numbers or booleans; trailing empty cells are omitted by the API.
         */
        fun fromRawValues(values: List<List<Any?>>): SheetTable {
            val nonEmptyRows = values
                .map { row -> row.map(::cellToString) }
                .filter { row -> row.any { it.isNotBlank() } }
            if (nonEmptyRows.isEmpty()) return SheetTable(emptyList(), emptyList())

            val header = nonEmptyRows.first().map { it.trim() }
            val width = maxOf(header.size, nonEmptyRows.maxOf { it.size })
            val paddedHeader = header.pad(width).mapIndexed { index, name ->
                name.ifBlank { columnLetter(index) }
            }
            return SheetTable(
                header = paddedHeader,
                rows = nonEmptyRows.drop(1).map { it.pad(width).map(String::trim) },
            )
        }

        fun cellToString(cell: Any?): String = when (cell) {
            null -> ""
            is String -> cell
            is BigDecimal -> cell.stripTrailingZeros().toPlainString()
            is Double -> if (cell.isFinite()) {
                BigDecimal.valueOf(cell).stripTrailingZeros().toPlainString()
            } else {
                ""
            }

            is Float -> cellToString(cell.toDouble())
            is Number -> cell.toString()
            else -> cell.toString()
        }

        /** 0 -> A, 25 -> Z, 26 -> AA */
        fun columnLetter(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % ALPHABET_SIZE
                sb.append('A' + rem)
                n = (n - 1) / ALPHABET_SIZE
            }
            return sb.reverse().toString()
        }

        private fun List<String>.pad(width: Int): List<String> =
            if (size >= width) this else this + List(width - size) { "" }
    }
}
