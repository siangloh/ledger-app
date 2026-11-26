package com.siangloh.ledger.data

import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedLedgerEngineTest {

    @Test
    fun computeOverviewStats_calculatesIncomeExpenseSavingsNetBalanceAndExcludesTransfers() {
        val txs = listOf(
            LocalTransaction(
                id = 1,
                date = "2026-11-25",
                type = "income",
                category = "工资",
                amount = 5000.0,
                accountId = 3
            ),
            LocalTransaction(
                id = 2,
                date = "2026-11-20",
                type = "expense",
                category = "餐饮",
                amount = 145.60,
                accountId = 2
            ),
            LocalTransaction(
                id = 3,
                date = "2026-11-18",
                type = "expense",
                category = "账单",
                amount = 254.40,
                accountId = 3
            ),
            LocalTransaction(
                id = 4,
                date = "2026-11-15",
                type = "savings",
                category = "定期存款",
                amount = 1000.0,
                accountId = 3
            ),
            // Internal transfer must NOT be counted in income, expense, or savings
            LocalTransaction(
                id = 5,
                date = "2026-11-12",
                type = "transfer",
                category = "转账",
                amount = 300.0,
                accountId = 3,
                toAccountId = 2
            ),
            // Previous month transaction must be ignored for 2026-11 monthly overview
            LocalTransaction(
                id = 6,
                date = "2026-10-31",
                type = "expense",
                category = "购物",
                amount = 999.0,
                accountId = 7
            )
        )

        val stats = SharedLedgerEngine.computeOverviewStats(
            transactions = txs,
            yearMonth = "2026-11",
            period = "month"
        )

        assertEquals("month", stats.period)
        assertEquals("2026-11", stats.yearMonth)
        assertEquals(5000.0, stats.income)
        assertEquals(400.0, stats.expense)
        assertEquals(1000.0, stats.savings)
        // netBalance = income (5000) - expense (400) - savings (1000) = 3600
        assertEquals(3600.0, stats.netBalance)
        assertEquals(5, stats.recentTransactions.size)
    }

    @Test
    fun computeOverviewStats_supportsThreeMonthAndYearRanges() {
        val txs = listOf(
            LocalTransaction(id = 1, date = "2026-11-10", type = "expense", category = "餐饮", amount = 100.0),
            LocalTransaction(id = 2, date = "2026-09-01", type = "expense", category = "交通", amount = 50.0),
            LocalTransaction(id = 3, date = "2026-08-31", type = "expense", category = "购物", amount = 200.0),
            LocalTransaction(id = 4, date = "2025-12-25", type = "income", category = "工资", amount = 4000.0)
        )

        val threeMonth = SharedLedgerEngine.computeOverviewStats(txs, yearMonth = "2026-11", period = "3m")
        assertEquals(150.0, threeMonth.expense)
        assertEquals(-150.0, threeMonth.netBalance)

        val yearly = SharedLedgerEngine.computeOverviewStats(txs, yearMonth = "2026-11", period = "year")
        assertEquals(350.0, yearly.expense)
        assertEquals(0.0, yearly.income)
    }

    @Test
    fun computeAccountsSummary_excludesInternalTransferFromMonthlyInAndOut() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "Maybank / MAE", type = "savings", balance = 4200.0, orderNum = 1),
            LocalAccount(id = 2, name = "Touch 'n Go", type = "e_wallet", balance = 250.0, orderNum = 2)
        )
        val txs = listOf(
            LocalTransaction(id = 1, date = "2026-11-01", type = "income", category = "工资", amount = 4500.0, accountId = 1),
            LocalTransaction(id = 2, date = "2026-11-03", type = "expense", category = "账单", amount = 120.0, accountId = 1),
            // Internal transfer from Maybank (1) to Touch 'n Go (2) must NOT appear in monthlyOut or monthlyIn
            LocalTransaction(id = 3, date = "2026-11-05", type = "transfer", category = "转账", amount = 300.0, accountId = 1, toAccountId = 2),
            LocalTransaction(id = 4, date = "2026-11-06", type = "expense", category = "餐饮", amount = 50.0, accountId = 2),
            LocalTransaction(id = 5, date = "2026-11-07", type = "savings", category = "应急基金", amount = 500.0, accountId = 1),
            // Out-of-month transaction excluded
            LocalTransaction(id = 6, date = "2026-10-20", type = "expense", category = "杂货", amount = 80.0, accountId = 2)
        )

        val summary = SharedLedgerEngine.computeAccountsSummary(
            accounts = accounts,
            transactions = txs,
            yearMonth = "2026-11"
        )

        val maybankItem = summary.items.first { it.account.id == 1 }
        val tngItem = summary.items.first { it.account.id == 2 }

        assertEquals(4500.0, maybankItem.monthlyIn)
        assertEquals(120.0, maybankItem.monthlyOut)
        assertEquals(0.0, tngItem.monthlyIn)
        assertEquals(50.0, tngItem.monthlyOut)
    }

    @Test
    fun computeAccountsSummary_calculatesCreditCardLiabilitiesAndNetWorthCorrectly() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "现金", type = "cash", balance = 300.0, orderNum = 1),
            LocalAccount(id = 2, name = "Maybank / MAE", type = "savings", balance = 2700.0, orderNum = 2),
            LocalAccount(id = 3, name = "信用卡", type = "credit_card", balance = -650.25, creditLimit = 5000.0, orderNum = 3),
            LocalAccount(id = 4, name = "备用信用卡", type = "credit_card", balance = 0.0, creditLimit = 3000.0, orderNum = 4),
            LocalAccount(id = 5, name = "透支钱包", type = "e_wallet", balance = -49.75, orderNum = 5)
        )

        val summary = SharedLedgerEngine.computeAccountsSummary(
            accounts = accounts,
            transactions = emptyList(),
            yearMonth = "2026-11"
        )

        assertEquals(3000.0, summary.totalAssets)
        assertEquals(700.0, summary.totalLiabilities)
        assertEquals(2300.0, summary.netWorth)
        assertEquals(5, summary.items.size)
    }

    @Test
    fun computeAccountDeltas_handlesIncomeExpenseSavingsAndTransferAccurately() {
        assertEquals(
            mapOf(1 to 2500.0),
            SharedLedgerEngine.computeAccountDeltas(type = "income", amount = 2500.0, accountId = 1, toAccountId = null)
        )
        assertEquals(
            mapOf(2 to -35.50),
            SharedLedgerEngine.computeAccountDeltas(type = "expense", amount = 35.50, accountId = 2, toAccountId = null)
        )
        assertEquals(
            mapOf(1 to -500.0),
            SharedLedgerEngine.computeAccountDeltas(type = "savings", amount = 500.0, accountId = 1, toAccountId = null)
        )
        assertEquals(
            mapOf(1 to -200.0, 2 to 200.0),
            SharedLedgerEngine.computeAccountDeltas(type = "transfer", amount = 200.0, accountId = 1, toAccountId = 2)
        )
        // Self-transfer cancels out cleanly
        assertTrue(
            SharedLedgerEngine.computeAccountDeltas(type = "transfer", amount = 200.0, accountId = 1, toAccountId = 1).isEmpty()
        )
    }

    @Test
    fun defaultsAndCleanCategory_seedExpectedAccountsAndCategories() {
        val accounts = SharedLedgerEngine.defaultAccounts()
        assertEquals(7, accounts.size)
        assertTrue(accounts.any { it.name == "Touch 'n Go" && it.type == "e_wallet" })
        assertTrue(accounts.any { it.name == "Maybank / MAE" && it.type == "savings" })
        assertTrue(accounts.any { it.name == "Public Bank" && it.type == "savings" })
        assertTrue(accounts.any { it.name == "CIMB Bank" && it.type == "savings" })
        assertTrue(accounts.any { it.name == "GrabPay" && it.type == "e_wallet" })
        assertTrue(accounts.any { it.type == "credit_card" && it.creditLimit == 5000.0 })

        val categories = SharedLedgerEngine.defaultCategories()
        assertTrue(categories.any { it.type == "expense" })
        assertTrue(categories.any { it.type == "income" })
        assertTrue(categories.any { it.type == "savings" })

        assertEquals("餐饮", SharedLedgerEngine.cleanCategoryName("""{"category": "餐饮"}"""))
        assertEquals("其他", SharedLedgerEngine.cleanCategoryName("   "))
    }
}
