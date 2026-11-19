package com.siangloh.ledger

import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.LocalFinancialAdvisor
import com.siangloh.ledger.data.LocalFinancialAdvisor.AdvisorCalculator
import com.siangloh.ledger.data.LocalFinancialAdvisor.TxSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFinancialAdvisorTest {

    @Test
    fun detectRepeatMerchant_findsHighFrequencySmallLeaksAndAnnualizes() {
        val txs = listOf(
            TxSample("2026-11-02", 14.50, "餐饮", "Zus Coffee #102", false),
            TxSample("2026-11-05", 13.00, "餐饮", "Zus Coffee", false),
            TxSample("2026-11-09", 16.50, "餐饮", "Zus Coffee Ref:99", false),
            TxSample("2026-11-12", 15.00, "餐饮", "Zus Coffee", false),
            TxSample("2026-11-13", 22.00, "交通", "Grab Ride", false)
        )
        val stat = AdvisorCalculator.detectRepeatMerchant(txs)
        assertNotNull(stat)
        assertEquals(4, stat!!.count)
        assertEquals(59.0, stat.total, 0.01)
        assertEquals(59.0 * 12.0, stat.annualizedCost, 0.01)
    }

    @Test
    fun detectWeekendSkew_identifiesWeekendOverWeekdaySpendSpike() {
        // 2026-11-02..06 Mon-Fri; 2026-11-07..08 Sat-Sun; 2026-11-14..15 Sat-Sun
        val txs = listOf(
            TxSample("2026-11-02", 25.0, "餐饮", "Lunch", false),
            TxSample("2026-11-03", 30.0, "餐饮", "Lunch", false),
            TxSample("2026-11-04", 25.0, "餐饮", "Lunch", false),
            TxSample("2026-11-07", 110.0, "娱乐", "Dining Out", false),
            TxSample("2026-11-08", 90.0, "购物", "Mall", false),
            TxSample("2026-11-14", 100.0, "娱乐", "Dinner", false)
        )
        val skew = AdvisorCalculator.detectWeekendSkew(txs)
        assertNotNull(skew)
        assertTrue("Expected ratio >= 3.0, got ${skew!!.ratio}", skew.ratio >= 3.0)
        assertTrue("Expected positive savable", skew.estimatedMonthlySavable > 200.0)
    }

    @Test
    fun computeMetrics_calculatesBurnProjectionRunwayAndHighCreditUtilization() {
        val accounts = listOf(
            AccountRecord(1, "Maybank Savings", "savings", 9000.0, 0.0, "MYR", null, null),
            AccountRecord(2, "TNG eWallet", "e_wallet", 1000.0, 0.0, "MYR", null, null),
            AccountRecord(3, "Visa Platinum", "credit_card", -2600.0, 4000.0, "MYR", 5, 25)
        )
        // Passed 15 days of 30, remaining 15 days
        // Fixed bills already paid = 1500, pending bills = 500, discretionary spent = 1500 (RM 100/day -> proj 3000 variable -> total proj 5000 vs income 4200 -> -800 deficit)
        val snapshot = AdvisorCalculator.computeMetrics(
            income = 4200.0,
            expense = 3000.0,
            savings = 400.0,
            available = 700.0,
            pendingBillsTotal = 500.0,
            fixedExpenseTotal = 1500.0,
            discretionaryExpenseTotal = 1500.0,
            overallBudgetLimit = 3800.0,
            passedDays = 15,
            remainingDays = 15,
            totalDays = 30,
            discretionaryTxs = emptyList(),
            accounts = accounts
        )
        assertEquals(100.0, snapshot.variableDailyRate, 0.01)
        assertEquals(5000.0, snapshot.projectedMonthEndExpense, 0.01)
        assertEquals(-800.0, snapshot.projectedMonthEndNet, 0.01)
        // 10000 liquid / 5000 projected burn = 2.0 months runway
        assertNotNull(snapshot.runwayMonths)
        assertEquals(2.0, snapshot.runwayMonths!!, 0.05)
        // Visa card 2600 / 4000 = 65%
        assertNotNull(snapshot.highCreditCard)
        assertEquals("Visa Platinum", snapshot.highCreditCard!!.first)
        assertEquals(65.0, snapshot.highCreditCard!!.second, 0.1)
    }

    @Test
    fun isFixedEssentialName_matchesLocalizedFixedBillsWithoutDiscretionary() {
        assertTrue(LocalFinancialAdvisor.isFixedEssentialName("Housing", "住房与房租"))
        assertTrue(LocalFinancialAdvisor.isFixedEssentialName("Sewa Rumah"))
        assertTrue(!LocalFinancialAdvisor.isFixedEssentialName("Dining", "餐饮"))
    }
}
