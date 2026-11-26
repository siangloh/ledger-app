package com.siangloh.ledger.core.prefs

import platform.Foundation.NSUserDefaults

actual object PlatformContextHolder {
    actual val isInitialized: Boolean = true
    actual fun init(context: Any?) {}
}

actual class PlatformPrefs actual constructor(name: String) {
    private val suiteName = "com.siangloh.ledger.$name"
    private val customDefaults: NSUserDefaults? = NSUserDefaults(suiteName = suiteName)
    private val defaults: NSUserDefaults = customDefaults ?: NSUserDefaults.standardUserDefaults

    actual fun getString(key: String, defaultValue: String?): String? {
        return defaults.stringForKey(key) ?: defaultValue
    }

    actual fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return if (defaults.objectForKey(key) == null) defaultValue else defaults.boolForKey(key)
    }

    actual fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        return if (defaults.objectForKey(key) == null) defaultValue else defaults.integerForKey(key).toInt()
    }

    actual fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        return if (defaults.objectForKey(key) == null) defaultValue else defaults.integerForKey(key)
    }

    actual fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    actual fun clear() {
        if (customDefaults != null) {
            customDefaults.removePersistentDomainForName(suiteName)
        }
    }
}
