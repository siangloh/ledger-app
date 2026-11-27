package com.siangloh.ledger.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

actual fun readPlatformClipboardText(): String? {
    if (!PlatformContextHolder.isInitialized) return null
    return try {
        val clipboard = PlatformContextHolder.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.coerceToText(PlatformContextHolder.appContext)?.toString()
        } else {
            null
        }
    } catch (e: Throwable) {
        Log.w("PlatformFeedback", "Failed to read clipboard text", e)
        null
    }
}

actual fun openSystemShortcutsApp(): Boolean {
    if (!PlatformContextHolder.isInitialized) return false
    return try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PlatformContextHolder.appContext.startActivity(intent)
        true
    } catch (e: Throwable) {
        Log.w("PlatformFeedback", "Failed to open notification listener settings", e)
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
