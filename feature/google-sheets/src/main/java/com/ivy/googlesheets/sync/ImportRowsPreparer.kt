package com.ivy.googlesheets.sync

import com.ivy.importdata.csv.CSVRow
import com.ivy.importdata.csv.ColumnMapping
import com.ivy.importdata.csv.DateMetadata
import com.ivy.importdata.csv.ImportantFields
import com.ivy.importdata.csv.MappingStatus
import com.ivy.importdata.csv.OptionalFields
import com.ivy.importdata.csv.TransferFields
import com.ivy.importdata.csv.TrnTypeMetadata

/**
 * Converts rows of the user's sheet + their [SheetMapping] into the input of the existing
 * Ivy CSV importer, so Sheets import reuses the same, battle-tested account/category matching.
 *
 * Values are normalized first (see [SheetValueNormalizer]) and written into extra columns
 * appended after the sheet's own columns, so the importer always sees strict formats.
 */
object ImportRowsPreparer {
    data class Prepared(
        /** Header row first, as the CSV importer expects. */
        val csv: List<CSVRow>,
        val important: ImportantFields,
        val transfer: TransferFields,
        val optional: OptionalFields,
    )

    /** Normalized columns appended after the sheet's own columns, in this order. */
    private enum class Extra { DATE, AMOUNT, TYPE, ACCOUNT, TO_AMOUNT }

    private const val EXPENSE = "Expense"
    private const val INCOME = "Income"
    private const val TRANSFER = "Transfer"

    fun prepare(table: SheetTable, rows: List<List<String>>, mapping: SheetMapping): Prepared {
        val width = table.width
        val dateCol = width + Extra.DATE.ordinal
        val amountCol = width + Extra.AMOUNT.ordinal
        val typeCol = width + Extra.TYPE.ordinal
        val accountCol = width + Extra.ACCOUNT.ordinal
        val toAmountCol = width + Extra.TO_AMOUNT.ordinal

        val header = CSVRow(table.header + Extra.entries.map { "_" + it.name.lowercase() })
        val csvRows = rows.map { row ->
            val rawAmount = row.getOrElse(mapping.amount) { "" }
            val amount = SheetValueNormalizer.normalizeAmount(rawAmount)
            val date = SheetValueNormalizer.normalizeDate(
                row.getOrElse(mapping.date) { "" },
                mapping.dateOrder
            )
            val type = resolveType(row, mapping, amount)
            val account = if (mapping.account >= 0) {
                row.getOrElse(mapping.account) { "" }.ifBlank { mapping.fixedAccountName }
            } else {
                mapping.fixedAccountName
            }
            val toAmount = if (mapping.toAmount >= 0) {
                SheetValueNormalizer.normalizeAmount(row.getOrElse(mapping.toAmount) { "" })
            } else {
                amount
            }
            CSVRow(
                row.pad(width) + listOf(
                    date ?: "",
                    amount ?: "",
                    type ?: "",
                    account,
                    toAmount ?: "",
                )
            )
        }

        return Prepared(
            csv = listOf(header) + csvRows,
            important = ImportantFields(
                amount = column("Amount", amountCol, metadata = 1, required = true),
                amountStatus = Unchecked,
                type = column(
                    "Transaction Type",
                    typeCol,
                    metadata = TrnTypeMetadata(income = INCOME, expense = EXPENSE, transfer = TRANSFER),
                    required = true
                ),
                typeStatus = Unchecked,
                date = column("Date", dateCol, metadata = DateMetadata.MonthFirst, required = true),
                dateStatus = Unchecked,
                account = column("Account", accountCol, metadata = Unit, required = true),
                accountStatus = Unchecked,
                accountCurrency = column("Account Currency", mapping.currency, Unit, required = false),
                accountCurrencyStatus = Unchecked,
            ),
            transfer = TransferFields(
                toAccount = column("To Account", mapping.toAccount, Unit, required = false),
                toAccountStatus = Unchecked,
                toAccountCurrency = column("To Account Currency", SheetMapping.NONE, Unit, required = false),
                toAccountCurrencyStatus = Unchecked,
                toAmount = column("To Amount", toAmountCol, metadata = 1, required = false),
                toAmountStatus = Unchecked,
            ),
            optional = OptionalFields(
                category = column("Category", mapping.category, Unit, required = false),
                categoryStatus = Unchecked,
                title = column("Title", mapping.title, Unit, required = false),
                titleStatus = Unchecked,
                description = column("Description", mapping.description, Unit, required = false),
                descriptionStatus = Unchecked,
            ),
        )
    }

    /** @return "Income", "Expense", "Transfer" or null if the row's type can't be determined. */
    fun resolveType(row: List<String>, mapping: SheetMapping, normalizedAmount: String?): String? =
        when (mapping.typeMode) {
            SheetMapping.TypeMode.ALL_EXPENSES -> EXPENSE
            SheetMapping.TypeMode.ALL_INCOME -> INCOME
            SheetMapping.TypeMode.AMOUNT_SIGN -> when {
                normalizedAmount == null -> null
                normalizedAmount.startsWith("-") -> EXPENSE
                else -> INCOME
            }

            SheetMapping.TypeMode.COLUMN -> typeFromText(row.getOrElse(mapping.type) { "" }, mapping)
        }

    private fun typeFromText(raw: String, mapping: SheetMapping): String? {
        val value = ColumnAutoMapper.normalize(raw)
        if (value.isBlank()) return null
        fun matches(keyword: String): Boolean =
            keyword.isNotBlank() && value.contains(ColumnAutoMapper.normalize(keyword))
        return when {
            matches(mapping.transferKeyword) -> TRANSFER
            matches(mapping.incomeKeyword) -> INCOME
            matches(mapping.expenseKeyword) -> EXPENSE
            DefaultTransfer.any { value.contains(it) } -> TRANSFER
            DefaultIncome.any { value.contains(it) } -> INCOME
            DefaultExpense.any { value.contains(it) } -> EXPENSE
            else -> null
        }
    }

    private val DefaultIncome = listOf("ingreso", "income", "entrada", "cobro", "sueldo", "salario", "+")
    private val DefaultExpense = listOf("gasto", "expense", "egreso", "salida", "pago", "compra", "-")
    private val DefaultTransfer = listOf("transfer")

    private val Unchecked = MappingStatus(sampleValues = emptyList(), success = true)

    private fun <M> column(
        name: String,
        index: Int,
        metadata: M,
        required: Boolean,
    ): ColumnMapping<M> = ColumnMapping(
        ivyColumn = name,
        helpInfo = "",
        name = name,
        index = index,
        metadata = metadata,
        required = required,
    )

    private fun List<String>.pad(width: Int): List<String> =
        if (size >= width) take(width) else this + List(width - size) { "" }
}
