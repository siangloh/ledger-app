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

    @Test
    fun parseLinesToReceiptResult_handlesSubTotalRoundingDiscountAndQtyUnitPrices() {
        val lines = listOf(
            "KOPITIAM AH HOCK",
            "Date: 19/11/2026 14:30",
            "2 x Hainan Chicken Rice 12.00 24.00",
            "Kopi Ice 3,50",
            "Half Boiled Egg 2.80",
            "Sub-Total 30.30",
            "Member Discount -3.00",
            "Service Charge 10% 3.03",
            "SST 6% 1.82",
            "Rounding Adj -0.05",
            "GRAND TOTAL RM 32.10",
            "DuitNow QR 32.10"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals("KOPITIAM AH HOCK", result.merchant)
        assertEquals(3, result.items.size)
        assertEquals("2x Hainan Chicken Rice", result.items[0].name)
        assertEquals(24.00, result.items[0].price, 0.001)
        assertEquals(3.50, result.items[1].price, 0.001)
        assertEquals(2.80, result.items[2].price, 0.001)
        assertEquals(30.30, result.detectedSubtotal, 0.001)
        assertEquals(3.03, result.detectedServiceCharge, 0.001)
        assertEquals(1.82, result.detectedSst, 0.001)
        assertEquals(-3.05, result.detectedAdjustment, 0.001)
        assertEquals(32.10, result.detectedTotal, 0.001)
        assertEquals(32.10, result.computedTotal, 0.001)
        assertTrue(result.isTallied)
        assertEquals(0.0, result.tallyDiff, 0.001)
    }

    @Test
    fun parseLinesToReceiptResult_doesNotDoubleCountInclusiveSst() {
        val lines = listOf(
            "CAFE BISTRO",
            "Flat White 13.00",
            "Croissant 9.00",
            "Sub Total 22.00",
            "Incl. 6% SST 1.25",
            "Total RM 22.00"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals(2, result.items.size)
        assertEquals(0.0, result.detectedSst, 0.001)
        assertEquals(22.00, result.computedTotal, 0.001)
        assertTrue(result.isTallied)
    }

    @Test
    fun parseLinesToReceiptResult_handlesWrappedItemsTotalDiscountTotalTaxAndThousandCommas() {
        val lines = listOf(
            "HAIDILAO HOTPOT PAVILION",
            "Tax Invoice #HDL-99201",
            "Dine In",
            "Signature Tomato Soup Pot",
            "2 x 38.00 76.00",
            "+ Sliced Wagyu Beef 188.00",
            "+ Fresh Prawn Paste 36.00",
            "+ Condiments Free 0.00",
            "Wagyu Platter Deluxe 1,000.00",
            "Sub-Total 1,300.00",
            "Total Discount (50.00)",
            "Total Service Charge 10% 130.00",
            "Total Tax 6% SST 78.00",
            "Rounding Adj 0.02",
            "GRAND TOTAL RM 1,458.02",
            "Member Points 1,458.00",
            "VISA **** 8842 1,458.02",
            "Thank You!"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals("HAIDILAO HOTPOT PAVILION", result.merchant)
        assertEquals(4, result.items.size)
        assertEquals("2x Signature Tomato Soup Pot", result.items[0].name)
        assertEquals(76.00, result.items[0].price, 0.001)
        assertEquals(1000.00, result.items[3].price, 0.001)
        assertEquals(130.00, result.detectedServiceCharge, 0.001)
        assertEquals(78.00, result.detectedSst, 0.001)
        assertEquals(-49.98, result.detectedAdjustment, 0.001)
        assertEquals(1458.02, result.detectedTotal, 0.001)
        assertEquals(1458.02, result.computedTotal, 0.001)
        assertTrue(result.isTallied)
    }

    @Test
    fun parseLinesToReceiptResult_handlesSplitPriceRowsTaxTagsAndBrokenDecimalDots() {
        val lines = listOf(
            "BANANA LEAF CURRY HOUSE",
            "Date: 19/11/2026",
            "Banana Leaf Set",
            "RM 18. 50 (ST)",
            "Ayam Goreng Berempah 14.50 T1",
            "Teh Tarik Kurang Manis 4 .50 SR",
            "SST 6%",
            "2.25",
            "GRAND TOTAL",
            "RM 39.75"
        )
        val result = ReceiptOcrParser.parseLinesToReceiptResult(lines)
        assertEquals(3, result.items.size)
        assertEquals("Banana Leaf Set", result.items[0].name)
        assertEquals(18.50, result.items[0].price, 0.001)
        assertEquals(14.50, result.items[1].price, 0.001)
        assertEquals(4.50, result.items[2].price, 0.001)
        assertEquals(2.25, result.detectedSst, 0.001)
        assertEquals(39.75, result.detectedTotal, 0.001)
        assertTrue(result.isTallied)
    }
}

