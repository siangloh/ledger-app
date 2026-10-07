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
        "affin", "bankislam", "alliance", "mms", "sms", "messaging"
    )

    val DEFAULT_PACKAGES = setOf(
        "my.com.tngdigital.ewallet",
        "com.maybank2u.life",
        "com.maybank2u.m2umobile",
        "com.v2.cimb.malaysia",
        "com.cimb.cimbclicks",
        "com.rhbgroup.rhbmobile",
        "my.com.hongleongconnect.mobile",
        "com.ambank.ambankconnect",
        "com.ambank.amonline",
        "my.com.affinonline.retail",
        "my.com.bankislam.bizbridge",
        "com.alliancedirect.mobile",
        "com.publicbank.pbe",
        "com.publicbank.mypb",
        "com.myboost",
        "com.grabtaxi.passenger",
        "com.shopee.my",
        "my.bigpay.app",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )

    // 内存缓存：避免手机每来一条无关通知都执行磁盘 SharedPreferences 读取
    @Volatile
    private var memoryCache: Set<String>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAppMonitored(context: Context, pkgName: String): Boolean {
        val monitored = getMonitoredSet(context)
        return monitored.contains(pkgName)
    }

    fun setAppMonitored(context: Context, pkgName: String, enabled: Boolean) {
        val prefs = getPrefs(context)
        val currentSet = prefs.getStringSet(KEY_ENABLED_PKGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            currentSet.add(pkgName)
        } else {
            currentSet.remove(pkgName)
        }
        val immutableSet = currentSet.toSet()
        memoryCache = immutableSet
        prefs.edit()
            .putStringSet(KEY_ENABLED_PKGS, immutableSet)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    fun getMonitoredSet(context: Context): Set<String> {
        val cached = memoryCache
        if (cached != null) return cached

        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initDefaults(context)
            return memoryCache ?: emptySet()
        }
        val loaded = prefs.getStringSet(KEY_ENABLED_PKGS, emptySet())?.toSet() ?: emptySet()
        memoryCache = loaded
        return loaded
    }

    private fun initDefaults(context: Context) {
        val defaultEnabled = DEFAULT_PACKAGES.toMutableSet()
        try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val pkg = app.packageName.lowercase()
                val matchesKnownBank = KNOWN_BANK_KEYWORDS.any { pkg.contains(it) }
                if (matchesKnownBank) {
                    defaultEnabled.add(app.packageName)
                }
            }
        } catch (_: Exception) {}

        val finalSet = defaultEnabled.toSet()
        memoryCache = finalSet
        getPrefs(context).edit()
            .putStringSet(KEY_ENABLED_PKGS, finalSet)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }
}
