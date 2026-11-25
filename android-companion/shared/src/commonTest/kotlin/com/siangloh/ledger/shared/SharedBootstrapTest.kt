package com.siangloh.ledger.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedBootstrapTest {
    @Test
    fun sharedHealthCheck_includesPlatformName() {
        val status = sharedHealthCheck()
        assertTrue(status.startsWith("Ledger Shared KMP Ready ("))
        assertTrue(status.endsWith(")"))
    }
}
