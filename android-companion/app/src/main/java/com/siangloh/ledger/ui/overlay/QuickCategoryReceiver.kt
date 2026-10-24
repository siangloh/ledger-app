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
                val res = com.siangloh.ledger.data.LocalLedgerRepository.updateTransactionCategory(context, txId, category)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        Toast.makeText(context, "已将【$merchant】分类为【$category】", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "分类修改失败", Toast.LENGTH_SHORT).show()
                    }
                }
                for (prefs in listOf("cashflow_reports", "ledger_overview_cache", "ledger_records_cache", "ledger_accounts_cache", "ledger_sync_prefs")) {
                    context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().apply()
                }
                try {
                    val syncIntent = Intent("com.siangloh.ledger.SYNC_COMPLETED")
                    syncIntent.setPackage(context.packageName)
                    context.sendBroadcast(syncIntent)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update category locally: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
