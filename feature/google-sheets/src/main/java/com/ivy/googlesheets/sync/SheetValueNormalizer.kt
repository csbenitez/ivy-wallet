package com.ivy.googlesheets.sync

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Turns the values people actually type in budget sheets into the strict formats
 * the Ivy CSV importer understands:
 * - amounts: "$ 1.234,50", "(12.00)", "-1,234.5 €" -> "1234.50", "-12.00", "-1234.5"
 * - dates: "5/3/2026", "05.03.26 14:30", "2026-3-5", Sheets serial numbers -> "2026-03-05 14:30:00"
 */
object SheetValueNormalizer {
    private val IsoOut: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private const val THOUSANDS_GROUP = 3
    private const val MAX_MONTH = 12
    private const val YEAR_DIGITS = 4
    private const val TWO_DIGIT_YEAR_BASE = 2000
    private const val SECONDS_PER_DAY = 86_400

    // Sheets serial dates are days since 1899-12-30; accept roughly 1950..2150.
    private val SheetsEpoch: LocalDate = LocalDate.of(1899, 12, 30)
    private const val MIN_SERIAL = 18_000.0
    private const val MAX_SERIAL = 91_000.0

    // region Amount
    fun normalizeAmount(raw: String): String? {
        val text = raw.trim()
        val accountingNegative = text.startsWith("(") && text.endsWith(")")
        val negative = accountingNegative || text.contains('-') || text.contains('−')
        val digits = text.filter { it.isDigit() || it == '.' || it == ',' }
        val plain = digits.takeIf { it.any(Char::isDigit) }
            ?.let { toPlainNumber(it, decimalSeparator(it)) }
            ?.takeIf { it.isNotEmpty() }
        return plain?.let { if (negative) "-$it" else it }
    }

    private fun toPlainNumber(digits: String, decimalSeparator: Char?): String {
        val decimalIndex = decimalSeparator?.let(digits::lastIndexOf) ?: -1
        return buildString {
            digits.forEachIndexed { index, c ->
                if (c.isDigit()) append(c) else if (index == decimalIndex) append('.')
            }
        }.trimStart('.')
    }

    private fun decimalSeparator(digits: String): Char? {
        val lastDot = digits.lastIndexOf('.')
        val lastComma = digits.lastIndexOf(',')
        return when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) '.' else ','
            lastDot >= 0 -> decimalOrThousands(digits, '.')
            lastComma >= 0 -> decimalOrThousands(digits, ',')
            else -> null
        }
    }

    /**
     * "1.234" or "1,234,567" -> thousands separator; "12.5", "0,99", "1234,567" -> decimal.
     */
    private fun decimalOrThousands(digits: String, sep: Char): Char? {
        val parts = digits.split(sep)
        val beforeSep = parts.first()
        val looksLikeThousands = parts.size > 2 || (
            parts[1].length == THOUSANDS_GROUP && beforeSep.isNotEmpty() &&
                beforeSep.length <= THOUSANDS_GROUP && beforeSep != "0"
            )
        return if (looksLikeThousands) null else sep
    }
    // endregion

    // region Date
    private val DateRegex = Regex(
        """^(?<a>\d{1,4})[/.\-](?<b>\d{1,2})[/.\-](?<c>\d{1,4})""" +
            """(?:[ T,]+(?<h>\d{1,2}):(?<m>\d{2})(?::(?<s>\d{2}))?)?"""
    )

    fun normalizeDate(raw: String, order: SheetMapping.DateOrder): String? {
        val text = raw.trim()
        return (parseSerial(text) ?: parseNumericDate(text, order))?.format(IsoOut)
    }

    private fun parseSerial(text: String): LocalDateTime? = text.toDoubleOrNull()
        ?.takeIf { it in MIN_SERIAL..MAX_SERIAL }
        ?.let { serial ->
            val days = serial.toLong()
            val seconds = ((serial - days) * SECONDS_PER_DAY).toLong()
            SheetsEpoch.plusDays(days).atStartOfDay().plusSeconds(seconds)
        }

    private fun parseNumericDate(text: String, order: SheetMapping.DateOrder): LocalDateTime? {
        val groups = DateRegex.find(text)?.groups ?: return null
        fun part(name: String): String = groups[name]?.value.orEmpty()
        val (year, month, day) = yearMonthDay(part("a"), part("b"), part("c"), order)
        return try {
            LocalDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(
                    part("h").toIntOrNull() ?: 0,
                    part("m").toIntOrNull() ?: 0,
                    part("s").toIntOrNull() ?: 0,
                )
            )
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun yearMonthDay(
        a: String,
        b: String,
        c: String,
        order: SheetMapping.DateOrder
    ): Triple<Int, Int, Int> {
        if (a.length == YEAR_DIGITS) return Triple(a.toInt(), b.toInt(), c.toInt())
        val first = a.toInt()
        val second = b.toInt()
        val year = if (c.length <= 2) TWO_DIGIT_YEAR_BASE + c.toInt() else c.toInt()
        val dayFirst = when {
            first > MAX_MONTH -> true
            second > MAX_MONTH -> false
            else -> order == SheetMapping.DateOrder.DAY_FIRST
        }
        return if (dayFirst) Triple(year, second, first) else Triple(year, first, second)
    }

    /** Guesses whether the sheet writes day or month first from sample values. */
    fun guessDateOrder(samples: List<String>): SheetMapping.DateOrder {
        val votes = samples
            .mapNotNull { DateRegex.find(it.trim())?.groupValues }
            .filter { it[1].length != YEAR_DIGITS }
        val dayFirstVotes = votes.count { it[1].toInt() > MAX_MONTH }
        val monthFirstVotes = votes.count { it[2].toInt() > MAX_MONTH }
        return if (monthFirstVotes > dayFirstVotes) {
            SheetMapping.DateOrder.MONTH_FIRST
        } else {
            SheetMapping.DateOrder.DAY_FIRST
        }
    }
    // endregion
}
