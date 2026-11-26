package com.siangloh.ledger.core

import com.siangloh.ledger.core.format.currentEpochMillis
import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.core.format.daysInMonth
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.shiftDays
import com.siangloh.ledger.core.format.shiftMonth
import com.siangloh.ledger.core.format.todayDateString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerFormattersTest {

    @Test
    fun formatMoney_basicAndGrouping() {
        assertEquals("0.00", formatMoney(0.0))
        assertEquals("5.00", formatMoney(5.0))
        assertEquals("1,234.50", formatMoney(1234.5))
        assertEquals("1,234,567.89", formatMoney(1234567.89))
        assertEquals("1234567.89", formatMoney(1234567.89, grouping = false))
        assertEquals("1,000,000,000.00", formatMoney(1_000_000_000.0))
    }

    @Test
    fun formatMoney_exactHalfUpRounding() {
        assertEquals("1.01", formatMoney(1.005))
        assertEquals("2.68", formatMoney(2.675))
        assertEquals("1.24", formatMoney(1.235))
        assertEquals("1.25", formatMoney(1.245))
        assertEquals("0.01", formatMoney(0.005))
        assertEquals("0.00", formatMoney(0.0049))
        assertEquals("10.00", formatMoney(9.995))
        assertEquals("1,000.00", formatMoney(999.995))
        assertEquals("1.235", formatMoney(1.2345, decimals = 3))
        assertEquals("1,235", formatMoney(1234.5, decimals = 0))
        assertEquals("3", formatMoney(2.5, decimals = 0))
    }

    @Test
    fun formatMoney_negativesAndNegativeZeroAvoidance() {
        assertEquals("-1,234.50", formatMoney(-1234.5))
        assertEquals("-1234.50", formatMoney(-1234.5, grouping = false))
        assertEquals("-1.01", formatMoney(-1.005))
        assertEquals("-0.01", formatMoney(-0.005))
        assertEquals("0.00", formatMoney(-0.001))
        assertEquals("0.00", formatMoney(-0.0049))
        assertEquals("0.00", formatMoney(-0.0))
        assertEquals("0", formatMoney(-0.4, decimals = 0))
    }

    @Test
    fun formatMoney_rejectsNegativeDecimals() {
        assertFailsWith<IllegalArgumentException> {
            formatMoney(10.0, decimals = -1)
        }
    }

    @Test
    fun dateAndClockHelpers_formatAndRange() {
        val now = currentEpochMillis()
        assertTrue(now > 1_700_000_000_000L)

        val today = todayDateString(TimeZone.UTC)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(today))
        LocalDate.parse(today)

        val month = currentMonthString(TimeZone.UTC)
        assertTrue(Regex("""\d{4}-\d{2}""").matches(month))
        assertTrue(today.startsWith(month))
    }

    @Test
    fun shiftMonth_handlesYearRollovers() {
        assertEquals("2025-02", shiftMonth("2024-11", 3))
        assertEquals("2023-11", shiftMonth("2024-02", -3))
        assertEquals("2024-05", shiftMonth("2024-05", 0))
        assertEquals("2023-01", shiftMonth("2024-01", -12))
        assertEquals("2025-01", shiftMonth("2024-12", 1))
        assertTrue(Regex("""\d{4}-\d{2}""").matches(shiftMonth("2024/01", 1)))
        assertTrue(Regex("""\d{4}-\d{2}""").matches(shiftMonth("2024-13", 1)))
    }

    @Test
    fun shiftDays_handlesMonthsAndLeapYears() {
        assertEquals("2024-02-29", shiftDays("2024-02-28", 1))
        assertEquals("2024-03-01", shiftDays("2024-02-29", 1))
        assertEquals("2023-03-01", shiftDays("2023-02-28", 1))
        assertEquals("2023-12-31", shiftDays("2024-01-01", -1))
        assertEquals("2024-05-10", shiftDays("2024-05-10", 0))
    }

    @Test
    fun daysInMonth_leapYearsAndStandardMonths() {
        assertEquals(29, daysInMonth("2024-02"))
        assertEquals(28, daysInMonth("2023-02"))
        assertEquals(29, daysInMonth("2000-02"))
        assertEquals(28, daysInMonth("1900-02"))
        assertEquals(31, daysInMonth("2024-01"))
        assertEquals(30, daysInMonth("2024-04"))
        assertEquals(31, daysInMonth("2024-12"))
    }
}
