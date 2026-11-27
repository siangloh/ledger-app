package com.siangloh.ledger.shared

import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.ui.platform.AccessibilitySyncOptions
import androidx.compose.ui.window.ComposeUIViewController
import com.siangloh.ledger.ui.LedgerSharedApp
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalComposeApi::class)
fun MainViewController() = ComposeUIViewController(
    configure = {
        enforceStrictPlistSanityCheck = false
        accessibilitySyncOptions = AccessibilitySyncOptions.Always(debugLogger = null)
    }
) {
    val isSmoke = NSProcessInfo.processInfo.arguments.any { it?.toString() == "--uitest-smoke" }
    LedgerSharedApp(runUiSmokeTest = isSmoke)
}
