package com.siangloh.ledger

import com.siangloh.ledger.data.LocalLedgerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RebateOffsetUnitTest {

    @Test
    fun computeRebateOffset_partialDeduction_preservesPrecisionAndAppendsTag() {
        val (newAmount, newNote) = LocalLedgerRepository.computeRebateOffset(
            expenseAmount = 102.90,
            offsetAmount = 66.66,
            existingNote = "Dinner with friends",
            tag = "[Rebate offset RM 66.66]"
        )
        assertEquals(36.24, newAmount, 0.001)
        assertEquals("Dinner with friends [Rebate offset RM 66.66]", newNote)
    }

    @Test
    fun computeRebateOffset_overDeduction_clampsToZeroAndHandlesBlankNote() {
        val (newAmount, newNote) = LocalLedgerRepository.computeRebateOffset(
            expenseAmount = 15.80,
            offsetAmount = 50.00,
            existingNote = "   ",
            tag = "[已抵扣 RM 50.00]"
        )
        assertEquals(0.0, newAmount, 0.001)
        assertEquals("[已抵扣 RM 50.00]", newNote)
    }
}

