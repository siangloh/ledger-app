package com.siangloh.ledger

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NetworkHelper {
    private const val TAG = "LedgerNetwork"
    private const val BASE_WEBHOOK_URL = "https://ledger-app-l3hc.onrender.com/api/auto-track"
    private val API_KEY = BuildConfig.API_KEY

    fun postNotificationAsync(text: String, callback: ((Boolean, String) -> Unit)? = null) {
        Thread {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val fullUrl = "$BASE_WEBHOOK_URL?text=$encodedText"

                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-API-KEY", API_KEY)
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                // 发送空 body 或简要内容
                val out = OutputStreamWriter(conn.outputStream, "UTF-8")
                out.write("")
                out.flush()
                out.close()

                val responseCode = conn.responseCode
                Log.d(TAG, "HTTP POST status: $responseCode")

                val isSuccess = responseCode in 200..299
                val responseMsg = if (isSuccess) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
                }

                callback?.invoke(isSuccess, responseMsg)
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification to server: ${e.message}", e)
                callback?.invoke(false, e.message ?: "Unknown network error")
            }
        }.start()
    }
}
