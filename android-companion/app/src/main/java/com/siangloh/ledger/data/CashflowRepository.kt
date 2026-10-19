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

        // 2. 离线保护：设备无网络时直接回退到本地缓存，不报错
        if (!NetworkHelper.isOnline(context)) {
            val fallback = getCached(context, month, currency)
            if (fallback != null) {
                return@withContext fallback
            }
            throw IllegalStateException("Offline and no local cashflow cache")
        }

        // 3. 网络请求：使用更紧凑的超时（5秒连接，8秒读取，避免长达15秒的卡死）
        val server = NetworkHelper.getServerUrl(context).trimEnd('/')
        val key = NetworkHelper.getApiKey(context)
        val user = NetworkHelper.getUsername(context)
        try {
            val query = "month=${URLEncoder.encode(month, "UTF-8")}&currency=${URLEncoder.encode(currency, "UTF-8")}&username=${URLEncoder.encode(user, "UTF-8")}"
            val connection = URL("$server/api/native/cashflow-analysis?$query").openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("X-API-KEY", key)
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                check(connection.responseCode in 200..299) { "Cashflow request failed: ${connection.responseCode}" }
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                check(data.optBoolean("ok")) { "Invalid cashflow report" }
                cache.edit().putString(cacheKey, data.toString()).apply()
                Result(data, false)
            } finally { connection.disconnect() }
        } catch (error: Exception) {
            Log.w("Cashflow", "Online fetch failed or timed out; falling back to local cache", error)
            val fallback = getCached(context, month, currency) ?: throw error
            fallback
        }
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
