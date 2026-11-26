package com.siangloh.ledger.platform

expect object PlatformCapabilities {
    val isIos: Boolean
    val supportsBackgroundNotificationListener: Boolean
    val supportsHomeWidget: Boolean
}
