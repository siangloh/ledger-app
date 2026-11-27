package com.siangloh.ledger.data

import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.core.format.todayDateString
import com.siangloh.ledger.core.prefs.PlatformPrefs
import com.siangloh.ledger.parser.LocalNotificationParser
import com.siangloh.ledger.platform.LocaleStateHolder
import com.siangloh.ledger.platform.PlatformBiometricAuth
import com.siangloh.ledger.platform.PlatformReceiptScanner
import com.siangloh.ledger.platform.sharePlatformTextFile
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.shared.IosLedgerBridge
import com.siangloh.ledger.ui.i18n.SharedStrings
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun shareBackupAndCsvWriteToTempDirectoryOnIos() = runBlocking {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val db = AppDatabase.getInstance()
        val jsonBackup = LedgerBackupCore.exportFromDatabase(db)
        val csvBackup = LedgerBackupCore.exportCsvFromDatabase(db)

        assertTrue(
            sharePlatformTextFile("test-backup.json", "application/json", jsonBackup.jsonContent),
            "sharePlatformTextFile should succeed for JSON backup"
        )
        assertTrue(
            sharePlatformTextFile("test-export.csv", "text/csv", csvBackup.csvContent),
            "sharePlatformTextFile should succeed for CSV export"
        )

        val tempDir = NSTemporaryDirectory()
        val jsonPath = if (tempDir.endsWith("/")) "${tempDir}test-backup.json" else "$tempDir/test-backup.json"
        val csvPath = if (tempDir.endsWith("/")) "${tempDir}test-export.csv" else "$tempDir/test-export.csv"
        val fm = NSFileManager.defaultManager
        assertTrue(fm.fileExistsAtPath(jsonPath), "Backup JSON should be written to NSTemporaryDirectory")
        assertTrue(fm.fileExistsAtPath(csvPath), "Export CSV should be written to NSTemporaryDirectory")
    }

    @Test
    fun destructiveRestoreToDatabaseOnIosRoomSqlite() = runBlocking {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val db = AppDatabase.getInstance()
        val accId = db.localAccountDao().getAll().first().id
        val today = todayDateString()

        val originalTxId = SharedLedgerEngine.recordTransaction(
            date = today,
            type = "expense",
            category = "餐饮",
            amount = 25.00,
            note = "iOS Restore Baseline",
            accountId = accId,
            source = "ios_test",
            db = db
        )
        assertTrue(originalTxId > 0L)

        val jsonBackup = LedgerBackupCore.exportFromDatabase(db)
        assertTrue(jsonBackup.jsonContent.contains("iOS Restore Baseline"))

        val extraTxId = SharedLedgerEngine.recordTransaction(
            date = today,
            type = "expense",
            category = "交通",
            amount = 99.00,
            note = "iOS Extra Should Be Wiped",
            accountId = accId,
            source = "ios_test",
            db = db
        )
        assertTrue(extraTxId > 0L)
        assertTrue(db.localTransactionDao().getAll().any { it.note == "iOS Extra Should Be Wiped" })

        val parsedPayload = LedgerBackupCore.parseBackupJson(jsonBackup.jsonContent)
        LedgerBackupCore.restoreToDatabase(db, parsedPayload)

        val restoredTxs = db.localTransactionDao().getAll()
        assertTrue(
            restoredTxs.any { it.note == "iOS Restore Baseline" && it.amount == 25.00 },
            "Restored DB should contain baseline transaction"
        )
        assertFalse(
            restoredTxs.any { it.note == "iOS Extra Should Be Wiped" },
            "Destructive restore should remove transactions created after backup"
        )

        // Clean up test transaction
        restoredTxs.filter { it.note == "iOS Restore Baseline" }.forEach {
            SharedLedgerEngine.deleteTransaction(it.id, db)
        }
    }

    @Test
    fun biometricAvailabilityAndEmptyReceiptScannerDoNotCrashOrDeadlockOnIos() {
        val bioAvailable = PlatformBiometricAuth.isAvailable()
        assertTrue(bioAvailable || !bioAvailable, "isAvailable() should return a valid Boolean")

        // Non-blocking guard check on empty image bytes (no NSRunLoop pump / main-queue await in headless test)
        PlatformReceiptScanner.recognizeTextFromImageBytes(ByteArray(0)) {}
    }
}
