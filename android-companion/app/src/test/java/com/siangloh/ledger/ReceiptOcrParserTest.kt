package com.siangloh.ledger

import com.siangloh.ledger.parser.ReceiptOcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrParserTest {

    @Test
    fun parseLinesToReceiptResult_talliesItemsPlusServiceAndSstWithTotal() {
        val lines = listOf(
            "THAI STREET KITCHEN",
            "Tax Invoice #1024",
            "1x Tom Yum Soup 36.00",
            "Nasi Goreng Kampung 14.00",
            "Iced Milo 4.50",
            "Subtotal 54.50",
            "Service Charge 10% 5.45",
            "SST 6% 3.27",
            "TOTAL RM 63.22",
            "CASH 70.00",
            "CHANGE 6.78"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals("THAI STREET KITCHEN", result.merchant)
        assertEquals(3, result.items.size)
        assertEquals("Tom Yum Soup", result.items[0].name)
        assertEquals(36.00, result.items[0].price, 0.001)
        assertEquals(5.45, result.detectedServiceCharge, 0.001)
        assertEquals(3.27, result.detectedSst, 0.001)
        assertEquals(63.22, result.detectedTotal, 0.001)
        assertEquals(63.22, result.computedTotal, 0.001)
        assertTrue(result.isTallied)
        assertEquals(0.0, result.tallyDiff, 0.001)
    }

    @Test
    fun parseLinesToReceiptResult_detectsMismatchWhenItemIsMissing() {
        val lines = listOf(
            "MAMAK BISTRO",
            "Roti Canai 3.00",
            "Teh Tarik 2.50",
            "TOTAL RM 12.50"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals(2, result.items.size)
        assertEquals(5.50, result.computedTotal, 0.001)
        assertEquals(12.50, result.detectedTotal, 0.001)
        assertFalse(result.isTallied)
        assertEquals(-7.00, result.tallyDiff, 0.001)
    }
}

