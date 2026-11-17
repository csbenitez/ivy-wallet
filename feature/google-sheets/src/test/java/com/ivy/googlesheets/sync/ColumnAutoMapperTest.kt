package com.ivy.googlesheets.sync

import io.kotest.matchers.shouldBe
import org.junit.Test

class ColumnAutoMapperTest {
    @Test
    fun `maps a typical spanish budget sheet`() {
        val table = SheetTable(
            header = listOf("Fecha", "Concepto", "Categoría", "Cuenta", "Cuenta destino", "Importe", "Saldo"),
            rows = listOf(
                listOf("25/01/2026", "Super", "Comida", "Banco", "", "-45,20", "1000"),
                listOf("26/01/2026", "Sueldo", "Salario", "Banco", "", "2000", "3000"),
            ),
        )
        val mapping = ColumnAutoMapper.guess(table)
        mapping.date shouldBe 0
        mapping.title shouldBe 1
        mapping.category shouldBe 2
        mapping.account shouldBe 3
        mapping.toAccount shouldBe 4
        mapping.amount shouldBe 5
        mapping.type shouldBe SheetMapping.NONE
        mapping.typeMode shouldBe SheetMapping.TypeMode.AMOUNT_SIGN
        mapping.dateOrder shouldBe SheetMapping.DateOrder.DAY_FIRST
        mapping.isComplete shouldBe true
    }

    @Test
    fun `maps an english sheet with a type column`() {
        val table = SheetTable(
            header = listOf("Date", "Type", "Amount", "Category", "Notes"),
            rows = listOf(listOf("01/31/2026", "Expense", "12", "Food", "")),
        )
        val mapping = ColumnAutoMapper.guess(table)
        mapping.date shouldBe 0
        mapping.type shouldBe 1
        mapping.amount shouldBe 2
        mapping.category shouldBe 3
        mapping.title shouldBe 4 // notes promoted to title when there's no title column
        mapping.typeMode shouldBe SheetMapping.TypeMode.COLUMN
        mapping.dateOrder shouldBe SheetMapping.DateOrder.MONTH_FIRST
        // no account column -> falls back to a fixed account, still complete
        mapping.account shouldBe SheetMapping.NONE
        mapping.isComplete shouldBe true
    }

    @Test
    fun `expense-only sheet`() {
        val table = SheetTable(
            header = listOf("Día", "Gasto", "Descripción"),
            rows = listOf(listOf("3/1/2026", "15", "Taxi")),
        )
        val mapping = ColumnAutoMapper.guess(table)
        mapping.date shouldBe 0
        mapping.amount shouldBe 1
        mapping.typeMode shouldBe SheetMapping.TypeMode.ALL_EXPENSES
    }
}
