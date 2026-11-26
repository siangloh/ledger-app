package com.siangloh.ledger.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlatformBridgesTest {

    @Test
    fun platformCapabilitiesAreConsistent() {
        assertNotEquals(
            PlatformCapabilities.isIos,
            PlatformCapabilities.supportsBackgroundNotificationListener,
            "Background notification listener is Android-only"
        )
        assertNotEquals(
            PlatformCapabilities.isIos,
            PlatformCapabilities.supportsHomeWidget,
            "Home widget capability mirrors Android vs iOS target"
        )
    }

    @Test
    fun localeStateHolderNormalizesAndUpdatesStateFlow() {
        val original = LocaleStateHolder.language.value
        try {
            assertEquals("zh", LocaleStateHolder.normalizeLanguage("zh-Hans"))
            assertEquals("en", LocaleStateHolder.normalizeLanguage("en-US"))
            assertEquals("ms", LocaleStateHolder.normalizeLanguage("ms-MY"))
            assertEquals(LocaleStateHolder.DEFAULT_LANGUAGE, LocaleStateHolder.normalizeLanguage("fr-FR"))

            LocaleStateHolder.setLanguage("en-US")
            assertEquals("en", LocaleStateHolder.language.value)
            assertEquals("en", PlatformLocaleBridge.getCurrentLanguage())

            LocaleStateHolder.setLanguage("ms")
            assertEquals("ms", LocaleStateHolder.language.value)
            assertEquals("ms", PlatformLocaleBridge.getCurrentLanguage())

            LocaleStateHolder.setLanguage("zh-CN")
            assertEquals("zh", LocaleStateHolder.language.value)
            assertEquals("zh", PlatformLocaleBridge.getCurrentLanguage())

            LocaleStateHolder.refresh()
            assertEquals("zh", LocaleStateHolder.language.value)
        } finally {
            LocaleStateHolder.setLanguage(original)
        }
    }
}
