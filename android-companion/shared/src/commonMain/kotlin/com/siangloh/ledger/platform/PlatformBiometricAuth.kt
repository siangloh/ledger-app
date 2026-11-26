package com.siangloh.ledger.platform

expect object PlatformBiometricAuth {
    fun isAvailable(): Boolean
    fun authenticate(
        title: String,
        subtitle: String,
        cancelLabel: String,
        onResult: (Boolean, String?) -> Unit
    )
}
