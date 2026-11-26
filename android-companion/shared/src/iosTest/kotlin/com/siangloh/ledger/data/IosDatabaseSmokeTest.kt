package com.siangloh.ledger.data

import com.siangloh.ledger.core.format.currentMonthString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
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
}
