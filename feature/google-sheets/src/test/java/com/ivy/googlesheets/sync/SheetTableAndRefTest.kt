package com.ivy.googlesheets.sync

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigDecimal

class SheetTableAndRefTest {
    @Test
    fun `parses spreadsheet id from url or raw id`() {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-xy"
        SpreadsheetRef.parseId("https://docs.google.com/spreadsheets/d/$id/edit#gid=0") shouldBe id
        SpreadsheetRef.parseId("  $id ") shouldBe id
        SpreadsheetRef.parseId("https://example.com") shouldBe null
        SpreadsheetRef.parseId("") shouldBe null
    }

    @Test
    fun `builds a padded table skipping empty rows`() {
        val table = SheetTable.fromRawValues(
            listOf(
                listOf("Fecha", "Monto", ""),
                emptyList(),
                listOf("01/02/2026", BigDecimal("12.50")),
                listOf("02/02/2026", BigDecimal("1E+3"), "nota"),
            )
        )
        table.header shouldBe listOf("Fecha", "Monto", "C")
        table.rows shouldBe listOf(
            listOf("01/02/2026", "12.5", ""),
            listOf("02/02/2026", "1000", "nota"),
        )
    }

    @Test
    fun `column letters`() {
        SheetTable.columnLetter(0) shouldBe "A"
        SheetTable.columnLetter(25) shouldBe "Z"
        SheetTable.columnLetter(26) shouldBe "AA"
    }

    @Test
    fun `fingerprints are stable and tell duplicates apart`() {
        val rows = listOf(
            listOf("01/02/2026", "10", "Café", "balance 100"),
            listOf("01/02/2026", "10", "Café", "balance 90"),
        )
        val columns = listOf(0, 1, 2)
        val first = RowFingerprint.fingerprints(rows, columns)
        (first[0] == first[1]) shouldBe false
        // unmapped column changed -> same fingerprints
        val edited = rows.map { it.take(3) + "changed" }
        RowFingerprint.fingerprints(edited, columns) shouldBe first
        // a new row appended doesn't change existing fingerprints
        RowFingerprint.fingerprints(rows + listOf(listOf("03/02/2026", "5", "Pan", "")), columns)
            .take(2) shouldBe first
    }
}
