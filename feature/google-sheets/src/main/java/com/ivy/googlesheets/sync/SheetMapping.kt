package com.ivy.googlesheets.sync

import kotlinx.serialization.Serializable

/**
 * How the columns of the user's own sheet map to Ivy Wallet transaction fields.
 * Column values are 0-based indexes into [SheetTable.header], or [NONE].
 * Saved so that later syncs don't need the mapping step again.
 */
@Serializable
data class SheetMapping(
    val date: Int = NONE,
    val dateOrder: DateOrder = DateOrder.DAY_FIRST,
    val amount: Int = NONE,
    val typeMode: TypeMode = TypeMode.ALL_EXPENSES,
    val type: Int = NONE,
    val incomeKeyword: String = "",
    val expenseKeyword: String = "",
    val transferKeyword: String = "",
    val account: Int = NONE,
    val fixedAccountName: String = DEFAULT_ACCOUNT_NAME,
    val currency: Int = NONE,
    val category: Int = NONE,
    val title: Int = NONE,
    val description: Int = NONE,
    val toAccount: Int = NONE,
    val toAmount: Int = NONE,
) {
    /** The mapped columns of the user's sheet; used to fingerprint rows. */
    val mappedColumns: List<Int>
        get() = listOf(
            date, amount, type, account, currency,
            category, title, description, toAccount, toAmount
        ).filter { it >= 0 }.distinct().sorted()

    fun missingFields(): List<Field> = buildList {
        if (date < 0) add(Field.DATE)
        if (amount < 0) add(Field.AMOUNT)
        if (typeMode == TypeMode.COLUMN && type < 0) add(Field.TYPE)
        if (account < 0 && fixedAccountName.isBlank()) add(Field.ACCOUNT)
    }

    val isComplete: Boolean get() = missingFields().isEmpty()

    fun with(field: Field, column: Int): SheetMapping = when (field) {
        Field.DATE -> copy(date = column)
        Field.AMOUNT -> copy(amount = column)
        Field.TYPE -> copy(type = column)
        Field.ACCOUNT -> copy(account = column)
        Field.CURRENCY -> copy(currency = column)
        Field.CATEGORY -> copy(category = column)
        Field.TITLE -> copy(title = column)
        Field.DESCRIPTION -> copy(description = column)
        Field.TO_ACCOUNT -> copy(toAccount = column)
        Field.TO_AMOUNT -> copy(toAmount = column)
    }

    fun columnOf(field: Field): Int = when (field) {
        Field.DATE -> date
        Field.AMOUNT -> amount
        Field.TYPE -> type
        Field.ACCOUNT -> account
        Field.CURRENCY -> currency
        Field.CATEGORY -> category
        Field.TITLE -> title
        Field.DESCRIPTION -> description
        Field.TO_ACCOUNT -> toAccount
        Field.TO_AMOUNT -> toAmount
    }

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
    }
}
