package com.siangloh.ledger.platform

actual object PlatformCapabilities {
    actual val isIos: Boolean = false
    actual val supportsBackgroundNotificationListener: Boolean = true
    actual val supportsHomeWidget: Boolean = true
}
