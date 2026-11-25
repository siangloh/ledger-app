package com.siangloh.ledger.data

import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerBackupManagerTest {

    @Test
    fun backupJson_roundTripsAccountsCategoriesAndTransactions() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "Touch 'n Go", type = "e_wallet", balance = 128.50, currency = "RM", orderNum = 0),
            LocalAccount(id = 2, name = "Maybank", type = "savings", balance = 5400.00, currency = "RM", orderNum = 1)
        )
        val categories = listOf(
            LocalCategory(id = 10, name = "餐饮", type = "expense", icon = "", color = "#ef4444", orderNum = 0)
        )
        val transactions = listOf(
            LocalTransaction(
                id = 101L,
                date = "2026-11-25",
                time = "12:30:00",
                type = "expense",
                category = "餐饮",
                amount = 18.50,
                note = "Nasi Lemak, Teh Tarik",
                tags = "lunch",
                accountId = 1,
                toAccountId = null,
                source = "manual",
                channel = "TNG",
                createdAt = 1700000000000L,
                updatedAt = 1700000000000L
            )
        )

        val json = LedgerBackupManager.buildBackupJson(accounts, categories, transactions, "2026-11-25T15:00:00")
        val parsed = LedgerBackupManager.parseBackupJson(json)

        assertEquals(LedgerBackupManager.CURRENT_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals("2026-11-25T15:00:00", parsed.exportedAt)
        assertEquals(2, parsed.accounts.size)
        assertEquals("Touch 'n Go", parsed.accounts[0].name)
        assertEquals(128.50, parsed.accounts[0].balance, 0.001)
        assertEquals(1, parsed.categories.size)
        assertEquals("餐饮", parsed.categories[0].name)
        assertEquals(1, parsed.transactions.size)
        assertEquals(18.50, parsed.transactions[0].amount, 0.001)
        assertEquals(1, parsed.transactions[0].accountId)
        assertNull(parsed.transactions[0].toAccountId)
        assertEquals("Nasi Lemak, Teh Tarik", parsed.transactions[0].note)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseBackupJson_rejectsInvalidSchema() {
        LedgerBackupManager.parseBackupJson("""{"hello":"world"}""")
    }

    @Test
    fun buildTransactionsCsv_includesBomAndEscapesCommasAndQuotes() {
        val txs = listOf(
            LocalTransaction(
                id = 1L,
                date = "2026-11-25",
                time = "09:15:00",
                type = "expense",
                category = "餐饮",
                amount = 12.9,
                note = "Cafe \"A\", Kopi",
                tags = "breakfast",
                accountId = 1,
                toAccountId = null
            )
        )
        val csv = LedgerBackupManager.buildTransactionsCsv(txs, mapOf(1 to "Touch 'n Go"))
        assertTrue(csv.startsWith("\uFEFFDate,Time,Type,Category,Amount,Account,ToAccount,Note,Tags\r\n"))
        assertTrue(csv.contains("2026-11-25,09:15:00,expense,餐饮,12.90,Touch 'n Go,,\"Cafe \"\"A\"\", Kopi\",breakfast\r\n"))
    }
}
