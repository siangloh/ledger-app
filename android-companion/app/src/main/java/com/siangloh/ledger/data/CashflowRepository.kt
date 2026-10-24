package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

        val overviewRes = LocalLedgerRepository.getOverviewJson(context, month).getOrNull()
        val inc = overviewRes?.optDouble("income", 0.0) ?: 0.0
        val exp = overviewRes?.optDouble("expense", 0.0) ?: 0.0
        val avail = inc - exp
        val sparkline = overviewRes?.optJSONArray("sparkline")
        val dailyArray = org.json.JSONArray()
        for (d in 1..31) {
            val dateStr = String.format(Locale.getDefault(), "%s-%02d", month, d)
            var dayAmt = 0.0
            if (sparkline != null) {
                for (i in 0 until sparkline.length()) {
                    val pt = sparkline.optJSONObject(i)
                    if (pt?.optString("date") == dateStr) {
                        dayAmt = pt.optDouble("amount", 0.0)
                        break
                    }
                }
            }
            dailyArray.put(dayAmt)
        }

        val data = JSONObject().apply {
            put("ok", true)
            put("month", month)
            put("currency", if (currency.isNotBlank()) currency else "RM")
            put("income", inc)
            put("expense", exp)
            put("reserved", 0.0)
            put("available", avail)
            put("daily", dailyArray)
            put("expense_percent", if (inc > 0) (exp / inc * 100) else 0.0)
            put("reserve_percent", 0.0)
            put("available_percent", if (inc > 0) (avail / inc * 100) else 0.0)
        }
        cache.edit().putString(cacheKey, data.toString()).apply()
        Result(data, false)
    }

    suspend fun update(context: Context, payload: JSONObject) = withContext(Dispatchers.IO) {
        val user = URLEncoder.encode(NetworkHelper.getUsername(context), "UTF-8")
        val server = NetworkHelper.getServerUrl(context).trimEnd('/')
        val connection = URL("$server/api/native/cashflow-analysis?username=$user").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-API-KEY", NetworkHelper.getApiKey(context))
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            check(connection.responseCode in 200..299) { "Cashflow update failed" }
            check(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok")) { "Cashflow update rejected" }
            // Invalidate every month/currency: a payment may affect earlier bills.
            context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
        } finally { connection.disconnect() }
    }
}
