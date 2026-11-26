package com.siangloh.ledger.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.siangloh.ledger.ui.LedgerSharedApp

fun MainViewController() = ComposeUIViewController(
    configure = {
        enforceStrictPlistSanityCheck = false
    }
) {
    LedgerSharedApp()
}
