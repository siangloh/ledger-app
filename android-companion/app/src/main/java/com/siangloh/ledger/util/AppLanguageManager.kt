package com.siangloh.ledger.util

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.siangloh.ledger.NetworkHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object AppLanguageManager {
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"
    const val LANG_MS = "ms"

    private const val PREFS_NAME = "ledger_lang_prefs"
    private const val KEY_LANG = "app_language"
    private const val KEY_MIGRATED_TO_PER_APP_API = "migrated_to_per_app_api"

    private fun normalizeLangTag(rawLang: String?): String? {
        val lower = rawLang?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            lower.startsWith("zh") -> LANG_ZH
            lower.startsWith("ms") || lower.startsWith("my") -> LANG_MS
            lower.startsWith("en") -> LANG_EN
            else -> null
        }
    }

    /**
     * 优先读取 AndroidX 官方 Per-App Language API（兼容系统「应用语言」独立设置），
     * 若尚未在系统级指定则回退至本地偏好与系统默认语言。
     */
    fun getLanguage(context: Context): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            normalizeLangTag(appLocales[0]?.language)?.let { return it }
        }
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        normalizeLangTag(sp.getString(KEY_LANG, null))?.let { return it }
        return getDefaultLanguage()
    }

    private fun getDefaultLanguage(): String {
        val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return when {
            sysLang.startsWith("zh") -> LANG_ZH
            sysLang.startsWith("ms") || sysLang.startsWith("my") -> LANG_MS
            else -> LANG_EN
        }
    }

    /**
     * 老版本平滑迁移：若老用户此前在 SharedPreferences 中自定义过语言，
     * 首次启动新版时自动同步至 AndroidX 官方 Per-App Language API。
     */
    fun migrateLegacyLanguageIfNeeded(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_MIGRATED_TO_PER_APP_API, false)) return

        val appLocales = AppCompatDelegate.getApplicationLocales()
        val legacyLang = normalizeLangTag(sp.getString(KEY_LANG, null))
        sp.edit().putBoolean(KEY_MIGRATED_TO_PER_APP_API, true).apply()

        if (appLocales.isEmpty && legacyLang != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(legacyLang))
        }
    }

    fun setLanguage(context: Context, lang: String) {
        val normalized = normalizeLangTag(lang) ?: LANG_EN
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, normalized)
            .putBoolean(KEY_MIGRATED_TO_PER_APP_API, true)
            .apply()

        // Sync with WebView Cookie
        try {
            val serverUrl = NetworkHelper.getServerUrl(context)
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(serverUrl, "lang=$normalized; path=/")
            cookieManager.flush()
        } catch (e: Exception) {
            android.util.Log.w("AppLanguageManager", "Failed to sync language cookie: ${e.message}")
        }

        // 调用 AndroidX 官方 Per-App Language API：系统原生接管语言持久化与 Activity 刷新
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))

        // 同步刷新桌面小部件，使其文案即时跟随自定义语言
        com.siangloh.ledger.widget.LedgerAppWidgetProvider.notifyDataChanged(context)
    }

    fun getLocale(lang: String): Locale {
        return when (lang) {
            LANG_EN -> Locale.ENGLISH
            LANG_MS -> Locale("ms", "MY")
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    fun wrapContext(context: Context, lang: String = getLanguage(context)): Context {
        val locale = getLocale(lang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }

    fun formatMonth(calendar: Calendar, lang: String): String {
        return when (lang) {
            LANG_EN -> SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(calendar.time)
            LANG_MS -> SimpleDateFormat("MMMM yyyy", Locale("ms", "MY")).format(calendar.time)
            else -> SimpleDateFormat("yyyy年M月", Locale.CHINESE).format(calendar.time)
        }
    }

    fun formatWidgetMonth(calendar: Calendar, lang: String): String {
        return when (lang) {
            LANG_EN -> " · " + SimpleDateFormat("MMM", Locale.ENGLISH).format(calendar.time)
            LANG_MS -> " · " + SimpleDateFormat("MMM", Locale("ms", "MY")).format(calendar.time)
            else -> " · ${calendar.get(Calendar.MONTH) + 1}月"
        }
    }

    fun formatWidgetDayExpenseTitle(calendar: Calendar, isToday: Boolean, lang: String): String {
        if (isToday) {
            return when (lang) {
                LANG_EN -> "Today Spend"
                LANG_MS -> "Belanja Hari Ini"
                else -> "今日支出"
            }
        }
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return when (lang) {
            LANG_EN -> "${SimpleDateFormat("MMM", Locale.ENGLISH).format(calendar.time)} $day Spend"
            LANG_MS -> "$day ${SimpleDateFormat("MMM", Locale("ms", "MY")).format(calendar.time)} Belanja"
            else -> "${calendar.get(Calendar.MONTH) + 1}月${day}日 支出"
        }
    }

    fun format3mRange(calendar: Calendar, lang: String): String {
        val startCal = (calendar.clone() as Calendar).apply { add(Calendar.MONTH, -2) }
        return when (lang) {
            LANG_EN -> {
                val start = SimpleDateFormat("MMM", Locale.ENGLISH).format(startCal.time)
                val end = SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(calendar.time)
                "$start – $end (Last 3 months)"
            }
            LANG_MS -> {
                val start = SimpleDateFormat("MMM", Locale("ms", "MY")).format(startCal.time)
                val end = SimpleDateFormat("MMM yyyy", Locale("ms", "MY")).format(calendar.time)
                "$start – $end (3 bulan terkini)"
            }
            else -> {
                val start = SimpleDateFormat("yyyy-MM", Locale.CHINESE).format(startCal.time)
                val end = SimpleDateFormat("yyyy-MM", Locale.CHINESE).format(calendar.time)
                "$start ~ $end (近3个月)"
            }
        }
    }

    fun formatYearRange(calendar: Calendar, lang: String): String {
        val year = calendar.get(Calendar.YEAR)
        return when (lang) {
            LANG_EN -> "Year $year (Full Year)"
            LANG_MS -> "Tahun $year (Sepanjang Tahun)"
            else -> "${year}年 全年统计"
        }
    }
}
