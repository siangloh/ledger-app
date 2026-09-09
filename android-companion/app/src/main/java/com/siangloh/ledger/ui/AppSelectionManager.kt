package com.siangloh.ledger.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppSelectionManager {
    private const val PREFS_NAME = "monitored_apps_prefs"
    private const val KEY_ENABLED_PKGS = "enabled_packages"
    private const val KEY_INITIALIZED = "is_initialized"

    val KNOWN_BANK_KEYWORDS = listOf(
        "tng", "touch", "ewallet", "publicbank", "mypb", "maybank",
        "cimb", "rhb", "hongleong", "ambank", "boost", "grabpay",
        "shopeepay", "bigpay", "merchantrade", "hsbc", "ocbc", "uob",
        "affin", "bankislam", "alliance"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAppMonitored(context: Context, pkgName: String): Boolean {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initDefaults(context)
        }
        val enabledSet = prefs.getStringSet(KEY_ENABLED_PKGS, emptySet()) ?: emptySet()
        return enabledSet.contains(pkgName)
    }

    fun setAppMonitored(context: Context, pkgName: String, enabled: Boolean) {
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ENABLED_PKGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            currentSet.add(pkgName)
        } else {
            currentSet.remove(pkgName)
        }
        prefs.edit()
            .putStringSet(KEY_ENABLED_PKGS, currentSet)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    fun getMonitoredSet(context: Context): Set<String> {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initDefaults(context)
        }
        return prefs.getStringSet(KEY_ENABLED_PKGS, emptySet()) ?: emptySet()
    }

    private fun initDefaults(context: Context) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val defaultEnabled = mutableSetOf<String>()

        for (app in installedApps) {
            val pkg = app.packageName.lowercase()
            val matchesKnownBank = KNOWN_BANK_KEYWORDS.any { pkg.contains(it) }
            if (matchesKnownBank) {
                defaultEnabled.add(app.packageName)
            }
        }

        getPrefs(context).edit()
            .putStringSet(KEY_ENABLED_PKGS, defaultEnabled)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }
}
