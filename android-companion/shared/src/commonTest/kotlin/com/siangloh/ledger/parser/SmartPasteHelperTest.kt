package com.siangloh.ledger.parser

import com.siangloh.ledger.data.entities.LocalAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmartPasteHelperTest {

    @Test
    fun parsesTouchNGoPaymentMessage() {
        val result = SmartPasteHelper.parseClipboardTransaction(
            "Touch 'n Go eWallet: You have paid RM 18.50 to ZUS Coffee."
        )
        assertNotNull(result)
        assertEquals(18.50, result.amount, 0.001)
        assertEquals("expense", result.type)
        assertEquals("餐饮", result.category)
        assertEquals("Touch 'n Go", result.channel)
    }

    @Test
    fun parsesMaybankIncomeMessage() {
        val result = SmartPasteHelper.parseClipboardTransaction(
            "Maybank2u: You have received RM 250.00 from JOHN DOE via DuitNow."
        )
        assertNotNull(result)
        assertEquals(250.00, result.amount, 0.001)
        assertEquals("income", result.type)
        assertEquals("Maybank", result.channel)
    }

    @Test
    fun ignoresPromotionalAndOtpMessages() {
        assertNull(SmartPasteHelper.parseClipboardTransaction("Get up to RM 88 cashback when you spend RM 50!"))
        assertNull(SmartPasteHelper.parseClipboardTransaction("Your Maybank TAC OTP code is 123456 for RM 100.00"))
        assertNull(SmartPasteHelper.parseClipboardTransaction(""))
        assertNull(SmartPasteHelper.parseClipboardTransaction(null))
    }

    @Test
    fun matchesAccountIdByChannelName() {
        val accounts = listOf(
            LocalAccount(id = 1, name = "现金", type = "cash", balance = 100.0),
            LocalAccount(id = 2, name = "TNG eWallet", type = "ewallet", balance = 50.0),
            LocalAccount(id = 3, name = "Maybank MAE", type = "bank", balance = 1000.0)
        )
        assertEquals(2, SmartPasteHelper.matchAccountIdForChannel("Touch 'n Go", accounts))
        assertEquals(3, SmartPasteHelper.matchAccountIdForChannel("Maybank", accounts))
        assertNull(SmartPasteHelper.matchAccountIdForChannel("银行/卡", accounts))
    }
}
