package com.siangloh.ledger

import com.siangloh.ledger.ui.compose.ReceiptJobState
import com.siangloh.ledger.ui.compose.SplitBillLogic
import com.siangloh.ledger.ui.compose.SplitItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitBillLogicTest {

    @Test
    fun splitMultiQuantityItem_splitsOddCentsExactlyToOriginalTotal() {
        val item = SplitItemState(
            id = "i1",
            name = "3x Teh Ais",
            price = 10.00,
            assignedMembers = setOf("Me", "Alex", "Ben")
        )
        val split = SplitBillLogic.splitMultiQuantityItem(item)
        assertEquals(3, split.size)
        assertEquals("Teh Ais (#1)", split[0].name)
        assertEquals(3.34, split[0].price, 0.001)
        assertEquals(3.33, split[1].price, 0.001)
        assertEquals(3.33, split[2].price, 0.001)
        assertEquals(10.00, split.sumOf { it.price }, 0.001)
    }

    @Test
    fun extractQuantityPrefix_ignoresSingleOrNonQuantityItems() {
        assertNull(SplitBillLogic.extractQuantityPrefix("Nasi Lemak"))
        assertNull(SplitBillLogic.extractQuantityPrefix("1x Kaya Toast"))
        assertEquals(4 to "Roti Canai", SplitBillLogic.extractQuantityPrefix("4x Roti Canai"))
    }

    @Test
    fun computeMemberTotals_appliesServiceChargeSstAndRoundingProportionally() {
        val job = ReceiptJobState(
            id = "j1",
            uri = null,
            isAnalyzing = false,
            merchant = "Kopitiam",
            items = listOf(
                SplitItemState("a", "Chicken Rice", 20.00, setOf("Me", "Alex")),
                SplitItemState("b", "Kopi Ice", 10.00, setOf("Me"))
            ),
            serviceCharge = 3.00,
            sst = 1.80,
            adjustment = -0.30,
            detectedTotal = 34.50
        )
        // Extra = 3.00 + 1.80 - 0.30 = 4.50 over RM 30.00 items -> +15% multiplier
        // Me subtotal = 10.00 + 10.00 = 20.00 -> 20.00 * 1.15 = 23.00
        // Alex subtotal = 10.00 -> 10.00 * 1.15 = 11.50
        val totals = SplitBillLogic.computeMemberTotals(listOf(job), listOf("Me", "Alex"))
        assertEquals(23.00, totals["Me"] ?: 0.0, 0.01)
        assertEquals(11.50, totals["Alex"] ?: 0.0, 0.01)
        assertEquals(34.50, (totals["Me"] ?: 0.0) + (totals["Alex"] ?: 0.0), 0.01)
    }
}
