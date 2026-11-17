package com.ivy.googlesheets.sync

import kotlinx.serialization.Serializable

/**
 * How the columns of the user's own sheet map to Ivy Wallet transaction fields.
 * Column values are 0-based indexes into [SheetTable.header], or [NONE].
 * Saved so that later syncs don't need the mapping step again.
 */
@Serializable
data class SheetMapping(
    val date: Int,
    val dateOrder: DateOrder,
    val amount: Int,
    val typeMode: TypeMode,
    val type: Int,
    val incomeKeyword: String,
    val expenseKeyword: String,
    val transferKeyword: String,
    val account: Int,
    val fixedAccountName: String,
    val currency: Int,
    val category: Int,
    val title: Int,
    val description: Int,
    val toAccount: Int,
    val toAmount: Int,
) {
    /** The mapped columns of the user's sheet; used to fingerprint rows. */
    val mappedColumns: List<Int>
        get() = listOf(
            date, amount, type, account, currency,
            category, title, description, toAccount, toAmount
        ).filter { it >= 0 }.distinct().sorted()

    enum class Field {
        DATE, AMOUNT, TYPE, ACCOUNT, CURRENCY, CATEGORY, TITLE, DESCRIPTION, TO_ACCOUNT, TO_AMOUNT
    }

    @Serializable
    enum class DateOrder { DAY_FIRST, MONTH_FIRST }

    @Serializable
    enum class TypeMode {
        /** A column says whether it's an income, expense or transfer. */
        COLUMN,

        /** Negative amounts are expenses, positive amounts are incomes. */
        AMOUNT_SIGN,

        /** The sheet only tracks spending. */
        ALL_EXPENSES,

        /** The sheet only tracks income. */
        ALL_INCOME,
    }

    companion object {
        const val NONE = -1
        const val DEFAULT_ACCOUNT_NAME = "Google Sheets"

        /** Nothing mapped yet; the starting point for [ColumnAutoMapper] and the UI. */
        val Empty = SheetMapping(
            date = NONE,
            dateOrder = DateOrder.DAY_FIRST,
            amount = NONE,
            typeMode = TypeMode.ALL_EXPENSES,
            type = NONE,
            incomeKeyword = "",
            expenseKeyword = "",
            transferKeyword = "",
            account = NONE,
            fixedAccountName = DEFAULT_ACCOUNT_NAME,
            currency = NONE,
            category = NONE,
            title = NONE,
            description = NONE,
            toAccount = NONE,
            toAmount = NONE,
        )
    }
}

val SheetMapping.isComplete: Boolean get() = missingFields().isEmpty()

fun SheetMapping.missingFields(): List<SheetMapping.Field> = buildList {
    if (date < 0) add(SheetMapping.Field.DATE)
    if (amount < 0) add(SheetMapping.Field.AMOUNT)
    if (typeMode == SheetMapping.TypeMode.COLUMN && type < 0) add(SheetMapping.Field.TYPE)
    if (account < 0 && fixedAccountName.isBlank()) add(SheetMapping.Field.ACCOUNT)
}

fun SheetMapping.with(field: SheetMapping.Field, column: Int): SheetMapping = when (field) {
    SheetMapping.Field.DATE -> copy(date = column)
    SheetMapping.Field.AMOUNT -> copy(amount = column)
    SheetMapping.Field.TYPE -> copy(type = column)
    SheetMapping.Field.ACCOUNT -> copy(account = column)
    SheetMapping.Field.CURRENCY -> copy(currency = column)
    SheetMapping.Field.CATEGORY -> copy(category = column)
    SheetMapping.Field.TITLE -> copy(title = column)
    SheetMapping.Field.DESCRIPTION -> copy(description = column)
    SheetMapping.Field.TO_ACCOUNT -> copy(toAccount = column)
    SheetMapping.Field.TO_AMOUNT -> copy(toAmount = column)
}

fun SheetMapping.columnOf(field: SheetMapping.Field): Int = when (field) {
    SheetMapping.Field.DATE -> date
    SheetMapping.Field.AMOUNT -> amount
    SheetMapping.Field.TYPE -> type
    SheetMapping.Field.ACCOUNT -> account
    SheetMapping.Field.CURRENCY -> currency
    SheetMapping.Field.CATEGORY -> category
    SheetMapping.Field.TITLE -> title
    SheetMapping.Field.DESCRIPTION -> description
    SheetMapping.Field.TO_ACCOUNT -> toAccount
    SheetMapping.Field.TO_AMOUNT -> toAmount
}
