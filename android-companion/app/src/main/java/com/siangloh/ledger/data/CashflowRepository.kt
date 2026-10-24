package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale


/** 离线优先的收支分析数据仓库 (Local-First Cashflow Repository) */
object CashflowRepository {
    data class Result(val data: JSONObject, val cached: Boolean)

    private fun getCacheKey(context: Context, month: String, currency: String): String {
        val server = NetworkHelper.getServerUrl(context).trimEnd('/')
        val key = NetworkHelper.getApiKey(context)
        val user = NetworkHelper.getUsername(context)
        val scope = "$server|$user|$key|$month|$currency"
        return MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 100% 同步纯本地获取缓存。0ms 延迟，无网络开销。
     * 若当前指定币种未找到，会尝试用默认币种的本地缓存兜底。
     */
    fun getCached(context: Context, month: String, currency: String = ""): Result? {
        return try {
            val cache = context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE)
            val directKey = getCacheKey(context, month, currency)
            val directSaved = cache.getString(directKey, null)
            if (directSaved != null) {
                return Result(JSONObject(directSaved), cached = true)
            }
            if (currency.isNotEmpty()) {
                val fallbackKey = getCacheKey(context, month, "")
                val fallbackSaved = cache.getString(fallbackKey, null)
                if (fallbackSaved != null) {
                    val fallbackJson = JSONObject(fallbackSaved)
                    if (fallbackJson.optString("currency").equals(currency, ignoreCase = true)) {
                        return Result(fallbackJson, cached = true)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("Cashflow", "getCached failed: ${e.message}")
            null
        }
    }

    fun invalidate(context: Context) {
        try {
            context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    /**
     * 离线优先加载 (Local-First / Offline-First)
     * - 如果 !forceRefresh 且本地已有缓存，立即返回本地缓存 (0ms 瞬切)，绝对不阻塞等待慢速网络
     * - 仅当无本地缓存，或用户明确要求 forceRefresh (如手动点击刷新) 时，才发起网络请求
     */
    suspend fun load(
        context: Context,
        month: String,
        currency: String = "",
        forceRefresh: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        val cacheKey = getCacheKey(context, month, currency)
        val cache = context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE)

        // 1. 本地优先：未强制刷新时，只要本地有缓存，直接返回本地数据！
        if (!forceRefresh) {
            val cachedResult = getCached(context, month, currency)
            if (cachedResult != null) {
                return@withContext cachedResult
            }
        }

        LocalLedgerRepository.initDefaultsIfNeeded(context)
        val db = AppDatabase.getInstance(context)
        val txList = db.localTransactionDao().getByMonth("$month%")
        val accounts = db.localAccountDao().getAll().associateBy { it.id }

        var inc = 0.0
        var exp = 0.0

        val expMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val incMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val dailyMap = mutableMapOf<String, Double>()
        val txArray = org.json.JSONArray()

        for (tx in txList) {
            val amt = tx.amount
            if (tx.type == "expense") {
                exp += amt
                val entry = expMap.getOrPut(tx.category) { Pair(0.0, mutableListOf()) }
                expMap[tx.category] = Pair(entry.first + amt, entry.second.apply { add(tx.id.toInt()) })
                dailyMap[tx.date] = (dailyMap[tx.date] ?: 0.0) + amt
            } else if (tx.type == "income") {
                inc += amt
                val entry = incMap.getOrPut(tx.category) { Pair(0.0, mutableListOf()) }
                incMap[tx.category] = Pair(entry.first + amt, entry.second.apply { add(tx.id.toInt()) })
            }

            txArray.put(JSONObject().apply {
                put("id", tx.id)
                put("date", tx.date)
                put("type", tx.type)
                put("amount", tx.amount)
                put("note", tx.note)
                put("category", tx.category)
                put("channel", tx.channel ?: "")
                put("account_name", accounts[tx.accountId]?.name ?: "默认账户")
            })
        }

        val avail = inc - exp

        val expCategories = org.json.JSONArray()
        for ((cat, pair) in expMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            expCategories.put(JSONObject().apply {
                put("label", cat)
                put("amount", amt)
                put("income_percent", if (inc > 0) amt / inc * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val incPlatforms = org.json.JSONArray()
        for ((cat, pair) in incMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            incPlatforms.put(JSONObject().apply {
                put("key", cat)
                put("label", cat)
                put("amount", amt)
                put("income_percent", if (inc > 0) amt / inc * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val dailyArray = org.json.JSONArray()
        for (d in 1..31) {
            val dateStr = String.format(Locale.getDefault(), "%s-%02d", month, d)
            dailyArray.put(dailyMap[dateStr] ?: 0.0)
        }

        val cur = if (currency.isNotBlank()) currency else "RM"
        val data = JSONObject().apply {
            put("ok", true)
            put("month", month)
            put("currency", cur)
            put("currencies", org.json.JSONArray().put(cur))
            put("income", inc)
            put("expense", exp)
            put("reserved", 0.0)
            put("planned", 0.0)
            put("paid", exp)
            put("payment_percent", 100.0)
            put("available", avail)
            put("daily", dailyArray)
            put("expense_percent", if (inc > 0) (exp / inc * 100) else 0.0)
            put("reserve_percent", 0.0)
            put("available_percent", if (inc > 0) (avail / inc * 100) else 0.0)
            put("transactions", txArray)
            put("expense_categories", expCategories)
            put("income_platforms", incPlatforms)
            put("bills", org.json.JSONArray())
            put("prior_overdue", org.json.JSONArray())
            put("scheduled_transactions", org.json.JSONArray())
            put("confirmed_transactions", org.json.JSONArray())
            put("inactive_bills", org.json.JSONArray())
        }
        cache.edit().putString(cacheKey, data.toString()).apply()
        Result(data, false)
    }

    suspend fun update(context: Context, payload: JSONObject) = withContext(Dispatchers.IO) {
        // 本地单机模式：清除现金流缓存以触发本地重新计算
        context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

