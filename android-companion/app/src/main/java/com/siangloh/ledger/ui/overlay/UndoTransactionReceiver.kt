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
import com.siangloh.ledger.R
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

    /** Recoverable, authenticated deletion; repeated requests are safe. */
    private const val UNDO_PATH = "/api/native/undo-transaction"

    fun undo(context: Context, transactionId: Long, onDone: () -> Unit = {}) {
        if (transactionId <= 0) {
            toast(context, context.getString(R.string.undo_transaction_unsynced))
            onDone()
            return
        }
        Thread {
            var ok = false
            try {
                kotlinx.coroutines.runBlocking {
                    val res = com.siangloh.ledger.data.LocalLedgerRepository.deleteTransaction(context, transactionId)
                    ok = res.isSuccess
                }
                if (ok) {
                    for (prefs in listOf("cashflow_reports", "ledger_overview_cache", "ledger_records_cache", "ledger_accounts_cache", "ledger_sync_prefs")) {
                        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().apply()
                    }
                    try {
                        val intent = Intent("com.siangloh.ledger.SYNC_COMPLETED")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Undo failed: ${e.message}", e)
            }
            toast(context, context.getString(if (ok) R.string.undo_transaction_success else R.string.undo_transaction_failed))
            onDone()
        }.start()
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
