package com.siangloh.ledger

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.siangloh.ledger.data.entities.CachedCategory
import com.siangloh.ledger.data.entities.PendingTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object NetworkHelper {
    private const val TAG = "LedgerNetwork"
    const val DEFAULT_BASE_URL = "https://ledger-app-l3hc.onrender.com"
    private const val PREFS_NAME = "ledger_network_prefs"
    private const val KEY_SERVER_URL = "custom_server_url"

    const val DEFAULT_API_KEY = "zo}SxK_}_%0LO8w;"
    private const val KEY_API_KEY = "custom_api_key"

    private const val KEY_USERNAME = "custom_username"

    private var cachedContext: Context? = null

    fun init(context: Context) {
        cachedContext = context.applicationContext
    }

    fun getUsername(context: Context? = null): String {
        val ctx = context ?: cachedContext
        return ctx?.let {
            try {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_USERNAME, "")?.trim() ?: ""
            } catch (_: Exception) { "" }
        } ?: ""
    }

    fun setUsername(context: Context, username: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clean = username.trim()
        if (clean.isEmpty()) {
            sp.edit().remove(KEY_USERNAME).apply()
        } else {
            sp.edit().putString(KEY_USERNAME, clean).apply()
        }
    }

    fun getApiKey(context: Context? = null): String {
        val ctx = context ?: cachedContext
        val spKey = ctx?.let {
            try {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_API_KEY, null)?.trim()
            } catch (_: Exception) { null }
        }
        if (!spKey.isNullOrEmpty()) return spKey
        val buildKey = BuildConfig.API_KEY.trim()
        if (buildKey.isNotEmpty()) return buildKey
        return DEFAULT_API_KEY
    }

    fun setApiKey(context: Context, key: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clean = key.trim()
        if (clean.isEmpty() || clean == DEFAULT_API_KEY) {
            sp.edit().remove(KEY_API_KEY).apply()
        } else {
            sp.edit().putString(KEY_API_KEY, clean).apply()
        }
    }

    fun getServerUrl(context: Context? = null): String {
        val ctx = context ?: cachedContext
        if (ctx == null) return DEFAULT_BASE_URL
        return try {
            val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val custom = sp.getString(KEY_SERVER_URL, null)?.trim()?.trimEnd('/')
            if (!custom.isNullOrEmpty()) custom else DEFAULT_BASE_URL
        } catch (_: Exception) {
            DEFAULT_BASE_URL
        }
    }

    fun setServerUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty() || clean.equals(DEFAULT_BASE_URL, ignoreCase = true)) {
            sp.edit().remove(KEY_SERVER_URL).apply()
        } else {
            sp.edit().putString(KEY_SERVER_URL, clean).apply()
        }
    }

    fun isOnline(context: Context): Boolean {
        init(context)
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun postNotificationAsync(text: String, context: Context? = null, callback: ((Boolean, String, String?) -> Unit)? = null) {
        Thread {
            try {
                val key = getApiKey(context)
                val username = getUsername(context)
                val encodedKey = try { java.net.URLEncoder.encode(key, "UTF-8") } catch (_: Exception) { "" }
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }

                val queryParams = mutableListOf<String>()
                if (encodedKey.isNotEmpty()) queryParams.add("key=$encodedKey")
                if (encodedUser.isNotEmpty()) queryParams.add("username=$encodedUser")

                val fullUrl = if (queryParams.isNotEmpty()) {
                    "${getServerUrl(context)}/api/auto-track?" + queryParams.joinToString("&")
                } else {
                    "${getServerUrl(context)}/api/auto-track"
                }
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("User-Agent", "LedgerAppCompanion/1.0 (Android; Dalvik)")
                if (key.isNotEmpty()) {
                    conn.setRequestProperty("X-API-KEY", key)
                }
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.doOutput = true

                val jsonPayload = JSONObject().apply {
                    put("key", key)
                    put("text", text)
                    if (username.isNotEmpty()) {
                        put("username", username)
                    }
                }.toString()

                OutputStreamWriter(conn.outputStream, "UTF-8").use { out ->
                    out.write(jsonPayload)
                    out.flush()
                }

                val responseCode = conn.responseCode
                val responseMsg = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
                }

                var verdict: String? = null
                var isSuccess = responseCode in 200..299
                try {
                    val jsonObj = JSONObject(responseMsg)
                    verdict = jsonObj.optString("verdict", null)
                    if (jsonObj.has("ok")) {
                        isSuccess = jsonObj.optBoolean("ok")
                    }
                } catch (_: Exception) {}

                callback?.invoke(isSuccess, responseMsg, verdict)
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification to server: ${e.message}", e)
                callback?.invoke(false, e.message ?: "Unknown network error", null)
            }
        }.start()
    }

    fun syncPendingTransactions(transactions: List<PendingTransaction>, context: Context? = null, callback: ((Boolean, List<Long>) -> Unit)? = null) {
        Thread {
            try {
                val fullUrl = "${getServerUrl(context)}/api/transactions/sync"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("User-Agent", "LedgerAppCompanion/1.0 (Android; Dalvik)")
                val key = getApiKey(context)
                if (key.isNotEmpty()) {
                    conn.setRequestProperty("X-API-KEY", key)
                }
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.doOutput = true

                val jsonArray = JSONArray()
                transactions.forEach { tx ->
                    val obj = JSONObject().apply {
                        put("local_id", tx.id)
                        put("date", tx.date)
                        put("type", tx.type)
                        put("group_name", tx.group_name)
                        put("category", tx.category)
                        put("amount", tx.amount)
                        put("note", tx.note)
                        put("source", tx.source)
                    }
                    jsonArray.put(obj)
                }

                val username = getUsername(context)
                val body = JSONObject().apply {
                    put("transactions", jsonArray)
                    if (username.isNotEmpty()) {
                        put("username", username)
                    }
                }.toString()

                OutputStreamWriter(conn.outputStream, "UTF-8").use { out ->
                    out.write(body)
                    out.flush()
                }

                val responseCode = conn.responseCode
                val responseMsg = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
                }

                var isSuccess = responseCode in 200..299
                val syncedIds = mutableListOf<Long>()
                try {
                    val jsonObj = JSONObject(responseMsg)
                    if (jsonObj.has("ok")) {
                        isSuccess = jsonObj.optBoolean("ok")
                    }
                    val idsArr = jsonObj.optJSONArray("synced_ids")
                    if (idsArr != null) {
                        for (i in 0 until idsArr.length()) {
                            syncedIds.add(idsArr.getLong(i))
                        }
                    }
                } catch (_: Exception) {}

                callback?.invoke(isSuccess, syncedIds)
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync pending transactions: ${e.message}", e)
                callback?.invoke(false, emptyList())
            }
        }.start()
    }

    fun fetchCategories(callback: ((Boolean, List<CachedCategory>) -> Unit)? = null) {
        Thread {
            try {
                val username = getUsername()
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }
                val fullUrl = if (encodedUser.isNotEmpty()) {
                    "${getServerUrl()}/api/categories?username=$encodedUser"
                } else {
                    "${getServerUrl()}/api/categories"
                }
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-API-KEY", getApiKey())
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                val resultList = mutableListOf<CachedCategory>()
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val arr = json.optJSONArray("categories")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                resultList.add(
                                    CachedCategory(
                                        id = item.optLong("id", i.toLong() + 1),
                                        name = item.optString("name", ""),
                                        type = item.optString("type", "expense"),
                                        group_name = item.optString("group_name", "personal")
                                    )
                                )
                            }
                        }
                    }
                    callback?.invoke(true, resultList)
                } else {
                    callback?.invoke(false, emptyList())
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch categories: ${e.message}", e)
                callback?.invoke(false, emptyList())
            }
        }.start()
    }
}
