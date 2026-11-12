package com.siangloh.ledger.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.siangloh.ledger.MainActivity
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.CashflowRepository
import com.siangloh.ledger.data.LocalLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 离线优先的账单到期提醒后台任务 (Local Bill Due Date Reminder Worker)
 * 每日定时在手机本地无网检查待付账单，在到期前 3 天、前 1 天及当天发出通知预警
 */
class BillDueReminderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BillDueReminder"
        private const val CHANNEL_ID = "bill_due_reminders_channel"
        private const val WORK_NAME = "periodic_bill_due_reminder"
        private const val PREFS_NAME = "bill_due_reminder_prefs"

        fun scheduleDaily(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BillDueReminderWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.i(TAG, "Scheduled daily BillDueReminderWorker successfully.")
        }

        fun runOnceImmediately(context: Context) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<BillDueReminderWorker>().build()
            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            checkAndNotifyDueBills()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "BillDueReminderWorker failed: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun checkAndNotifyDueBills() {
        val now = Calendar.getInstance()
        val curYear = now.get(Calendar.YEAR)
        val curMonth = now.get(Calendar.MONTH) + 1 // 1-12
        val curDay = now.get(Calendar.DAY_OF_MONTH)
        val monthStr = String.format(Locale.US, "%04d-%02d", curYear, curMonth)
        val todayIso = String.format(Locale.US, "%04d-%02d-%02d", curYear, curMonth, curDay)

        val toolsRes = LocalLedgerRepository.getToolsSummary(appContext).getOrNull()
        val subs = toolsRes?.subscriptions ?: return
        if (subs.isEmpty()) return

        val db = AppDatabase.getInstance(appContext)
        val txList = db.localTransactionDao().getByMonth("$monthStr%")
        val linksArr = CashflowRepository.getPaymentLinks(appContext)

        val linksList = mutableListOf<org.json.JSONObject>()
        for (i in 0 until linksArr.length()) {
            linksList.add(linksArr.getJSONObject(i))
        }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannelIfNeeded(nm)

        for (sub in subs) {
            // 如果设置为自动续订/自动扣款，用户无需手动操作，优先关注未标记自动扣款项
            if (sub.autoRenew) continue
            if (sub.cost <= 0) continue

            // 检查该账单在当月是否已结清 (通过关联或匹配支出)
            val billKey = "sub_${sub.id}"
            val directLinks = linksList.filter { it.optString("bill_key") == billKey }

            val isPaid = if (directLinks.isNotEmpty()) {
                var sum = 0.0
                for (link in directLinks) {
                    val txId = link.optLong("transaction_id")
                    val isRefund = link.optBoolean("is_refund", false)
                    val tx = txList.find { it.id == txId } ?: db.localTransactionDao().getById(txId)
                    if (tx != null) {
                        if (isRefund) sum -= tx.amount else sum += tx.amount
                    }
                }
                (sub.cost - sum) <= 0.0
            } else {
                txList.any { tx ->
                    tx.type == "expense" &&
                        (sub.accountId == null || tx.accountId == sub.accountId || (sub.accountName != null && sub.accountName.equals(tx.channel, ignoreCase = true))) &&
                        (tx.category.equals(sub.category, ignoreCase = true) || tx.note.contains(sub.name, ignoreCase = true))
                }
            }

            if (isPaid) continue // 已付清的账单无需提醒

            // 解析账单扣款日
            val dueDay = parseDayOfMonth(sub.nextBillingDate) ?: 15
            val daysDiff = dueDay - curDay

            // 在到期前 3 天、前 1 天以及当天进行提醒
            if (daysDiff in listOf(3, 1, 0)) {
                val dedupKey = "notified_${sub.id}_${todayIso}_diff$daysDiff"
                if (prefs.getBoolean(dedupKey, false)) {
                    continue // 今天已经提醒过该阶段，不重复打扰
                }

                val curSymbol = sub.currency.ifBlank { "RM" }
                val amountStr = "$curSymbol ${String.format(Locale.getDefault(), "%.2f", sub.cost)}"
                val dueIso = String.format(Locale.US, "%04d-%02d-%02d", curYear, curMonth, dueDay)

                val message = when (daysDiff) {
                    0 -> appContext.getString(R.string.notif_bill_due_today, amountStr)
                    1 -> appContext.getString(R.string.notif_bill_due_tomorrow, amountStr)
                    else -> appContext.getString(R.string.notif_bill_due_days, daysDiff, dueIso, amountStr)
                }

                val title = appContext.getString(R.string.notif_bill_due_title, sub.name)
                sendDueNotification(nm, sub.id, title, message)
                prefs.edit().putBoolean(dedupKey, true).apply()
            }
        }
    }

    private fun parseDayOfMonth(nextBillingDate: String): Int? {
        val match = Regex("""\d+""").find(nextBillingDate) ?: return null
        val num = match.value.toIntOrNull() ?: return null
        return if (num in 1..31) num else null
    }

    private fun createChannelIfNeeded(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notif_bill_due_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.notif_bill_due_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendDueNotification(nm: NotificationManager, subId: Int, title: String, message: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_tab", "bills")
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            subId + 10000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(subId + 10000, notification)
    }
}

