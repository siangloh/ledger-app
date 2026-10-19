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

/** Explicit account-scoped cache. Never represents a cached response as freshly synced. */
object CashflowRepository {
    data class Result(val data: JSONObject, val cached: Boolean)

    suspend fun load(context: Context, month: String, currency: String = ""): Result = withContext(Dispatchers.IO) {
        val server = NetworkHelper.getServerUrl(context).trimEnd('/')
        val key = NetworkHelper.getApiKey(context)
        val user = NetworkHelper.getUsername(context)
        val scope = "$server|$user|$key|$month|$currency"
        val cacheKey = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val cache = context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE)
        try {
            val query = "month=${URLEncoder.encode(month, "UTF-8")}&currency=${URLEncoder.encode(currency, "UTF-8")}&username=${URLEncoder.encode(user, "UTF-8")}"
            val connection = URL("$server/api/native/cashflow-analysis?$query").openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("X-API-KEY", key)
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                check(connection.responseCode in 200..299) { "Cashflow request failed" }
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                check(data.optBoolean("ok")) { "Invalid cashflow report" }
                cache.edit().putString(cacheKey, data.toString()).apply()
                Result(data, false)
            } finally { connection.disconnect() }
        } catch (error: Exception) {
            Log.w("Cashflow", "Cashflow request failed; checking account-scoped cache", error)
            val saved = cache.getString(cacheKey, null) ?: throw error
            Result(JSONObject(saved), true)
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
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            check(connection.responseCode in 200..299) { "Cashflow update failed" }
            check(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok")) { "Cashflow update rejected" }
            // Invalidate every month/currency: a payment may affect earlier bills.
            context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
        } finally { connection.disconnect() }
    }
}
