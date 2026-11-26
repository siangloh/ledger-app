package com.siangloh.ledger.core.prefs

import platform.Foundation.NSUserDefaults

actual object PlatformContextHolder {
    actual val isInitialized: Boolean = true
    actual fun init(context: Any?) {}
}

actual class PlatformPrefs actual constructor(name: String) {
    private val prefix = "pref_${name}_"
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    private fun scopedKey(key: String): String = "$prefix$key"

    actual fun getString(key: String, defaultValue: String?): String? {
        return defaults.stringForKey(scopedKey(key)) ?: defaultValue
    }

    actual fun putString(key: String, value: String?) {
        val fullKey = scopedKey(key)
        if (value == null) {
            defaults.removeObjectForKey(fullKey)
        } else {
            defaults.setObject(value, forKey = fullKey)
        }
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val fullKey = scopedKey(key)
        return if (defaults.objectForKey(fullKey) == null) defaultValue else defaults.boolForKey(fullKey)
    }

    actual fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = scopedKey(key))
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        val fullKey = scopedKey(key)
        return if (defaults.objectForKey(fullKey) == null) defaultValue else defaults.integerForKey(fullKey).toInt()
    }

    actual fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = scopedKey(key))
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        val fullKey = scopedKey(key)
        return if (defaults.objectForKey(fullKey) == null) defaultValue else defaults.integerForKey(fullKey)
    }

    actual fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = scopedKey(key))
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(scopedKey(key))
    }

    actual fun clear() {
        val keysToRemove = defaults.dictionaryRepresentation().keys
            .mapNotNull { it?.toString() }
            .filter { it.startsWith(prefix) }
        for (fullKey in keysToRemove) {
            defaults.removeObjectForKey(fullKey)
        }
    }
}
