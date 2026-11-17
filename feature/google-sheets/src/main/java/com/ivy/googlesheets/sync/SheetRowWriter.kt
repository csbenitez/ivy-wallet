package com.ivy.googlesheets.sync

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The parts of an Ivy Wallet transaction that can be written to the user's sheet.
 * Kept free of Ivy domain types so the mapping is easy to test and to reuse from a plugin.
 */
data class OutgoingTransaction(
    val type: Type,
    val dateTime: LocalDateTime,
    val amount: Double,
    val currency: String,
    val account: String,
    val category: String?,
    val title: String?,
    val description: String?,
    val toAccount: String?,
    val toAmount: Double?,
) {
    enum class Type { INCOME, EXPENSE, TRANSFER }
}

/**
 * Writes Ivy transactions as rows in the user's own format (the inverse of [ImportRowsPreparer]).
 * Cells are sent with `valueInputOption=USER_ENTERED`: amounts as numbers and dates as text
 * in the same day/month order the sheet already uses, so Sheets turns them into real dates.
 */
object SheetRowWriter {
    /**
     * @return the row to append, or null if the sheet's format can't represent the transaction
     * (e.g. an income in a sheet that only tracks expenses, or a transfer without "to account").
     */
    fun toRow(trn: OutgoingTransaction, mapping: SheetMapping, width: Int): List<Any>? {
        if (!canRepresent(trn, mapping)) return null
        val cells = MutableList<Any>(width) { "" }
        fun put(column: Int, value: Any?) {
            if (column in 0 until width && value != null) cells[column] = value
        }

        put(mapping.date, formatDate(trn.dateTime, mapping.dateOrder))
        put(mapping.amount, signedAmount(trn, mapping))
        if (mapping.typeMode == SheetMapping.TypeMode.COLUMN && mapping.type != mapping.amount) {
            put(mapping.type, typeLabel(trn.type, mapping))
        }
        put(mapping.account, trn.account)
        put(mapping.currency, trn.currency)
        put(mapping.category, trn.category)
        put(mapping.title, trn.title)
        if (mapping.description != mapping.title) put(mapping.description, trn.description)
        if (trn.type == OutgoingTransaction.Type.TRANSFER) {
            put(mapping.toAccount, trn.toAccount)
            put(mapping.toAmount, trn.toAmount?.let(::number))
        }
        return cells
    }

    fun canRepresent(trn: OutgoingTransaction, mapping: SheetMapping): Boolean = when (trn.type) {
        OutgoingTransaction.Type.EXPENSE -> mapping.typeMode != SheetMapping.TypeMode.ALL_INCOME
        OutgoingTransaction.Type.INCOME -> mapping.typeMode != SheetMapping.TypeMode.ALL_EXPENSES
        OutgoingTransaction.Type.TRANSFER ->
            mapping.typeMode == SheetMapping.TypeMode.COLUMN && mapping.toAccount >= 0
    }

    private fun signedAmount(trn: OutgoingTransaction, mapping: SheetMapping): BigDecimal {
        val value = number(trn.amount)
        val negative = mapping.typeMode == SheetMapping.TypeMode.AMOUNT_SIGN &&
            trn.type == OutgoingTransaction.Type.EXPENSE
        return if (negative) value.negate() else value
    }

    private fun typeLabel(type: OutgoingTransaction.Type, mapping: SheetMapping): String = when (type) {
        OutgoingTransaction.Type.INCOME -> mapping.incomeKeyword.ifBlank { "Ingreso" }
        OutgoingTransaction.Type.EXPENSE -> mapping.expenseKeyword.ifBlank { "Gasto" }
        OutgoingTransaction.Type.TRANSFER -> mapping.transferKeyword.ifBlank { "Transferencia" }
    }

    private fun number(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)

    private val DayFirst = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val MonthFirst = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")

    fun formatDate(dateTime: LocalDateTime, order: SheetMapping.DateOrder): String =
        dateTime.format(if (order == SheetMapping.DateOrder.DAY_FIRST) DayFirst else MonthFirst)
}
