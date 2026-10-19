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

    // 线程池复用：避免频繁直接 new Thread 造成系统级线程开销与 CPU 调度消耗
    private val networkExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

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

    private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"

    fun isWifiOnlySync(context: Context? = null): Boolean {
        val ctx = context ?: cachedContext ?: return false
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYNC_WIFI_ONLY, false)
        } catch (_: Exception) {
            false
        }
    }

    fun setWifiOnlySync(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_SYNC_WIFI_ONLY, enabled).apply()
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

    fun isWifi(context: Context): Boolean {
        init(context)
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun canSyncNow(context: Context): Boolean {
        if (!isOnline(context)) return false
        if (isWifiOnlySync(context)) {
            return isWifi(context)
        }
        return true
    }

    fun postNotificationAsync(
        text: String,
        context: Context? = null,
        packageName: String? = null,
        callback: ((Boolean, String, String?) -> Unit)? = null
    ) {
        networkExecutor.execute {
            try {
                val key = getApiKey(context)
                val username = getUsername(context)
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }

                val queryParams = mutableListOf<String>()
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
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.doOutput = true

                val jsonPayload = JSONObject().apply {
                    put("key", key)
                    put("text", text)
                    if (!packageName.isNullOrBlank()) {
                        put("package_name", packageName)
                    }
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
                    verdict = if (jsonObj.has("verdict")) jsonObj.optString("verdict") else null
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
        }
    }

    fun syncPendingTransactions(transactions: List<PendingTransaction>, context: Context? = null, callback: ((Boolean, List<Long>) -> Unit)? = null) {
        syncPendingTransactionsDetailed(transactions, context) { success, syncedIds, _ ->
            callback?.invoke(success, syncedIds)
        }
    }

    fun syncPendingTransactionsDetailed(
        transactions: List<PendingTransaction>,
        context: Context? = null,
        callback: ((Boolean, List<Long>, Map<Long, String>) -> Unit)? = null
    ) {
        networkExecutor.execute {
            val syncedIds = mutableListOf<Long>()
            val failedErrors = mutableMapOf<Long, String>()
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
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
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
                        if (tx.account_id != null) put("account_id", tx.account_id)
                        if (tx.to_account_id != null) put("to_account_id", tx.to_account_id)
                        if (tx.tags.isNotBlank()) put("tags", tx.tags)
                    }
                    jsonArray.put(obj)
                }

                val username = getUsername(context)
                val body = JSONObject().apply {
                    put("key", key)
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
                    val failedArr = jsonObj.optJSONArray("failed_items")
                    if (failedArr != null) {
                        for (i in 0 until failedArr.length()) {
                            val f = failedArr.getJSONObject(i)
                            val fid = f.optLong("local_id")
                            val freason = f.optString("reason", "服务器校验拒绝")
                            failedErrors[fid] = freason
                        }
                    }
                } catch (_: Exception) {}

                if (!isSuccess || responseCode !in 200..299) {
                    val errMsg = if (responseCode == 401) "API Key 无效或未授权" else "HTTP $responseCode: ${responseMsg.take(50)}"
                    transactions.forEach { tx ->
                        if (!syncedIds.contains(tx.id)) {
                            failedErrors[tx.id] = failedErrors[tx.id] ?: errMsg
                        }
                    }
                }

                callback?.invoke(isSuccess, syncedIds, failedErrors)
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync pending transactions: ${e.message}", e)
                val errMsg = when (e) {
                    is java.net.SocketTimeoutException -> "网络请求超时 (15s)"
                    is java.net.UnknownHostException -> "无法解析服务器地址，请检查配置"
                    is java.net.ConnectException -> "连接服务器失败，服务未启动或网络阻断"
                    else -> e.message ?: "网络通信异常"
                }
                transactions.forEach { tx ->
                    failedErrors[tx.id] = errMsg
                }
                callback?.invoke(false, emptyList(), failedErrors)
            }
        }
    }

    fun fetchCategories(callback: ((Boolean, List<CachedCategory>) -> Unit)? = null) {
        networkExecutor.execute {
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
        }
    }

    fun getRecycleBinItems(page: Int = 1, callback: ((Boolean, List<JSONObject>, Int, String?) -> Unit)?) {
        networkExecutor.execute {
            try {
                val username = getUsername()
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }
                val fullUrl = "${getServerUrl()}/api/native/recycle-bin?username=$encodedUser&page=$page"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-API-KEY", getApiKey())
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val arr = json.optJSONArray("items")
                        val items = mutableListOf<JSONObject>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                items.add(arr.getJSONObject(i))
                            }
                        }
                        val total = json.optInt("total_count", items.size)
                        callback?.invoke(true, items, total, null)
                    } else {
                        callback?.invoke(false, emptyList(), 0, json.optString("message", "Error"))
                    }
                } else {
                    callback?.invoke(false, emptyList(), 0, "HTTP $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch recycle bin: ${e.message}", e)
                callback?.invoke(false, emptyList(), 0, e.message)
            }
        }
    }

    fun restoreRecycleBinItem(id: Long, callback: ((Boolean, String?) -> Unit)?) {
        networkExecutor.execute {
            try {
                val username = getUsername()
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }
                val fullUrl = "${getServerUrl()}/api/native/recycle-bin/$id/restore?username=$encodedUser"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-API-KEY", getApiKey())
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    callback?.invoke(json.optBoolean("ok"), json.optString("message"))
                } else {
                    callback?.invoke(false, "HTTP $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore recycle item: ${e.message}", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    fun permanentDeleteRecycleBinItem(id: Long, callback: ((Boolean, String?) -> Unit)?) {
        networkExecutor.execute {
            try {
                val username = getUsername()
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }
                val fullUrl = "${getServerUrl()}/api/native/recycle-bin/$id/permanent-delete?username=$encodedUser"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-API-KEY", getApiKey())
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    callback?.invoke(json.optBoolean("ok"), json.optString("message"))
                } else {
                    callback?.invoke(false, "HTTP $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete recycle item: ${e.message}", e)
                callback?.invoke(false, e.message)
            }
        }
    }

    fun emptyRecycleBin(callback: ((Boolean, String?) -> Unit)?) {
        networkExecutor.execute {
            try {
                val username = getUsername()
                val encodedUser = try { java.net.URLEncoder.encode(username, "UTF-8") } catch (_: Exception) { "" }
                val fullUrl = "${getServerUrl()}/api/native/recycle-bin/empty?username=$encodedUser"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-API-KEY", getApiKey())
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    callback?.invoke(json.optBoolean("ok"), json.optString("message"))
                } else {
                    callback?.invoke(false, "HTTP $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to empty recycle bin: ${e.message}", e)
                callback?.invoke(false, e.message)
            }
        }
    }
}
