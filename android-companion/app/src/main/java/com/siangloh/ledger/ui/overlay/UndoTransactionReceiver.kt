package com.siangloh.ledger.ui.overlay

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.siangloh.ledger.NetworkHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通知栏「撤销」按钮的接收器（已在 AndroidManifest.xml 注册）。
 */
class UndoTransactionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
        }

        val pending = goAsync()
        TransactionUndo.undo(context.applicationContext, transactionId) { pending.finish() }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}

/** 撤销一笔自动记账的交易（悬浮提示条与通知栏共用） */
object TransactionUndo {

    private const val TAG = "TransactionUndo"

    /**
     * ⚠️ 假设的后端接口：POST {serverUrl}/api/native/undo-transaction
     * body: { key, transaction_id, username }，返回 { ok: Boolean, message: String }
     * 如果你的后端路径或参数不同，只需要改这里。
     */
    private const val UNDO_PATH = "/api/native/undo-transaction"

    fun undo(context: Context, transactionId: Long, onDone: () -> Unit = {}) {
        if (transactionId <= 0) {
            toast(context, "这笔账单尚未同步，请到流水里删除")
            onDone()
            return
        }
        Thread {
            var ok = false
            var msg = ""
            try {
                val serverUrl = NetworkHelper.getServerUrl(context)
                val apiKey = NetworkHelper.getApiKey(context)
                val username = NetworkHelper.getUsername(context)

                val conn = URL(serverUrl + UNDO_PATH).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("key", apiKey)
                    put("transaction_id", transactionId)
                    if (username.isNotEmpty()) put("username", username)
                }.toString()
                conn.outputStream.bufferedWriter().use { it.write(payload) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    ok = json.optBoolean("ok", false)
                    msg = json.optString("message", "")
                } else {
                    msg = "HTTP $code"
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Undo failed: ${e.message}", e)
                msg = e.message ?: "网络异常"
            }
            toast(context, if (ok) "已撤销这笔账单" else "撤销失败：$msg")
            onDone()
        }.start()
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
