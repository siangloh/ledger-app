package com.siangloh.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import com.siangloh.ledger.MainActivity
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.CashflowRepository
import com.siangloh.ledger.data.LocalDataStore
import com.siangloh.ledger.data.LocalLedgerRepository
import com.siangloh.ledger.ui.QuickAddActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 账本桌面小部件 (Home Screen AppWidget)
 * 实时离线展示当月净结余、今日支出与待付账单，并支持一键极速记账与刷新
 */
class LedgerAppWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "LedgerAppWidget"
        const val ACTION_WIDGET_REFRESH = "com.siangloh.ledger.ACTION_WIDGET_REFRESH"
        const val ACTION_DATA_SYNCED = "com.siangloh.ledger.DATA_SYNCED"

        /**
         * 通知所有桌面小部件立即重新计算并静默刷新
         */
        fun notifyDataChanged(context: Context) {
            try {
                val intent = Intent(context, LedgerAppWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_REFRESH
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast widget update: ${e.message}")
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == ACTION_WIDGET_REFRESH || action == ACTION_DATA_SYNCED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, LedgerAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in appWidgetIds) {
                updateWidget(context, appWidgetManager, id)
            }

            // 如果是用户主动在桌面点击了刷新按钮，同时触发一次静默同步
            if (action == ACTION_WIDGET_REFRESH && intent.getBooleanExtra("manual_click", false)) {
                CoroutineScope(Dispatchers.IO).launch {
                    LocalDataStore.syncAllUserData(context, force = true)
                }
            }
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cal = Calendar.getInstance()
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)

                val monthStr = String.format(Locale.US, "%04d-%02d", year, month)
                val todayStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

                val db = AppDatabase.getInstance(appCtx)
                val monthTransactions = db.localTransactionDao().getByMonth("$monthStr%")

                var totalIncome = 0.0
                var totalExpense = 0.0
                var todayExpense = 0.0

                for (tx in monthTransactions) {
                    when (tx.type) {
                        "income" -> totalIncome += tx.amount
                        "expense" -> {
                            totalExpense += tx.amount
                            if (tx.date == todayStr) {
                                todayExpense += tx.amount
                            }
                        }
                    }
                }

                val netBalance = totalIncome - totalExpense

                // 获取待付账单数量
                val toolsRes = LocalLedgerRepository.getToolsSummary(appCtx).getOrNull()
                val subs = toolsRes?.subscriptions ?: emptyList()
                val linksArr = CashflowRepository.getPaymentLinks(appCtx)
                val linksList = mutableListOf<org.json.JSONObject>()
                for (i in 0 until linksArr.length()) {
                    linksList.add(linksArr.getJSONObject(i))
                }

                var pendingCount = 0
                for (sub in subs) {
                    if (sub.cost <= 0) continue
                    val billKey = "sub_${sub.id}"
                    val hasLink = linksList.any { it.optString("bill_key") == billKey }
                    val isPaid = if (hasLink) {
                        true
                    } else {
                        monthTransactions.any { tx ->
                            tx.type == "expense" &&
                                (sub.accountId == null || tx.accountId == sub.accountId || (sub.accountName != null && sub.accountName.equals(tx.channel, ignoreCase = true))) &&
                                (tx.category.equals(sub.category, ignoreCase = true) || tx.note.contains(sub.name, ignoreCase = true))
                        }
                    }
                    if (!isPaid) {
                        pendingCount++
                    }
                }

                val currency = "RM"
                val views = RemoteViews(appCtx.packageName, R.layout.widget_ledger_overview)

                // 月份标签
                views.setTextViewText(R.id.tv_widget_month, "· ${month}月")

                // 净结余数值与颜色
                val sign = if (netBalance >= 0) "+" else "-"
                val absVal = abs(netBalance)
                val netStr = "$sign$currency ${String.format(Locale.getDefault(), "%,.2f", absVal)}"
                views.setTextViewText(R.id.tv_widget_net_balance, netStr)
                views.setTextColor(R.id.tv_widget_net_balance, if (netBalance >= 0) Color.parseColor("#0F766E") else Color.parseColor("#B91C1C"))

                // 今日支出
                val todayStrDisplay = "$currency ${String.format(Locale.getDefault(), "%,.2f", todayExpense)}"
                views.setTextViewText(R.id.tv_widget_today_expense, todayStrDisplay)

                // 待付账单
                val pendingDisplay = appCtx.getString(R.string.widget_bills_count_fmt, pendingCount)
                views.setTextViewText(R.id.tv_widget_pending_bills, pendingDisplay)

                // 点击主卡片 -> 打开主 App
                val mainIntent = Intent(appCtx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mainPendingIntent = PendingIntent.getActivity(
                    appCtx,
                    0,
                    mainIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)
                views.setOnClickPendingIntent(R.id.layout_hero_balance, mainPendingIntent)
                views.setOnClickPendingIntent(R.id.layout_widget_today, mainPendingIntent)

                // 点击待付账单卡片 -> 打开主 App 并直接定位到账单分析
                val billsIntent = Intent(appCtx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_tab", "bills")
                }
                val billsPendingIntent = PendingIntent.getActivity(
                    appCtx,
                    1,
                    billsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.layout_widget_bills, billsPendingIntent)

                // 点击「+ 记一笔」按钮 -> 直接弹出原生离线快速记账界面
                val quickAddIntent = Intent(appCtx, QuickAddActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val quickAddPendingIntent = PendingIntent.getActivity(
                    appCtx,
                    2,
                    quickAddIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.btn_widget_quick_add, quickAddPendingIntent)

                // 点击刷新按钮 -> 立即刷新小部件
                val refreshIntent = Intent(appCtx, LedgerAppWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_REFRESH
                    putExtra("manual_click", true)
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    appCtx,
                    3,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.btn_widget_refresh, refreshPendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "updateWidget error: ${e.message}", e)
            }
        }
    }
}

