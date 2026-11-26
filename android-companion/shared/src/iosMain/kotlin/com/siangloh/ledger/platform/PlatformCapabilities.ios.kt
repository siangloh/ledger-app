package com.siangloh.ledger.platform

actual object PlatformCapabilities {
    actual val isIos: Boolean = true
    actual val supportsBackgroundNotificationListener: Boolean = false
    actual val supportsHomeWidget: Boolean = false
}
