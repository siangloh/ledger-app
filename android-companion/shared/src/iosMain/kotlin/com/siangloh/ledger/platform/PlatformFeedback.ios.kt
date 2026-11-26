package com.siangloh.ledger.platform

import androidx.compose.runtime.Composable
import com.siangloh.ledger.core.prefs.PlatformPrefs
import platform.Foundation.NSLocale
import platform.Foundation.NSLog
import platform.Foundation.preferredLanguages

actual fun showPlatformToast(message: String) {
    NSLog("LedgerToast: %@", message)
    PlatformToastBus.emit(message)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val registrar = LocalPlatformBackHandlerRegistrar.current
    registrar?.invoke(enabled, onBack)
}

actual object PlatformLocaleBridge {
    private const val PREFS_NAME = "ledger_lang_prefs"
    private const val KEY_LANG = "app_lang"

    private val prefs by lazy { PlatformPrefs(PREFS_NAME) }

    actual fun getCurrentLanguage(): String {
        val saved = prefs.getString(KEY_LANG, null)
        if (!saved.isNullOrBlank()) {
            return LocaleStateHolder.normalizeLanguage(saved)
        }
        val systemPreferred = NSLocale.preferredLanguages.firstOrNull()?.toString()
        return if (!systemPreferred.isNullOrBlank()) {
            LocaleStateHolder.normalizeLanguage(systemPreferred)
        } else {
            LocaleStateHolder.DEFAULT_LANGUAGE
        }
    }

    actual fun setLanguage(lang: String) {
        val normalized = LocaleStateHolder.normalizeLanguage(lang)
        prefs.putString(KEY_LANG, normalized)
    }
}
