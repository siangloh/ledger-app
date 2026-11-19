package com.siangloh.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.BillingCycleRecord
import com.siangloh.ledger.data.BudgetItemRecord
import com.siangloh.ledger.data.BudgetOverviewRecord
import com.siangloh.ledger.data.LocalFinancialAdvisor
import com.siangloh.ledger.data.OverallBudgetRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvisorInstrumentedTest {

    @Test
    fun analyze_generatesDeepInsightsWithoutStringFormatErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val cashflow = JSONObject().apply {
            put("currency", "RM")
            put("income", 4200.0)
            put("expense", 3100.0)
            put("savings", 300.0)
            put("reserved", 500.0)
            put("available", 600.0)
            put("expense_categories", JSONArray().apply {
                put(JSONObject().apply { put("label", "房租"); put("amount", 1500.0) })
                put(JSONObject().apply { put("label", "餐饮"); put("amount", 1100.0) })
                put(JSONObject().apply { put("label", "娱乐"); put("amount", 500.0) })
            })
            put("bills", JSONArray().apply {
                put(JSONObject().apply {
                    put("title", "Tenaga Electric")
                    put("pending", 220.0)
                    put("is_paid", false)
                })
                put(JSONObject().apply {
                    put("title", "Unifi Fibre")
                    put("pending", 140.0)
                    put("is_paid", false)
                })
            })
            put("transactions", JSONArray().apply {
                put(JSONObject().apply { put("date", "2026-11-02"); put("type", "expense"); put("category", "餐饮"); put("amount", 14.0); put("note", "Zus Coffee #1") })
                put(JSONObject().apply { put("date", "2026-11-03"); put("type", "expense"); put("category", "餐饮"); put("amount", 15.0); put("note", "Zus Coffee") })
                put(JSONObject().apply { put("date", "2026-11-04"); put("type", "expense"); put("category", "餐饮"); put("amount", 16.0); put("note", "Zus Coffee") })
                put(JSONObject().apply { put("date", "2026-11-05"); put("type", "expense"); put("category", "餐饮"); put("amount", 15.0); put("note", "Zus Coffee") })
                put(JSONObject().apply { put("date", "2026-11-07"); put("type", "expense"); put("category", "娱乐"); put("amount", 180.0); put("note", "Weekend Dining") })
                put(JSONObject().apply { put("date", "2026-11-08"); put("type", "expense"); put("category", "娱乐"); put("amount", 140.0); put("note", "Cinema & Mall") })
                put(JSONObject().apply { put("date", "2026-11-14"); put("type", "expense"); put("category", "娱乐"); put("amount", 160.0); put("note", "Hotpot") })
            })
        }

        val budget = BudgetOverviewRecord(
            month = "2026-11",
            billingCycle = BillingCycleRecord("2026-11-01", "2026-11-30", "Nov", 1, 30, 15, 15),
            overall = OverallBudgetRecord(3800.0, 3100.0, 700.0, 81.5, "ok", 46.67, true, true),
            categories = listOf(
                BudgetItemRecord("餐饮", 900.0, 1100.0, -200.0, 122.2, "over", "#EF4444")
            ),
            totalCategoriesBudgeted = 1,
            overCount = 1,
            warnCount = 0,
            okCount = 0
        )

        val accounts = listOf(
            AccountRecord(1, "Maybank", "savings", 9500.0, 0.0, "MYR", null, null),
            AccountRecord(2, "Visa Rewards", "credit_card", -2600.0, 4000.0, "MYR", 5, 25)
        )

        val report = LocalFinancialAdvisor.analyze(context, cashflow, budget, accounts)
        assertTrue(report.hasProjection)
        assertNotNull(report.runwayMonths)
        assertTrue("Expected >= 4 deep insights, got ${report.insights.size}", report.insights.size >= 4)
        assertTrue(report.needsPct > 0.0 && report.wantsPct > 0.0)
        assertTrue(report.categoryHealth.any { it.isFixed && it.isOk })
        assertTrue(report.categoryHealth.any { !it.isFixed && !it.isOk })
    }
}
