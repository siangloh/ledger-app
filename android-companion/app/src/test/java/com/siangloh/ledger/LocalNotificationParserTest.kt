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
}

