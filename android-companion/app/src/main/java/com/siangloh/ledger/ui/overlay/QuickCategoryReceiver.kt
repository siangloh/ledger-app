package com.siangloh.ledger.ui.overlay

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class QuickCategoryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuickCategoryReceiver"
        const val ACTION_CATEGORIZE = "com.siangloh.ledger.ACTION_CATEGORIZE"
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_MERCHANT = "extra_merchant"
        const val EXTRA_REMEMBER = "extra_remember"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CATEGORIZE) return

        val txId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L)
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
        val merchant = intent.getStringExtra(EXTRA_MERCHANT) ?: ""
        val remember = intent.getBooleanExtra(EXTRA_REMEMBER, true)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        // Dismiss notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notifId != 0) {
            notificationManager.cancel(notifId)
        }

        if (txId <= 0) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverUrl = NetworkHelper.getServerUrl(context)
                val apiKey = NetworkHelper.getApiKey(context)
                val username = NetworkHelper.getUsername(context)

                val url = URL("$serverUrl/api/native/quick-categorize")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("User-Agent", "LedgerAppCompanion/1.0 (Android; Dalvik)")
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                if (apiKey.isNotEmpty()) {
                    conn.setRequestProperty("X-API-KEY", apiKey)
                }
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("key", apiKey)
                    put("transaction_id", txId)
                    put("category", category)
                    put("remember_merchant", remember)
                    if (merchant.isNotEmpty()) {
                        put("note", merchant)
                    }
                    if (username.isNotEmpty()) {
                        put("username", username)
                    }
                }.toString()

                OutputStreamWriter(conn.outputStream, "UTF-8").use { out ->
                    out.write(payload)
                    out.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = JSONObject(respStr)
                    if (respJson.optBoolean("ok", false)) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已将【$merchant】分类为【$category】", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val errMsg = respJson.optString("message", "分类修改失败")
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, "修改失败: $errMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, "分类修改失败 (HTTP $code)", Toast.LENGTH_LONG).show()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update category on backend: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(context, "网络错误，无法更新分类", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
