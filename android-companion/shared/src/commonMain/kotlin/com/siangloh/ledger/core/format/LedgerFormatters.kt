package com.siangloh.ledger.core.format

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.min

/**
 * Formats a monetary [amount] with exact decimal half-up rounding, optional thousands commas,
 * proper negative handling, and zero-normalization (never emits `"-0.00"`).
 */
fun formatMoney(amount: Double, decimals: Int = 2, grouping: Boolean = true): String {
    require(decimals >= 0) { "decimals must be non-negative, got $decimals" }
    if (!amount.isFinite()) return amount.toString()

    val isNegative = amount < 0.0
    val absVal = if (isNegative) -amount else if (amount == 0.0) 0.0 else amount

    val (rawDigits, rawPointPos) = parseDecimalDigits(absVal.toString())
    val rounded = roundDigitsHalfUp(rawDigits, rawPointPos, decimals)

    val allZero = rounded.digits.all { it == 0 }
    val intLen = rounded.digits.size - decimals
    val intRaw = buildString(intLen) {
        for (i in 0 until intLen) {
            append(('0'.code + rounded.digits[i]).toChar())
        }
    }
    val intFormatted = if (grouping) groupThousands(intRaw) else intRaw
    val sign = if (isNegative && !allZero) "-" else ""

    if (decimals == 0) {
        return sign + intFormatted
    }

    val fracFormatted = buildString(decimals) {
        for (i in intLen until rounded.digits.size) {
            append(('0'.code + rounded.digits[i]).toChar())
        }
    }
    return "$sign$intFormatted.$fracFormatted"
}

private data class RoundedDigits(val digits: IntArray)

private fun parseDecimalDigits(text: String): Pair<String, Int> {
    val eIdx = text.indexOfAny(charArrayOf('e', 'E'))
    val mantissa = if (eIdx >= 0) text.substring(0, eIdx) else text
    val exp = if (eIdx >= 0) text.substring(eIdx + 1).toInt() else 0

    val dotIdx = mantissa.indexOf('.')
    val fracLen = if (dotIdx >= 0) mantissa.length - 1 - dotIdx else 0
    val raw = if (dotIdx >= 0) mantissa.removeRange(dotIdx, dotIdx + 1) else mantissa

    var firstNonZero = 0
    while (firstNonZero < raw.length && raw[firstNonZero] == '0') {
        firstNonZero++
    }
    if (firstNonZero == raw.length) {
        return "" to 0
    }
    val trimmed = raw.substring(firstNonZero)
    val pointPos = (raw.length - firstNonZero) + exp - fracLen
    return trimmed to pointPos
}

private fun roundDigitsHalfUp(rawDigits: String, rawPointPos: Int, decimals: Int): RoundedDigits {
    if (rawDigits.isEmpty()) {
        return RoundedDigits(IntArray(1 + decimals))
    }

    val startIdx = min(0, rawPointPos - 1)
    val lastIdx = rawPointPos + decimals - 1
    val roundIdx = lastIdx + 1
    val totalLen = lastIdx - startIdx + 1
    val buf = IntArray(totalLen)

    for (offset in 0 until totalLen) {
        val idx = startIdx + offset
        buf[offset] = if (idx in rawDigits.indices) rawDigits[idx] - '0' else 0
    }

    val roundDigit = if (roundIdx in rawDigits.indices) rawDigits[roundIdx] - '0' else 0
    if (roundDigit >= 5) {
        var carry = 1
        for (i in totalLen - 1 downTo 0) {
            val sum = buf[i] + carry
            buf[i] = sum % 10
            carry = sum / 10
            if (carry == 0) break
        }
        if (carry > 0) {
            val expanded = IntArray(totalLen + 1)
            expanded[0] = carry
            buf.copyInto(expanded, destinationOffset = 1)
            return RoundedDigits(expanded)
        }
    }
    return RoundedDigits(buf)
}

private fun groupThousands(intDigits: String): String {
    if (intDigits.length <= 3) return intDigits
    val sb = StringBuilder(intDigits.length + (intDigits.length - 1) / 3)
    var count = 0
    for (i in intDigits.length - 1 downTo 0) {
        if (count > 0 && count % 3 == 0) {
            sb.append(',')
        }
        sb.append(intDigits[i])
        count++
    }
    return sb.reverse().toString()
}

fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun todayDateString(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val today = Clock.System.now().toLocalDateTime(timeZone).date
    return formatLocalDate(today)
}

fun currentMonthString(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val today = Clock.System.now().toLocalDateTime(timeZone).date
    return formatYearMonth(today.year, today.monthNumber)
}

fun shiftMonth(yearMonth: String, deltaMonths: Int): String {
    val first = parseYearMonthFirstDayOrNull(yearMonth) ?: parseYearMonthFirstDayOrNull(currentMonthString())
        ?: return "2026-01"
    return runCatching {
        val shifted = if (deltaMonths >= 0) {
            first.plus(DatePeriod(months = deltaMonths))
        } else {
            first.minus(DatePeriod(months = -deltaMonths))
        }
        formatYearMonth(shifted.year, shifted.monthNumber)
    }.getOrElse {
        formatYearMonth(first.year, first.monthNumber)
    }
}

fun shiftDays(dateStr: String, deltaDays: Int): String {
    val date = LocalDate.parse(dateStr)
    val shifted = if (deltaDays >= 0) {
        date.plus(DatePeriod(days = deltaDays))
    } else {
        date.minus(DatePeriod(days = -deltaDays))
    }
    return formatLocalDate(shifted)
}

fun daysInMonth(yearMonth: String): Int {
    val first = parseYearMonthFirstDayOrNull(yearMonth) ?: return 30
    return runCatching {
        val nextMonthFirst = first.plus(DatePeriod(months = 1))
        first.daysUntil(nextMonthFirst)
    }.getOrDefault(30)
}

private fun parseYearMonthFirstDayOrNull(yearMonth: String): LocalDate? {
    val parts = yearMonth.trim().split('-')
    if (parts.size != 2) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    if (year !in 1..9999 || month !in 1..12) return null
    return runCatching { LocalDate(year, month, 1) }.getOrNull()
}

private fun formatYearMonth(year: Int, monthNumber: Int): String {
    return "${year.toString().padStart(4, '0')}-${monthNumber.toString().padStart(2, '0')}"
}

private fun formatLocalDate(date: LocalDate): String {
    return "${formatYearMonth(date.year, date.monthNumber)}-${date.dayOfMonth.toString().padStart(2, '0')}"
}
