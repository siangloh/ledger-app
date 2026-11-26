package com.siangloh.ledger.data

import com.siangloh.ledger.core.format.daysInMonth
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.shiftMonth
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.parser.LocalNotificationParser
import com.siangloh.ledger.parser.ReceiptTextParser
import com.siangloh.ledger.ui.i18n.SharedStrings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealEnvironmentEdgeCaseTest {

    @Test
    fun stressTest_360TransactionsAcrossMultipleYearsAndMonths() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "现金", type = "cash", balance = 1200.0, orderNum = 1),
            LocalAccount(id = 2, name = "Touch 'n Go", type = "e_wallet", balance = 850.50, orderNum = 2),
            LocalAccount(id = 3, name = "Maybank / MAE", type = "savings", balance = 15000.0, orderNum = 3),
            LocalAccount(id = 4, name = "信用卡", type = "credit_card", balance = -1450.25, creditLimit = 8000.0, orderNum = 4)
        )

        val txs = mutableListOf<LocalTransaction>()
        var idSeq = 1L
        val years = listOf(2024, 2025, 2026)
        for (year in years) {
            for (month in 1..12) {
                val ym = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
                repeat(10) { idx ->
                    val day = (idx + 1).toString().padStart(2, '0')
                    val type = when (idx % 4) {
                        0 -> "income"
                        1 -> "expense"
                        2 -> "savings"
                        else -> "transfer"
                    }
                    val amount = when (type) {
                        "income" -> 1000.10
                        "expense" -> 45.35
                        "savings" -> 200.00
                        else -> 150.00
                    }
                    txs.add(
                        LocalTransaction(
                            id = idSeq++,
                            date = "$ym-$day",
                            time = "12:00:00",
                            type = type,
                            category = if (type == "transfer") "内部转账" else "餐饮",
                            amount = amount,
                            note = "Entry #$idx for $ym",
                            accountId = if (type == "income") 3 else 2,
                            toAccountId = if (type == "transfer" || type == "savings") 3 else null
                        )
                    )
                }
            }
        }

        assertEquals(360, txs.size)

        // Each month has: 3 income (3000.30), 3 expense (136.05), 2 savings (400.00), 2 transfer (300.00, excluded)
        val novStats = SharedLedgerEngine.computeOverviewStats(txs, yearMonth = "2026-11", period = "month")
        assertEquals(3000.30, novStats.income)
        assertEquals(136.05, novStats.expense)
        assertEquals(400.00, novStats.savings)
        assertEquals(2464.25, novStats.netBalance)
        assertEquals(10, novStats.recentTransactions.size)

        // Yearly stats for 2025: 12 months * monthly values
        val year2025 = SharedLedgerEngine.computeOverviewStats(txs, yearMonth = "2025-12", period = "year")
        assertEquals(36003.60, year2025.income)
        assertEquals(1632.60, year2025.expense)
        assertEquals(4800.00, year2025.savings)
        assertEquals(29571.00, year2025.netBalance)

        // Accounts summary excludes transfers from monthlyIn/monthlyOut
        val accSummary = SharedLedgerEngine.computeAccountsSummary(accounts, txs, yearMonth = "2026-11")
        assertEquals(17050.50, accSummary.totalAssets)
        assertEquals(1450.25, accSummary.totalLiabilities)
        assertEquals(15600.25, accSummary.netWorth)
        val tngItem = accSummary.items.first { it.account.id == 2 }
        assertEquals(0.0, tngItem.monthlyIn)
        assertEquals(136.05, tngItem.monthlyOut)
    }

    @Test
    fun malformedDateResilience_shiftMonthAndDaysInMonthNeverThrow() {
        val malformedInputs = listOf(
            "",
            "   ",
            "2024/01",
            "2024-13",
            "2024-00",
            "0000-01",
            "abc-de",
            "2024",
            "2024-11-01",
            "99999-12"
        )
        val ymPattern = Regex("""\d{4}-\d{2}""")

        for (bad in malformedInputs) {
            val shiftedPrev = shiftMonth(bad, -1)
            val shiftedNext = shiftMonth(bad, 1)
            assertTrue(ymPattern.matches(shiftedPrev), "shiftMonth($bad, -1) returned invalid '$shiftedPrev'")
            assertTrue(ymPattern.matches(shiftedNext), "shiftMonth($bad, 1) returned invalid '$shiftedNext'")

            val days = daysInMonth(bad)
            assertTrue(days in 28..31, "daysInMonth($bad) returned out-of-range $days")
        }
    }

    @Test
    fun specialCharacters_backupJsonCsvAndSharedStringsRoundTrip() {
        val trickyNote = "100% %@ %s \$HOME \"quotes\", commas,\nnewlines, 中文, Melayu, 🎉💳"
        val trickyTags = "tag1,tag2,\"quoted\",中文"
        val accounts = listOf(
            LocalAccount(id = 1, name = "TNG \"eWallet\" 🎉", type = "e_wallet", balance = 123.45, orderNum = 1)
        )
        val categories = listOf(
            LocalCategory(id = 1, name = "餐饮", type = "expense", icon = "🍔", color = "#fb923c", orderNum = 1)
        )
        val txs = listOf(
            LocalTransaction(
                id = 99L,
                date = "2026-11-26",
                time = "18:30:00",
                type = "expense",
                category = "餐饮",
                amount = 88.88,
                note = trickyNote,
                tags = trickyTags,
                accountId = 1,
                source = "manual",
                channel = "Apple Pay 🍎"
            )
        )

        // JSON round-trip
        val json = LedgerBackupCore.buildBackupJson(accounts, categories, txs, "2026-11-26T18:30:00")
        val parsed = LedgerBackupCore.parseBackupJson(json)
        assertEquals(1, parsed.accounts.size)
        assertEquals("TNG \"eWallet\" 🎉", parsed.accounts[0].name)
        assertEquals(1, parsed.transactions.size)
        assertEquals(trickyNote, parsed.transactions[0].note)
        assertEquals(trickyTags, parsed.transactions[0].tags)
        assertEquals("Apple Pay 🍎", parsed.transactions[0].channel)

        // CSV export escaping
        val csv = LedgerBackupCore.buildTransactionsCsv(txs, mapOf(1 to accounts[0].name))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"TNG \"\"eWallet\"\" 🎉\""))
        assertTrue(csv.contains("\"100% %@ %s \$HOME \"\"quotes\"\", commas,\nnewlines, 中文, Melayu, 🎉💳\""))

        // SharedStrings formatting across zh, en, ms
        for (lang in listOf("zh", "en", "ms")) {
            val s = SharedStrings.forLanguage(lang)
            val savedMsg = SharedStrings.format1(s.savedAmountFmt, formatMoney(88.88))
            assertTrue(savedMsg.contains("88.88"))
            val backupSummary = SharedStrings.format2(s.backupSummaryFmt, 4, 360)
            assertTrue(backupSummary.contains("4") && backupSummary.contains("360"))
            val delConfirm = SharedStrings.format3(
                s.deleteTransactionConfirmFmt,
                "100% %@ \$HOME 🎉",
                "88.88",
                "2026-11-26"
            )
            assertTrue(delConfirm.contains("100% %@ \$HOME 🎉"))
            assertTrue(delConfirm.contains("88.88"))
            assertTrue(delConfirm.contains("2026-11-26"))
        }
    }

    @Test
    fun multiAccountDeltas_transfersSavingsAndDeletionReversal() {
        // Expense from account 1
        val expenseDelta = SharedLedgerEngine.computeAccountDeltas("expense", 42.50, accountId = 1, toAccountId = null)
        assertEquals(mapOf(1 to -42.50), expenseDelta)

        // Income to account 2
        val incomeDelta = SharedLedgerEngine.computeAccountDeltas("income", 500.00, accountId = 2, toAccountId = null)
        assertEquals(mapOf(2 to 500.00), incomeDelta)

        // Savings with destination account
        val savingsWithDest = SharedLedgerEngine.computeAccountDeltas("savings", 300.00, accountId = 2, toAccountId = 3)
        assertEquals(mapOf(2 to -300.00, 3 to 300.00), savingsWithDest)

        // Self-transfer (same source and destination) nets to empty map
        val selfTransfer = SharedLedgerEngine.computeAccountDeltas("transfer", 100.00, accountId = 2, toAccountId = 2)
        assertTrue(selfTransfer.isEmpty())

        // Zero amount produces no deltas
        val zeroDelta = SharedLedgerEngine.computeAccountDeltas("expense", 0.0, accountId = 1, toAccountId = null)
        assertTrue(zeroDelta.isEmpty())
    }

    @Test
    fun parsersEdgeCases_bankSmsAndReceiptOcr() {
        // Promo SMS must be flagged as promo
        val promoSms = LocalNotificationParser.parse(
            "RM0.00: Spend RM50 with MAE & get RM10 cashback! T&Cs apply.",
            "com.maybank2u.life"
        )
        assertNotNull(promoSms)
        assertTrue(promoSms.isPromo)

        // OTP SMS must be ignored/promo
        val otpSms = LocalNotificationParser.parse(
            "RM0.00 Maybank: Your TAC is 482910 for transfer of RM 250.00. Do not share OTP.",
            "com.android.mms"
        )
        assertTrue(otpSms == null || otpSms.isPromo)

        // Real completed bank expense SMS
        val realExpense = LocalNotificationParser.parse(
            "You have paid RM 38.90 to VILLAGE PARK NASI LEMAK using your Maybank Card.",
            "com.maybank2u.life"
        )
        assertNotNull(realExpense)
        assertFalse(realExpense.isPromo)
        assertEquals("expense", realExpense.type)
        assertTrue(abs(realExpense.amount - 38.90) < 0.001)

        // Receipt OCR with comma-decimal, broken decimal space, service charge, SST, rounding
        val receiptLines = listOf(
            "KOPI TIAM SDN BHD",
            "Date: 26/11/2026",
            "Kopi O Ice 3,50",
            "Nasi Lemak Ayam 14 . 50",
            "Subtotal 18.00",
            "Service Charge 10% 1.80",
            "SST 6% 1.08",
            "Rounding -0.03",
            "Total RM 20.85"
        )
        val parsedReceipt = ReceiptTextParser.parseLinesToReceiptResult(receiptLines)
        assertEquals("KOPI TIAM SDN BHD", parsedReceipt.merchant)
        assertEquals(2, parsedReceipt.items.size)
        assertTrue(abs(parsedReceipt.detectedTotal - 20.85) < 0.01)
        assertTrue(parsedReceipt.isTallied)

        // Empty / garbage receipt lines should not crash
        val emptyReceipt = ReceiptTextParser.parseLinesToReceiptResult(listOf("", "   ", "---"))
        assertEquals(0, emptyReceipt.items.size)
        assertEquals(0.0, emptyReceipt.detectedTotal)

        // Blank SMS returns null
        assertNull(LocalNotificationParser.parse("   ", "ios.shortcuts"))
    }
}
