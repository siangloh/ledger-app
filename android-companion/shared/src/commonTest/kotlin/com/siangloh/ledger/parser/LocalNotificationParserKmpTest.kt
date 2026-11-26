package com.siangloh.ledger.parser

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalNotificationParserKmpTest {

    @Test
    fun parse_petrolAndBankPayments_extractsMerchantChannelAndCategory() {
        val tng = LocalNotificationParser.parse(
            "Payment of RM50.00 to PETRONAS DAGANGAN BHD successful",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(tng)
        assertEquals(50.0, tng.amount, 0.001)
        assertEquals("expense", tng.type)
        assertEquals("交通", tng.category)
        assertEquals("Touch 'n Go", tng.channel)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(tng.date))

        val maybank = LocalNotificationParser.parse(
            "MAE: RM 28.90 paid to ZUS COFFEE KLCC",
            "com.maybank2u.life"
        )
        assertNotNull(maybank)
        assertEquals(28.90, maybank.amount, 0.001)
        assertEquals("餐饮", maybank.category)
        assertEquals("Maybank", maybank.channel)

        val pbb = LocalNotificationParser.parse(
            "RM120.00 charged using your card ending 1234 @ JAYA GROCER RM120.00",
            "com.publicbank.mypb"
        )
        assertNotNull(pbb)
        assertEquals(120.0, pbb.amount, 0.001)
        assertEquals("杂货", pbb.category)
        assertEquals("Public Bank", pbb.channel)

        val cimb = LocalNotificationParser.parse(
            "Bayaran RM 35.00 berjaya di BHPetrol Kepong",
            "com.v2.cimb.malaysia"
        )
        assertNotNull(cimb)
        assertEquals(35.0, cimb.amount, 0.001)
        assertEquals("交通", cimb.category)
        assertEquals("CIMB", cimb.channel)
    }

    @Test
    fun parse_peerTransfersAndInternalReloads_classifyProperly() {
        val incomingNoSender = LocalNotificationParser.parse(
            "You have received RM 88.00",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(incomingNoSender)
        assertEquals("income", incomingNoSender.type)
        assertEquals("Touch 'n Go 入账", incomingNoSender.merchant)

        val incomingDefaultSender = LocalNotificationParser.parse(
            "You have received RM 88.00 !",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(incomingDefaultSender)
        assertEquals("income", incomingDefaultSender.type)
        assertEquals("朋友转账", incomingDefaultSender.merchant)

        val incomingNamed = LocalNotificationParser.parse(
            "You have received RM 66.66 via DuitNow transfer from NG TIAN YONG",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(incomingNamed)
        assertEquals("income", incomingNamed.type)
        assertEquals(66.66, incomingNamed.amount, 0.001)

        val reload = LocalNotificationParser.parse(
            "Reload successful! RM 100.00 into your GO+ account.",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(reload)
        assertTrue(reload.isInternalTransfer)
    }

    @Test
    fun parse_adsAndShopeeNoiseBlocked_whileLoyaltyReceiptsAndSettledRebateAllowed() {
        val ad = LocalNotificationParser.parse(
            "Enjoy RM10 instant rebate when you spend RM50 at Watsons!",
            "my.com.tngdigital.ewallet"
        )
        assertTrue(ad == null || ad.isPromo)

        val shopeeChat = LocalNotificationParser.parse(
            "Seller replied: RM 25.00 ready stock!",
            "com.shopee.my"
        )
        assertTrue(shopeeChat == null || shopeeChat.isPromo)

        val realShopeePay = LocalNotificationParser.parse(
            "ShopeePay: You have paid RM 49.90 to Uniqlo Official",
            "com.shopee.my"
        )
        assertNotNull(realShopeePay)
        assertFalse(realShopeePay.isPromo)
        assertEquals("购物", realShopeePay.category)

        val loyaltyReceipt = LocalNotificationParser.parse(
            "Payment of RM 15.00 to ZUS Coffee successful. You earned 15 GoRewards points!",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(loyaltyReceipt)
        assertFalse(loyaltyReceipt.isPromo)
        assertEquals(15.0, loyaltyReceipt.amount, 0.001)

        val settledCashback = LocalNotificationParser.parse(
            "RM 2.50 cashback has been credited to your Touch 'n Go eWallet",
            "my.com.tngdigital.ewallet"
        )
        assertNotNull(settledCashback)
        assertEquals("income", settledCashback.type)
        assertEquals("返现", settledCashback.category)
    }

    @Test
    fun crossAppDeduplicator_blocksSameCentsInWindow() {
        val dedup = LocalNotificationParser.CrossAppDeduplicator(windowMs = 180_000L)
        val t0 = 1_700_000_000_000L
        assertFalse(dedup.isDuplicateAndRecord("expense", 45.50, t0))
        assertTrue(dedup.isDuplicateAndRecord("expense", 45.50, t0 + 30_000L))
        assertFalse(dedup.isDuplicateAndRecord("income", 45.50, t0 + 30_000L))
        assertFalse(dedup.isDuplicateAndRecord("expense", 45.50, t0 + 185_000L))
    }

    @Test
    fun receiptTextParser_parsesItemsTaxesDiscountsAndChoosesBetterResult() {
        val fullLines = listOf(
            "ORIENTAL KOPI PAVILION",
            "Date: 26/11/2026 12:15",
            "2 x Signature Egg Tart 9.80",
            "Nasi Lemak Ayam Berempah 18,90",
            "Kopi Butter 5.50",
            "Sub-Total 34.20",
            "Voucher Discount (4.20)",
            "Service Charge 10% 3.00",
            "SST 6% 1.80",
            "GRAND TOTAL RM 34.80",
            "DuitNow QR 34.80"
        )
        val tallied = ReceiptTextParser.parseLinesToReceiptResult(fullLines)
        assertEquals("ORIENTAL KOPI PAVILION", tallied.merchant)
        assertEquals(3, tallied.items.size)
        assertEquals("2x Signature Egg Tart", tallied.items[0].name)
        assertTrue(abs(tallied.detectedServiceCharge - 3.00) < 0.001)
        assertTrue(abs(tallied.detectedSst - 1.80) < 0.001)
        assertTrue(abs(tallied.detectedAdjustment - (-4.20)) < 0.001)
        assertTrue(abs(tallied.detectedTotal - 34.80) < 0.001)
        assertTrue(tallied.isTallied)

        val partial = ReceiptTextParser.parseLinesToReceiptResult(
            listOf("ORIENTAL KOPI PAVILION", "Kopi Butter 5.50", "GRAND TOTAL RM 34.80")
        )
        assertFalse(partial.isTallied)
        val chosen = ReceiptTextParser.chooseBetterParse(partial, tallied)
        assertTrue(chosen.isTallied)
        assertEquals(3, chosen.items.size)
    }
}
