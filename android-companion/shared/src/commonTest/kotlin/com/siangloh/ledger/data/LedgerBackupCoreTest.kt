package com.siangloh.ledger.data

import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerBackupCoreTest {

    @Test
    fun backupJson_roundTripsAccountsCategoriesAndTransactionsInKmp() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "Touch 'n Go", type = "e_wallet", balance = 256.75, currency = "RM", orderNum = 0),
            LocalAccount(id = 2, name = "Maybank / MAE", type = "bank", balance = 4200.00, creditLimit = 0.0, currency = "RM", orderNum = 1)
        )
        val categories = listOf(
            LocalCategory(id = 10, name = "餐饮", type = "expense", icon = "restaurant", color = "#ef4444", orderNum = 0),
            LocalCategory(id = 11, name = "薪资", type = "income", icon = "payments", color = "#10b981", orderNum = 1)
        )
        val transactions = listOf(
            LocalTransaction(
                id = 501L,
                date = "2026-11-26",
                time = "13:15:00",
                type = "expense",
                category = "餐饮",
                amount = 24.90,
                note = "Nasi Lemak & Teh Tarik",
                tags = "lunch,kl",
                accountId = 1,
                toAccountId = null,
                source = "auto_notification",
                channel = "Touch 'n Go",
                createdAt = 1_700_000_111_000L,
                updatedAt = 1_700_000_222_000L
            ),
            LocalTransaction(
                id = 502L,
                date = "2026-11-26",
                time = "14:00:00",
                type = "transfer",
                category = "转账",
                amount = 1500.00,
                note = "Top up wallet",
                tags = "",
                accountId = 2,
                toAccountId = 1,
                source = "manual",
                channel = null,
                createdAt = 1_700_000_333_000L,
                updatedAt = 1_700_000_333_000L
            )
        )

        val json = LedgerBackupCore.buildBackupJson(accounts, categories, transactions, "2026-11-26T14:30:00")
        val parsed = LedgerBackupCore.parseBackupJson(json)

        assertEquals(LedgerBackupCore.CURRENT_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals("2026-11-26T14:30:00", parsed.exportedAt)
        assertEquals(2, parsed.accounts.size)
        assertEquals("Touch 'n Go", parsed.accounts[0].name)
        assertTrue(abs(parsed.accounts[0].balance - 256.75) < 0.001)
        assertEquals(2, parsed.categories.size)
        assertEquals("餐饮", parsed.categories[0].name)
        assertEquals(2, parsed.transactions.size)

        val tx0 = parsed.transactions[0]
        assertEquals(501L, tx0.id)
        assertTrue(abs(tx0.amount - 24.90) < 0.001)
        assertEquals(1, tx0.accountId)
        assertNull(tx0.toAccountId)
        assertEquals("Touch 'n Go", tx0.channel)

        val tx1 = parsed.transactions[1]
        assertEquals(2, tx1.accountId)
        assertEquals(1, tx1.toAccountId)
        assertNull(tx1.channel)
    }

    @Test
    fun parseBackupJson_rejectsBlankAndInvalidJson() {
        assertFailsWith<IllegalArgumentException> {
            LedgerBackupCore.parseBackupJson("   ")
        }
        assertFailsWith<IllegalArgumentException> {
            LedgerBackupCore.parseBackupJson("""{"schema_version": 0, "accounts": [], "transactions": []}""")
        }
        assertFailsWith<IllegalArgumentException> {
            LedgerBackupCore.parseBackupJson("""{"hello": "world"}""")
        }
    }

    @Test
    fun buildTransactionsCsv_formatsWithoutGroupingAndEscapesFields() {
        val txs = listOf(
            LocalTransaction(
                id = 1L,
                date = "2026-11-26",
                time = "09:15:00",
                type = "expense",
                category = "餐饮",
                amount = 1234.5,
                note = "Cafe \"A\", Kopi\nExtra",
                tags = "team,breakfast",
                accountId = 1,
                toAccountId = null,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val csv = LedgerBackupCore.buildTransactionsCsv(txs, mapOf(1 to "Touch 'n Go"))
        assertTrue(csv.startsWith("\uFEFFDate,Time,Type,Category,Amount,Account,ToAccount,Note,Tags\r\n"))
        assertTrue(csv.contains("2026-11-26,09:15:00,expense,餐饮,1234.50,Touch 'n Go,,\"Cafe \"\"A\"\", Kopi\nExtra\",\"team,breakfast\"\r\n"))
    }
}
