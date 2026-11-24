package com.siangloh.ledger

import com.siangloh.ledger.parser.LocalNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalNotificationParserTest {

    @Test
    fun parse_petrolAndOilPayments_categorizesAsTransportAndExtractsMerchant() {
        val cases = listOf(
            "Payment of RM50.00 to PETRONAS DAGANGAN BHD successful" to "my.com.tngdigital.ewallet",
            "Setel: Fuel payment RM 45.50 successful" to "com.setel.mobile",
            "You paid RM60.00 for petrol at Shell Station" to "com.maybank2u.life",
            "Bayaran RM 35.00 berjaya di BHPetrol Kepong" to "com.v2.cimb.malaysia",
            "Transaction of RM 80.00 paid for engine oil" to null,
            "RM55.00 spent on RON95 fuel" to "my.com.tngdigital.ewallet"
        )

        for ((text, pkg) in cases) {
            val result = LocalNotificationParser.parse(text, pkg)
            assertNotNull("Expected non-null parse for: $text", result)
            assertEquals("Expected 交通 category for: $text", "交通", result!!.category)
            assertFalse("Description must not be 本地消费 for: $text", result.merchant.contains("本地消费"))
            assertFalse("Description must not be 本地商户 for: $text", result.merchant.contains("本地商户"))
        }
    }

    @Test
    fun parse_unknownMerchantExpense_defaultsToOtherNotDining() {
        val result = LocalNotificationParser.parse("You have paid RM 25.00 for service", "my.com.tngdigital.ewallet")
        assertNotNull(result)
        assertEquals("其他", result!!.category)
        assertFalse(result.merchant == "本地消费")
        assertFalse(result.merchant == "本地商户")
    }

    @Test
    fun predictCategory_shortKeywordsRespectWordBoundaries() {
        // "Steam" contains "tea" as substring, must match 娱乐, not 餐饮
        assertEquals("娱乐", LocalNotificationParser.predictCategory("Steam Games", "paid rm 30.00 to steam games"))
        // "Boil" / "Toilet" contains "oil" as substring, must NOT match 交通
        assertEquals("其他", LocalNotificationParser.predictCategory("Toiletries Shop", "paid rm 15.00 to toiletries shop"))
    }

    @Test
    fun parse_peerTransfers_classifyAsIncomeOrExpenseNotInternalTransfer() {
        val incoming = LocalNotificationParser.parse(
            "You have received RM 66.66 via DuitNow transfer from NG TIAN YONG",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(incoming)
        assertEquals("income", incoming!!.type)

        val outgoing = LocalNotificationParser.parse(
            "You have transferred RM 2.34 to NG TIAN YONG",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(outgoing)
        assertEquals("expense", outgoing!!.type)
    }

    @Test
    fun parse_promotionalAdsWithAmounts_areBlockedAsPromo() {
        val adCases = listOf(
            "You've received a RM20 voucher! Min spend RM50." to "com.shopee.my",
            "Bayar guna DuitNow QR & menang pulangan tunai sehingga RM88! Transaksi minimum RM10." to "my.com.tngdigital.ewallet",
            "Enjoy RM10 instant rebate when you spend RM50 at Watsons!" to "my.com.tngdigital.ewallet",
            "Get up to RM20 cashback on your next purchase!" to "com.grabtaxi.passenger",
            "Fuel up RM50 at Petronas & earn 3x Mesra points!" to "com.setel.mobile",
            "Refuel RM40 with Setel and get RM5!" to "com.setel.mobile",
            "Transfer RM50 via DuitNow Transfer to win iPhone 16!" to "com.maybank2u.life",
            "GrabFood: RM12 OFF your next order! Order now." to "com.grabtaxi.passenger",
            "Cashback of RM15 when you spend RM100 this weekend! Limited time offer." to "com.v2.cimb.malaysia",
            "Special deal! RM9.90 combo offer at McDonald's today!" to "com.grabtaxi.passenger",
            "MAE: Enjoy 3.88% p.a. & earn RM50 bonus interest!" to "com.maybank2u.life",
            "You have received RM10 off voucher for your next GrabFood order!" to "com.grabtaxi.passenger",
            "Stand a chance to win RM500 cashback!" to "com.myboost"
        )

        for ((text, pkg) in adCases) {
            val result = LocalNotificationParser.parse(text, pkg)
            val blocked = result == null || result.isPromo
            org.junit.Assert.assertTrue(
                "Expected ad notification to be blocked, but got $result for: $text",
                blocked
            )
        }
    }

    @Test
    fun parse_realReceiptsWithLoyaltyFooterOrSettledCashback_stillRecordAccurately() {
        // Real payment receipt with points footer must NOT be falsely blocked
        val receiptWithPoints = LocalNotificationParser.parse(
            "Payment of RM 15.00 to ZUS Coffee successful. You earned 15 GoRewards points!",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(receiptWithPoints)
        assertFalse(receiptWithPoints!!.isPromo)
        assertEquals(15.0, receiptWithPoints.amount, 0.01)
        assertEquals("expense", receiptWithPoints.type)

        // Real settled cashback credited to wallet must still record as income
        val settledCashback = LocalNotificationParser.parse(
            "RM 2.50 cashback has been credited to your Touch 'n Go eWallet",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(settledCashback)
        assertFalse(settledCashback!!.isPromo)
        assertEquals(2.50, settledCashback.amount, 0.01)
        assertEquals("income", settledCashback.type)
        assertEquals("返现", settledCashback.category)
    }

    @Test
    fun parse_shopeeNonPaymentNoiseBlocked_whileShopeePayRecorded() {
        val noise1 = LocalNotificationParser.parse("Seller replied: This item is RM 35.00 only!", "com.shopee.my")
        org.junit.Assert.assertTrue(noise1 == null || noise1.isPromo)

        val noise2 = LocalNotificationParser.parse("Your parcel worth RM 49.90 has been delivered!", "com.shopee.my")
        org.junit.Assert.assertTrue(noise2 == null || noise2.isPromo)

        val realPay = LocalNotificationParser.parse("ShopeePay: You have paid RM 49.90 to Uniqlo Official", "com.shopee.my")
        assertNotNull(realPay)
        assertFalse(realPay!!.isPromo)
        assertEquals(49.90, realPay.amount, 0.01)
    }

    @Test
    fun crossAppDeduplicator_suppressesSameAmountWithinWindowAndAllowsDifferentOrLater() {
        val dedup = LocalNotificationParser.CrossAppDeduplicator(windowMs = 180_000L)
        val t0 = 1_700_000_000_000L
        // First notification (e.g. Maybank App push): allowed
        assertFalse(dedup.isDuplicateAndRecord("expense", 45.50, t0))
        // Second notification 60s later (e.g. Bank SMS for same RM 45.50): suppressed
        org.junit.Assert.assertTrue(dedup.isDuplicateAndRecord("expense", 45.50, t0 + 60_000L))
        // Different amount within window: allowed
        assertFalse(dedup.isDuplicateAndRecord("expense", 12.00, t0 + 90_000L))
        // Same amount after 3-minute window expires: allowed
        assertFalse(dedup.isDuplicateAndRecord("expense", 45.50, t0 + 181_000L))
    }
}

