package com.siangloh.ledger.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object PlatformToastBus {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun emit(message: String) {
        _messages.tryEmit(message)
    }
}

expect fun showPlatformToast(message: String)

val LocalPlatformBackHandlerRegistrar =
    staticCompositionLocalOf<(@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)?> {
        null
    }

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

expect object PlatformLocaleBridge {
    fun getCurrentLanguage(): String
    fun setLanguage(lang: String)
}

object LocaleStateHolder {
    const val DEFAULT_LANGUAGE = "zh"

    private val _language = MutableStateFlow(
        normalizeLanguage(
            runCatching { PlatformLocaleBridge.getCurrentLanguage() }
                .getOrDefault(DEFAULT_LANGUAGE)
        )
    )
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(lang: String) {
        val normalized = normalizeLanguage(lang)
        PlatformLocaleBridge.setLanguage(normalized)
        _language.value = normalized
    }

    fun refresh() {
        _language.value = normalizeLanguage(
            runCatching { PlatformLocaleBridge.getCurrentLanguage() }
                .getOrDefault(DEFAULT_LANGUAGE)
        )
    }

    fun normalizeLanguage(lang: String): String {
        val lower = lang.trim().lowercase()
        return when {
            lower.startsWith("zh") -> "zh"
            lower.startsWith("ms") || lower.startsWith("my") -> "ms"
            lower.startsWith("en") -> "en"
            else -> DEFAULT_LANGUAGE
        }
    }
}
