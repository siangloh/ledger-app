package com.siangloh.ledger.platform

actual object PlatformBiometricAuth {
    var androidAuthHandler: ((String, String, String, (Boolean, String?) -> Unit) -> Unit)? = null
    var androidAvailabilityCheck: (() -> Boolean)? = null

    actual fun isAvailable(): Boolean {
        return androidAvailabilityCheck?.invoke() ?: false
    }

    actual fun authenticate(
        title: String,
        subtitle: String,
        cancelLabel: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val handler = androidAuthHandler
        if (handler != null) {
            handler(title, subtitle, cancelLabel, onResult)
        } else {
            onResult(false, "Biometric authentication is not configured")
        }
    }
}
