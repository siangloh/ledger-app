package com.siangloh.ledger.data

import android.content.Context
import android.util.Log

/**
 * 本地数据持久化存储中心 (Local Data Store & Cache Manager)
 * 遵循离线优先原则，将关键账务数据（概览、流水、账户、工具）持久化于本地，
 * 保证无网络或冷启动时秒开展示，并在网络连通时双向同步。
 */
object LocalDataStore {
    private const val TAG = "LocalDataStore"

    private const val PREF_OVERVIEW = "ledger_overview_cache"
    private const val PREF_RECORDS = "ledger_records_cache"
    private const val PREF_ACCOUNTS = "ledger_accounts_cache"
    private const val PREF_TOOLS = "ledger_tools_cache"

    // -------------------------------------------------------------------------
    // 1. 概览数据持久化 (Overview Cache)
    // -------------------------------------------------------------------------
    fun saveOverview(context: Context, username: String? = null, period: String? = null, month: String? = null, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_OVERVIEW, Context.MODE_PRIVATE)
            val key = "overview_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            val legacyKey = "cache_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            sp.edit().putString(key, json).putString(legacyKey, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save overview cache: ${e.message}")
        }
    }

    fun getOverview(context: Context, username: String? = null, period: String? = null, month: String? = null): String? {
        return try {
            val sp = context.getSharedPreferences(PREF_OVERVIEW, Context.MODE_PRIVATE)
            val key = "overview_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            val legacyKey = "cache_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            sp.getString(key, null) ?: sp.getString(legacyKey, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get overview cache: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // 2. 流水明细持久化 (Records Cache)
    // -------------------------------------------------------------------------
    fun saveRecords(context: Context, month: String? = null, type: String? = null, search: String? = null, page: Int = 1, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_RECORDS, Context.MODE_PRIVATE)
            val key = "records_${month.orEmpty()}_${type.orEmpty()}_${search.orEmpty().trim()}_p${page}"
            sp.edit().putString(key, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save records cache: ${e.message}")
        }
    }

    fun getRecords(context: Context, month: String? = null, type: String? = null, search: String? = null, page: Int = 1): String? {
        return try {
            val sp = context.getSharedPreferences(PREF_RECORDS, Context.MODE_PRIVATE)
            val key = "records_${month.orEmpty()}_${type.orEmpty()}_${search.orEmpty().trim()}_p${page}"
            sp.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get records cache: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // 3. 资产与账户持久化 (Accounts Cache)
    // -------------------------------------------------------------------------
    fun saveAccounts(context: Context, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
            sp.edit().putString("accounts_summary_latest", json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save accounts cache: ${e.message}")
        }
    }

    fun getAccounts(context: Context): String? {
        return try {
            val sp = context.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
            sp.getString("accounts_summary_latest", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get accounts cache: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // 4. 工具与订阅持久化 (Tools Cache)
    // -------------------------------------------------------------------------
    fun saveTools(context: Context, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_TOOLS, Context.MODE_PRIVATE)
            sp.edit().putString("tools_summary_latest", json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tools cache: ${e.message}")
        }
    }

    fun getTools(context: Context): String? {
        return try {
            val sp = context.getSharedPreferences(PREF_TOOLS, Context.MODE_PRIVATE)
            sp.getString("tools_summary_latest", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get tools cache: ${e.message}")
            null
        }
    }
}
