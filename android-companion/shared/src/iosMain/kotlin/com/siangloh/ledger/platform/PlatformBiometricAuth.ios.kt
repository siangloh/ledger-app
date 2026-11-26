package com.siangloh.ledger.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual object PlatformBiometricAuth {
    private var activeContext: LAContext? = null

    @OptIn(ExperimentalForeignApi::class)
    actual fun isAvailable(): Boolean {
        return try {
            LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        } catch (_: Throwable) {
            false
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun authenticate(
        title: String,
        subtitle: String,
        cancelLabel: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        try {
            val context = LAContext()
            if (cancelLabel.isNotBlank()) {
                context.localizedCancelTitle = cancelLabel
            }
            if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) {
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(false, "Biometric authentication is unavailable")
                }
                return
            }
            activeContext = context
            val reason = when {
                subtitle.isNotBlank() && title.isNotBlank() -> "$title — $subtitle"
                title.isNotBlank() -> title
                subtitle.isNotBlank() -> subtitle
                else -> "Authenticate"
            }
            context.evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthentication,
                localizedReason = reason
            ) { success, error ->
                dispatch_async(dispatch_get_main_queue()) {
                    activeContext = null
                    onResult(success, error?.localizedDescription)
                }
            }
        } catch (e: Throwable) {
            activeContext = null
            dispatch_async(dispatch_get_main_queue()) {
                onResult(false, e.message ?: "Authentication failed")
            }
        }
    }
}
