package com.ivy.googlesheets.sync

import com.ivy.base.model.TransactionType
import com.ivy.importdata.csv.domain.extractValue
import com.ivy.importdata.csv.domain.parseAccount
import com.ivy.importdata.csv.domain.parseAmount
import com.ivy.importdata.csv.domain.parseCategory
import com.ivy.importdata.csv.domain.parseDate
import com.ivy.importdata.csv.domain.parseTransactionType
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class ImportAndWriteRowsTest {
    private val table = SheetTable(
        header = listOf("Fecha", "Concepto", "Categoría", "Importe", "Tipo"),
        rows = listOf(
            listOf("5/3/2026", "Super", "Comida", "1.234,50", "Gasto"),
            listOf("6/3/2026", "Sueldo", "Salario", "2000", "Ingreso"),
            listOf("7/3/2026", "Raro", "", "10", "???"),
        ),
    )
    private val mapping = SheetMapping.Empty.copy(
        date = 0,
        title = 1,
        category = 2,
        amount = 3,
        type = 4,
        typeMode = SheetMapping.TypeMode.COLUMN,
        fixedAccountName = "Efectivo",
    )

    @Test
    fun `prepared rows are understood by the Ivy CSV parsers`() {
        val prepared = ImportRowsPreparer.prepare(table, table.rows, mapping)
        val rows = prepared.csv.drop(1)
        val important = prepared.important

        val first = rows[0]
        parseAmount(first.extractValue(important.amount), important.amount.metadata) shouldBe 1234.5
        parseTransactionType(first.extractValue(important.type), important.type.metadata) shouldBe
            TransactionType.EXPENSE
        parseDate(first.extractValue(important.date), important.date.metadata) shouldBe
            LocalDateTime.of(2026, 3, 5, 0, 0)
        parseAccount(first.extractValue(important.account), Unit) shouldBe "Efectivo"
        parseCategory(first.extractValue(prepared.optional.category), Unit) shouldBe "Comida"

        parseTransactionType(rows[1].extractValue(important.type), important.type.metadata) shouldBe
            TransactionType.INCOME
        parseTransactionType(rows[2].extractValue(important.type), important.type.metadata) shouldBe null
    }

    @Test
    fun `amount sign mode`() {
        val signMapping = mapping.copy(typeMode = SheetMapping.TypeMode.AMOUNT_SIGN, type = SheetMapping.NONE)
        ImportRowsPreparer.resolveType(emptyList(), signMapping, "-3") shouldBe "Expense"
        ImportRowsPreparer.resolveType(emptyList(), signMapping, "3") shouldBe "Income"
    }

    @Test
    fun `custom type keywords win`() {
        val custom = mapping.copy(incomeKeyword = "Entra", expenseKeyword = "Sale")
        ImportRowsPreparer.resolveType(listOf("", "", "", "", "sale"), custom, "1") shouldBe "Expense"
        ImportRowsPreparer.resolveType(listOf("", "", "", "", "Entra"), custom, "1") shouldBe "Income"
    }

    private val expense = OutgoingTransaction(
        type = OutgoingTransaction.Type.EXPENSE,
        dateTime = LocalDateTime.of(2026, 3, 8, 13, 45),
        amount = 12.3,
        currency = "EUR",
        account = "Efectivo",
        category = "Comida",
        title = "Pan",
        description = null,
        toAccount = null,
        toAmount = null,
    )

    @Test
    fun `writes a transaction in the sheet's own format`() {
        SheetRowWriter.toRow(expense, mapping, width = 5) shouldBe listOf(
            "08/03/2026 13:45", "Pan", "Comida", BigDecimal("12.30"), "Gasto"
        )
        val signMapping = mapping.copy(typeMode = SheetMapping.TypeMode.AMOUNT_SIGN, type = SheetMapping.NONE)
        SheetRowWriter.toRow(expense, signMapping, width = 5)!![3] shouldBe BigDecimal("-12.30")
    }

    @Test
    fun `skips what the sheet can't represent`() {
        val income = expense.copy(type = OutgoingTransaction.Type.INCOME)
        val transfer = expense.copy(type = OutgoingTransaction.Type.TRANSFER, toAccount = "Banco")
        val expensesOnly = mapping.copy(typeMode = SheetMapping.TypeMode.ALL_EXPENSES)
        SheetRowWriter.toRow(income, expensesOnly, 5) shouldBe null
        SheetRowWriter.toRow(transfer, mapping, 5) shouldBe null // no "to account" column
    }

    @Test
    fun `written rows import back to the same transaction`() {
        val row = SheetRowWriter.toRow(expense, mapping, width = 5)!!
        val asRead = row.map { SheetTable.cellToString(it) }
        val prepared = ImportRowsPreparer.prepare(table, listOf(asRead), mapping)
        val back = prepared.csv[1]
        parseAmount(back.extractValue(prepared.important.amount), 1) shouldBe 12.3
        parseDate(back.extractValue(prepared.important.date), prepared.important.date.metadata) shouldBe
            LocalDateTime.of(2026, 3, 8, 13, 45)
        parseTransactionType(back.extractValue(prepared.important.type), prepared.important.type.metadata) shouldBe
            TransactionType.EXPENSE
    }
}
