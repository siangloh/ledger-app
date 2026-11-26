package com.siangloh.ledger.data

import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.core.format.todayDateString
import com.siangloh.ledger.core.prefs.PlatformPrefs
import com.siangloh.ledger.parser.LocalNotificationParser
import com.siangloh.ledger.platform.LocaleStateHolder
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.shared.IosLedgerBridge
import com.siangloh.ledger.ui.i18n.SharedStrings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDatabaseSmokeTest {
    @Test
    fun databaseInitAndOverviewQuerySucceedOnIos() = runBlocking {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val month = currentMonthString()
        val stats = SharedLedgerEngine.getOverviewStats(month)
        val accounts = SharedLedgerEngine.getAccountsSummary(month)
        assertTrue(accounts.items.isNotEmpty(), "Default accounts should be seeded on iOS")
        assertTrue(stats.yearMonth == month, "Overview stats month should match requested month")
    }

    @Test
    fun toastAndLocaleSwitchNeverCrashOnIos() {
        val original = LocaleStateHolder.language.value
        try {
            listOf("zh", "en", "ms").forEach { code ->
                LocaleStateHolder.setLanguage(code)
                assertEquals(code, LocaleStateHolder.language.value)
                val strings = SharedStrings.forLanguage(code)
                assertTrue(strings.overviewTitle.isNotBlank())
                assertTrue(SharedStrings.localizeCategory("餐饮", code).isNotBlank())
                showPlatformToast("Language switched to $code (100% %@ %s %d)")
            }
            val prefs = PlatformPrefs("ios_smoke_test_prefs")
            prefs.putString("k_str", "中文测试")
            prefs.putBoolean("k_bool", true)
            prefs.putInt("k_int", 42)
            assertEquals("中文测试", prefs.getString("k_str", null))
            assertEquals(true, prefs.getBoolean("k_bool", false))
            assertEquals(42, prefs.getInt("k_int", 0))
            prefs.clear()
            assertNull(prefs.getString("k_str", null))
        } finally {
            LocaleStateHolder.setLanguage(original)
        }
    }

    @Test
    fun recordDeleteAndBackupRoundTripOnIos() = runBlocking {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val db = AppDatabase.getInstance()
        val accId = db.localAccountDao().getAll().first().id
        val today = todayDateString()
        val month = currentMonthString()

        val txId = SharedLedgerEngine.recordTransaction(
            date = today,
            type = "expense",
            category = "餐饮",
            amount = 18.50,
            note = "iOS Smoke Lunch",
            accountId = accId,
            source = "ios_test",
            db = db
        )
        assertTrue(txId > 0L)

        val stats = SharedLedgerEngine.getOverviewStats(month, db)
        assertTrue(stats.expense >= 18.50)

        val jsonBackup = LedgerBackupCore.exportFromDatabase(db)
        assertTrue(jsonBackup.jsonContent.contains("iOS Smoke Lunch"))
        val csvBackup = LedgerBackupCore.exportCsvFromDatabase(db)
        assertTrue(csvBackup.csvContent.contains("iOS Smoke Lunch"))

        assertTrue(SharedLedgerEngine.deleteTransaction(txId, db))

        val parsed = LocalNotificationParser.parse(
            "Maybank: You spent RM12.00 at Starbucks",
            "ios.shortcuts"
        )
        assertNotNull(parsed)
        assertEquals(12.0, parsed.amount)

        // Non-blocking guard check on invalid amount (no main-queue await inside runBlocking)
        IosLedgerBridge.recordApplePayExpense(0.0, "", "") {}
    }
}
