package com.siangloh.ledger.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.siangloh.ledger.core.prefs.PlatformContextHolder
import com.siangloh.ledger.core.prefs.PlatformPrefs
import java.io.File

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

actual fun sharePlatformTextFile(fileName: String, mimeType: String, content: String): Boolean {
    if (!PlatformContextHolder.isInitialized) {
        return false
    }
    return try {
        val outFile = File(PlatformContextHolder.appContext.cacheDir, fileName)
        outFile.writeText(content, Charsets.UTF_8)
        true
    } catch (e: Throwable) {
        Log.w("PlatformFeedback", "Failed to write exported file $fileName", e)
        false
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
