package com.siangloh.ledger.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.siangloh.ledger.ui.LedgerSharedApp
import platform.Foundation.NSProcessInfo

fun MainViewController() = ComposeUIViewController(
    configure = {
        enforceStrictPlistSanityCheck = false
    }
) {
    val isSmoke = NSProcessInfo.processInfo.arguments.any { it?.toString() == "--uitest-smoke" }
    LedgerSharedApp(runUiSmokeTest = isSmoke)
}
