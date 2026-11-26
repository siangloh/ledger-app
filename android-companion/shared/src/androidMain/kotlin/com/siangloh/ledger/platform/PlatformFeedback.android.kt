package com.siangloh.ledger.platform

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.siangloh.ledger.core.prefs.PlatformContextHolder
import com.siangloh.ledger.core.prefs.PlatformPrefs

actual fun showPlatformToast(message: String) {
    if (!PlatformContextHolder.isInitialized) {
        PlatformToastBus.emit(message)
        return
    }
    val appContext = PlatformContextHolder.appContext
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }
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
    private var inMemoryFallback: String = LocaleStateHolder.DEFAULT_LANGUAGE

    var androidLocaleChangeListener: ((String) -> Unit)? = null

    actual fun getCurrentLanguage(): String {
        if (!PlatformContextHolder.isInitialized) {
            return inMemoryFallback
        }
        return prefs.getString(KEY_LANG, inMemoryFallback) ?: inMemoryFallback
    }

    actual fun setLanguage(lang: String) {
        val normalized = LocaleStateHolder.normalizeLanguage(lang)
        inMemoryFallback = normalized
        if (PlatformContextHolder.isInitialized) {
            prefs.putString(KEY_LANG, normalized)
        }
        androidLocaleChangeListener?.invoke(normalized)
    }
}
