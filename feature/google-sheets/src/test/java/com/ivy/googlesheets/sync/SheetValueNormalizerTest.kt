package com.ivy.googlesheets.sync

import io.kotest.matchers.shouldBe
import org.junit.Test

class SheetValueNormalizerTest {
    @Test
    fun `normalizes amounts in common sheet formats`() {
        val cases = mapOf(
            "12" to "12",
            "12.5" to "12.5",
            "-12.50" to "-12.50",
            "1,234.56" to "1234.56",
            "1.234,56" to "1234.56",
            "$ 1.234,50" to "1234.50",
            "1.234" to "1234",
            "1,234,567" to "1234567",
            "0,99" to "0.99",
            "(45.00)" to "-45.00",
            "-1 234,5 €" to "-1234.5",
            "− 20" to "-20",
            "" to null,
            "abc" to null,
        )
        cases.forEach { (input, expected) ->
            SheetValueNormalizer.normalizeAmount(input) shouldBe expected
        }
    }

    @Test
    fun `normalizes day-first dates`() {
        val order = SheetMapping.DateOrder.DAY_FIRST
        SheetValueNormalizer.normalizeDate("5/3/2026", order) shouldBe "2026-03-05 00:00:00"
        SheetValueNormalizer.normalizeDate("05.03.26 14:30", order) shouldBe "2026-03-05 14:30:00"
        SheetValueNormalizer.normalizeDate("2026-3-5", order) shouldBe "2026-03-05 00:00:00"
        SheetValueNormalizer.normalizeDate("31/12/2026", order) shouldBe "2026-12-31 00:00:00"
        // impossible as day-first, so it must be month-first
        SheetValueNormalizer.normalizeDate("12/31/2026", order) shouldBe "2026-12-31 00:00:00"
    }

    @Test
    fun `normalizes month-first dates`() {
        val order = SheetMapping.DateOrder.MONTH_FIRST
        SheetValueNormalizer.normalizeDate("3/5/2026 9:05:10", order) shouldBe "2026-03-05 09:05:10"
    }

    @Test
    fun `converts sheets serial dates`() {
        // 46086 = 2026-03-05 in Google Sheets
        SheetValueNormalizer.normalizeDate("46086", SheetMapping.DateOrder.DAY_FIRST) shouldBe
            "2026-03-05 00:00:00"
        SheetValueNormalizer.normalizeDate("46086.5", SheetMapping.DateOrder.DAY_FIRST) shouldBe
            "2026-03-05 12:00:00"
    }

    @Test
    fun `rejects invalid dates`() {
        SheetValueNormalizer.normalizeDate("31/31/2026", SheetMapping.DateOrder.DAY_FIRST) shouldBe null
        SheetValueNormalizer.normalizeDate("hello", SheetMapping.DateOrder.DAY_FIRST) shouldBe null
    }

    @Test
    fun `guesses date order from samples`() {
        SheetValueNormalizer.guessDateOrder(listOf("01/02/2026", "25/02/2026")) shouldBe
            SheetMapping.DateOrder.DAY_FIRST
        SheetValueNormalizer.guessDateOrder(listOf("01/02/2026", "02/25/2026")) shouldBe
            SheetMapping.DateOrder.MONTH_FIRST
    }
}
