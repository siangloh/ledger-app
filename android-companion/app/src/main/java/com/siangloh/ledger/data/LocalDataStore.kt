package com.siangloh.ledger.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * 本地数据持久化存储中心 (Local Data Store & Cache Manager)
 * 遵循离线优先原则，将关键账务数据（概览、流水、账户、工具）持久化于本地，
 * 进 App 时一次性全量预载，后续切换月份与筛选 100% 消费本地数据（0 网络请求、0ms 瞬切），
 * 仅每隔几分钟在后台定期与服务端同步最新数据。
 */
object LocalDataStore {
    private const val TAG = "LocalDataStore"

    private const val PREF_OVERVIEW = "ledger_overview_cache"
    private const val PREF_RECORDS = "ledger_records_cache"
    private const val PREF_ACCOUNTS = "ledger_accounts_cache"
    private const val PREF_TOOLS = "ledger_tools_cache"
    private const val PREF_SYNC = "ledger_sync_prefs"

    // 默认数据新鲜度有效期：5 分钟（5 分钟内切月翻页纯离线执行，零网络调用）
    const val DEFAULT_FRESHNESS_TTL_MS = 5 * 60 * 1000L

    fun shiftMonth(monthStr: String, delta: Int): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            val cal = Calendar.getInstance().apply {
                time = sdf.parse(monthStr) ?: Date()
                add(Calendar.MONTH, delta)
            }
            SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)
        } catch (_: Exception) {
            monthStr
        }
    }

    // -------------------------------------------------------------------------
    // 1. 概览数据持久化与新鲜度 (Overview Cache)
    // -------------------------------------------------------------------------
    fun saveOverview(context: Context, username: String? = null, period: String? = null, month: String? = null, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_OVERVIEW, Context.MODE_PRIVATE)
            val key = "overview_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            val legacyKey = "cache_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            sp.edit()
                .putString(key, json)
                .putString(legacyKey, json)
                .putLong("ts_$key", System.currentTimeMillis())
                .apply()
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

    fun isOverviewFresh(context: Context, username: String? = null, period: String? = null, month: String? = null, ttlMs: Long = DEFAULT_FRESHNESS_TTL_MS): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREF_OVERVIEW, Context.MODE_PRIVATE)
            val key = "overview_${username.orEmpty()}_${period.orEmpty()}_${month.orEmpty()}"
            val ts = sp.getLong("ts_$key", 0L)
            (System.currentTimeMillis() - ts) < ttlMs
        } catch (_: Exception) {
            false
        }
    }

    fun clearOverviewCache(context: Context) {
        try {
            context.getSharedPreferences(PREF_OVERVIEW, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear overview cache: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // 2. 流水明细持久化与新鲜度 (Records Cache)
    // -------------------------------------------------------------------------
    fun saveRecords(context: Context, month: String? = null, type: String? = null, search: String? = null, page: Int = 1, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_RECORDS, Context.MODE_PRIVATE)
            val key = "records_${month.orEmpty()}_${type.orEmpty()}_${search.orEmpty().trim()}_p${page}"
            sp.edit()
                .putString(key, json)
                .putLong("ts_$key", System.currentTimeMillis())
                .apply()
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

    fun isRecordsFresh(context: Context, month: String? = null, type: String? = null, search: String? = null, page: Int = 1, ttlMs: Long = DEFAULT_FRESHNESS_TTL_MS): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREF_RECORDS, Context.MODE_PRIVATE)
            val key = "records_${month.orEmpty()}_${type.orEmpty()}_${search.orEmpty().trim()}_p${page}"
            val ts = sp.getLong("ts_$key", 0L)
            (System.currentTimeMillis() - ts) < ttlMs
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // 3. 资产与账户持久化 (Accounts Cache)
    // -------------------------------------------------------------------------
    fun saveAccounts(context: Context, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_ACCOUNTS, Context.MODE_PRIVATE)
            sp.edit()
                .putString("accounts_summary_latest", json)
                .putLong("ts_accounts_summary_latest", System.currentTimeMillis())
                .apply()
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
            sp.edit()
                .putString("tools_summary_latest", json)
                .putLong("ts_tools_summary_latest", System.currentTimeMillis())
                .apply()
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

    // -------------------------------------------------------------------------
    // 5. 分类列表持久化 (Categories Cache)
    // -------------------------------------------------------------------------
    private const val PREF_CATEGORIES = "ledger_categories_cache"

    fun saveCategories(context: Context, json: String) {
        try {
            val sp = context.getSharedPreferences(PREF_CATEGORIES, Context.MODE_PRIVATE)
            sp.edit()
                .putString("categories_latest", json)
                .putLong("ts_categories_latest", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save categories cache: ${e.message}")
        }
    }

    fun getCategories(context: Context): String? {
        return try {
            val sp = context.getSharedPreferences(PREF_CATEGORIES, Context.MODE_PRIVATE)
            sp.getString("categories_latest", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get categories cache: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // 6. 全量同步时间戳 (Full Sync Timestamp)
    // -------------------------------------------------------------------------
    fun saveFullSyncTime(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREF_SYNC, Context.MODE_PRIVATE)
            sp.edit().putLong("last_full_sync_time", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    fun isFullSyncFresh(context: Context, ttlMs: Long = DEFAULT_FRESHNESS_TTL_MS): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREF_SYNC, Context.MODE_PRIVATE)
            val ts = sp.getLong("last_full_sync_time", 0L)
            (System.currentTimeMillis() - ts) < ttlMs
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var isSyncing = false

    // -------------------------------------------------------------------------
    // 7. 进 App 时全量预载所有数据 (App Startup & Periodic Background Sync)
    // 进 App 时一次性拉取全部核心数据存入本地，后续切月与翻页完全不发网络请求
    // -------------------------------------------------------------------------
    suspend fun syncAllUserData(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (isSyncing) {
            Log.d(TAG, "syncAllUserData already in progress. Skipping redundant concurrent call.")
            return@withContext
        }
        if (!force && isFullSyncFresh(context)) {
            Log.d(TAG, "All user data is fresh (within 5 minutes). Skipping online db fetch.")
            return@withContext
        }
        if (!NetworkHelper.isOnline(context)) {
            Log.d(TAG, "Device is offline. Running purely on local database.")
            return@withContext
        }

        isSyncing = true
        try {
            Log.i(TAG, "Syncing data from Turso Cloud & preloading local database...")
            try {
                TursoCloudClient.syncAllFromTurso(context)
            } catch (e: Exception) {
                Log.w(TAG, "Turso cloud sync skipped: ${e.message}")
            }
            val username = NetworkHelper.getUsername(context)
            val today = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val prevMonth = shiftMonth(today, -1)
            val prev2Month = shiftMonth(today, -2)

            LocalLedgerRepository.initDefaultsIfNeeded(context)

            for ((p, m) in listOf(Pair("month", today), Pair("month", prevMonth), Pair("month", prev2Month), Pair("3m", today), Pair("year", today))) {
                val yr = m.substringBefore('-').toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
                val ovJson = LocalLedgerRepository.getOverviewJson(context, month = m, period = p, year = yr).getOrNull()?.toString()
                if (ovJson != null) {
                    saveOverview(context, username, p, m, ovJson)
                }
            }

            for (m in listOf(today, prevMonth)) {
                NativeLedgerApi.getTransactions(
                    context = context,
                    type = "all",
                    month = m,
                    page = 1,
                    limit = 50,
                    forceRefresh = true
                )
                CashflowRepository.load(context, m, forceRefresh = true)
            }

            NativeLedgerApi.getAccounts(context, forceRefresh = true)
            NativeLedgerApi.getCategories(context, forceRefresh = true)

            saveFullSyncTime(context)
            Log.i(TAG, "All local user data successfully preloaded into cache!")

            try {
                val intent = Intent("com.siangloh.ledger.DATA_SYNCED")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "syncAllUserData failed: ${e.message}", e)
        } finally {
            isSyncing = false
        }
    }
}
