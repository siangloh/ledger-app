package com.siangloh.ledger.util

import android.content.Context
import android.os.Build
import android.os.LocaleList
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

    fun getLanguage(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LANG, null) ?: getDefaultLanguage()
    }

    private fun getDefaultLanguage(): String {
        val sysLang = Locale.getDefault().language.lowercase()
        return when {
            sysLang.startsWith("zh") -> LANG_ZH
            sysLang.startsWith("ms") || sysLang.startsWith("my") -> LANG_MS
            else -> LANG_EN
        }
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, lang)
            .apply()

        // Sync with WebView Cookie
        try {
            val serverUrl = NetworkHelper.getServerUrl(context)
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(serverUrl, "lang=$lang; path=/")
            cookieManager.flush()
        } catch (_: Exception) {}
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
