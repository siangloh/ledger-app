package com.siangloh.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.entities.NotificationLog
import com.siangloh.ledger.parser.LocalNotificationParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationFilterInstrumentedTest {

    @Test
    fun promoAdsAndShopeeNoiseBlocked_andRealReceiptsRecordedInRoomPipeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)

        val promoSamples = listOf(
            "You've received a RM20 voucher! Min spend RM50." to "com.shopee.my",
            "Bayar guna DuitNow QR & menang pulangan tunai sehingga RM88! Transaksi minimum RM10." to "my.com.tngdigital.ewallet",
            "Enjoy RM10 instant rebate when you spend RM50 at Watsons!" to "my.com.tngdigital.ewallet",
            "Transfer RM50 via DuitNow Transfer to win iPhone 16!" to "com.maybank2u.life",
            "Seller replied: This item is RM 35.00 only!" to "com.shopee.my",
            "Your parcel worth RM 49.90 has been delivered!" to "com.shopee.my"
        )

        for ((raw, pkg) in promoSamples) {
            val parsed = LocalNotificationParser.parse(raw, pkg)
            val blocked = LocalNotificationParser.isPromotionalOrAd(raw) || parsed == null || parsed.isPromo
            assertTrue("Expected promo/noise blocked on device for: $raw", blocked)
        }

        // Real receipt with points footer + CrossAppDeduplicator check
        val dedup = LocalNotificationParser.CrossAppDeduplicator(windowMs = 180_000L)
        val realReceipt = "Payment of RM 15.00 to ZUS Coffee successful. You earned 15 GoRewards points!"
        assertFalse(LocalNotificationParser.isPromotionalOrAd(realReceipt))
        val parsedReal = LocalNotificationParser.parse(realReceipt, "my.com.tngdigital.ewallet")
        assertNotNull(parsedReal)
        assertFalse(parsedReal!!.isPromo)
        assertEquals(15.0, parsedReal.amount, 0.01)

        val t0 = System.currentTimeMillis()
        assertFalse(dedup.isDuplicateAndRecord(parsedReal.type, parsedReal.amount, t0))
        assertTrue(dedup.isDuplicateAndRecord(parsedReal.type, parsedReal.amount, t0 + 45_000L))

        // Verify Room notificationLogDao accepts the new cross-app duplicate outcome cleanly
        db.notificationLogDao().insert(
            NotificationLog(
                sourcePackage = "com.android.mms",
                rawText = "Maybank SMS: RM 15.00 paid to ZUS Coffee",
                matchedPhase1 = true,
                sentToBackend = false,
                outcome = "ignored_duplicate_cross_app"
            )
        )
        val logs = db.notificationLogDao().getRecentLogs(5)
        assertTrue("Expected ignored_duplicate_cross_app logged in Room", logs.any { it.outcome == "ignored_duplicate_cross_app" })
    }
}
