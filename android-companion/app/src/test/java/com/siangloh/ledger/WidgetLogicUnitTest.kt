package com.siangloh.ledger

import com.siangloh.ledger.util.AppLanguageManager
import com.siangloh.ledger.widget.WidgetChartRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WidgetLogicUnitTest {

    @Test
    fun computeTrendSelectedIndex_fullMonth_mapsLast7DaysAccurately() {
        // 15 days in month -> slot 6 (today) maps to index 14, slot 0 (6 days ago) maps to index 8
        assertEquals(14, WidgetChartRenderer.computeTrendSelectedIndex(15, 6))
        assertEquals(13, WidgetChartRenderer.computeTrendSelectedIndex(15, 5))
        assertEquals(8, WidgetChartRenderer.computeTrendSelectedIndex(15, 0))
    }

    @Test
    fun computeTrendSelectedIndex_earlyMonth_clampsSafelyToMonthStart() {
        // Day 3 of the month (3 points: indices 0, 1, 2)
        assertEquals(2, WidgetChartRenderer.computeTrendSelectedIndex(3, 6)) // today (3rd)
        assertEquals(1, WidgetChartRenderer.computeTrendSelectedIndex(3, 5)) // yesterday (2nd)
        assertEquals(0, WidgetChartRenderer.computeTrendSelectedIndex(3, 4)) // 2 days ago (1st)
        assertEquals(0, WidgetChartRenderer.computeTrendSelectedIndex(3, 0)) // previous month clamped to 0
    }

    @Test
    fun computeTrendSelectedIndex_emptyOrZero_returnsZero() {
        assertEquals(0, WidgetChartRenderer.computeTrendSelectedIndex(0, 6))
        assertEquals(0, WidgetChartRenderer.computeTrendSelectedIndex(1, 0))
    }

    @Test
    fun formatWidgetDayExpenseTitle_allLanguages_formatsWithoutException() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10)
        }

        // Today labels
        assertEquals("今日支出", AppLanguageManager.formatWidgetDayExpenseTitle(cal, true, AppLanguageManager.LANG_ZH))
        assertEquals("Today Spend", AppLanguageManager.formatWidgetDayExpenseTitle(cal, true, AppLanguageManager.LANG_EN))
        assertEquals("Belanja Hari Ini", AppLanguageManager.formatWidgetDayExpenseTitle(cal, true, AppLanguageManager.LANG_MS))

        // Historical date labels
        assertEquals("11月10日 支出", AppLanguageManager.formatWidgetDayExpenseTitle(cal, false, AppLanguageManager.LANG_ZH))
        val enHist = AppLanguageManager.formatWidgetDayExpenseTitle(cal, false, AppLanguageManager.LANG_EN)
        assertTrue(enHist.contains("10") && enHist.contains("Spend"))
        val msHist = AppLanguageManager.formatWidgetDayExpenseTitle(cal, false, AppLanguageManager.LANG_MS)
        assertTrue(msHist.contains("10") && msHist.contains("Belanja"))
        assertFalse(msHist.isBlank())
    }

    @Test
    fun formatWidgetMonth_allLanguages_formatsCorrectly() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17)
        }
        assertEquals(" · 11月", AppLanguageManager.formatWidgetMonth(cal, AppLanguageManager.LANG_ZH))
        assertTrue(AppLanguageManager.formatWidgetMonth(cal, AppLanguageManager.LANG_EN).contains("Nov"))
        assertTrue(AppLanguageManager.formatWidgetMonth(cal, AppLanguageManager.LANG_MS).contains("Nov"))
    }
}

