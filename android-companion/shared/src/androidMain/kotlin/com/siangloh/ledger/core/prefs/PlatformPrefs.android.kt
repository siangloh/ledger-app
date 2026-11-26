package com.siangloh.ledger.core.prefs

import android.content.Context
import android.content.SharedPreferences

actual object PlatformContextHolder {
    lateinit var appContext: Context

    actual val isInitialized: Boolean
        get() = ::appContext.isInitialized

    actual fun init(context: Any?) {
        val ctx = context as? Context
            ?: throw IllegalArgumentException("PlatformContextHolder.init on Android requires an android.content.Context")
        appContext = ctx.applicationContext
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

actual class PlatformPrefs actual constructor(private val name: String) {
    private val prefs: SharedPreferences by lazy {
        check(PlatformContextHolder.isInitialized) {
            "PlatformContextHolder must be initialized before using PlatformPrefs"
        }
        PlatformContextHolder.appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    actual fun getString(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    actual fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    actual fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    actual fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    actual fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    actual fun clear() {
        prefs.edit().clear().apply()
    }
}
