package com.siangloh.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
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
        const val ACTION_WIDGET_TOGGLE_PATTERN = "com.siangloh.ledger.ACTION_WIDGET_TOGGLE_PATTERN"
        const val ACTION_WIDGET_SELECT_DAY = "com.siangloh.ledger.ACTION_WIDGET_SELECT_DAY"
        const val EXTRA_DAY_INDEX = "extra_day_index"
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
        if (action == ACTION_WIDGET_TOGGLE_PATTERN) {
            val prefs = context.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE)
            val current = prefs.getInt("chart_pattern", WidgetChartRenderer.PATTERN_TREND)
            val next = (current + 1) % 3
            prefs.edit().putInt("chart_pattern", next).apply()
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, LedgerAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in appWidgetIds) {
                updateWidget(context, appWidgetManager, id)
            }
            return
        }

        if (action == ACTION_WIDGET_SELECT_DAY) {
            val dayIndex = intent.getIntExtra(EXTRA_DAY_INDEX, 6)
            val targetWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val prefs = context.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (targetWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                prefs.edit().putInt("selected_day_index_$targetWidgetId", dayIndex).apply()
                updateWidget(context, appWidgetManager, targetWidgetId)
            } else {
                val thisWidget = ComponentName(context, LedgerAppWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                val editor = prefs.edit()
                for (id in appWidgetIds) {
                    editor.putInt("selected_day_index_$id", dayIndex)
                }
                editor.apply()
                for (id in appWidgetIds) {
                    updateWidget(context, appWidgetManager, id)
                }
            }
            return
        }

        if (action == ACTION_WIDGET_REFRESH || action == ACTION_DATA_SYNCED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, LedgerAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (action == ACTION_WIDGET_REFRESH) {
                // 刷新时重置所有小部件选中的日期回今天 (index = 6)
                val prefs = context.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for (id in appWidgetIds) {
                    editor.putInt("selected_day_index_$id", 6)
                }
                editor.apply()
            }
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("selected_day_index_$id")
        }
        editor.apply()
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val lang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(context)
        val appCtx = com.siangloh.ledger.util.AppLanguageManager.wrapContext(context.applicationContext, lang)
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
                var pendingAmount = 0.0
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
                        pendingAmount += sub.cost
                    }
                }

                val safeCashflow = netBalance - pendingAmount

                val currency = subs.firstOrNull { it.currency.isNotBlank() }?.currency ?: "RM"
                val views = RemoteViews(appCtx.packageName, R.layout.widget_ledger_overview)
                val isDarkTheme = (appCtx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                // 读取当前用户偏好的图表模式与当前小部件独立选中的探索天数 (默认 6 即今天)
                val prefs = appCtx.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE)
                val chartPattern = prefs.getInt("chart_pattern", WidgetChartRenderer.PATTERN_TREND)
                val selectedDayIndex = prefs.getInt("selected_day_index_$appWidgetId", 6).coerceIn(0, 6)

                // 统计近 7 天支出数据 (从 6 天前到今天，单次批量查询避免多次 DB I/O)
                val dailyExpenses = mutableListOf<Pair<String, Double>>()
                val dailyDates = mutableListOf<Calendar>()
                val barCal = Calendar.getInstance()
                barCal.add(Calendar.DAY_OF_MONTH, -6)
                val sdfDay = SimpleDateFormat("dd", Locale.US)
                val sdfFull = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val startDateStr = sdfFull.format(barCal.time)
                val sevenDayTxs = db.localTransactionDao().getByDateRange(startDateStr, todayStr)
                val sevenDayExpByDate = sevenDayTxs
                    .filter { it.type == "expense" }
                    .groupBy { it.date }
                for (i in 0..6) {
                    val fullDate = sdfFull.format(barCal.time)
                    val label = sdfDay.format(barCal.time)
                    val dayExp = (sevenDayExpByDate[fullDate] ?: emptyList()).sumOf { it.amount }
                    dailyExpenses.add(Pair(label, dayExp))
                    dailyDates.add(barCal.clone() as Calendar)
                    barCal.add(Calendar.DAY_OF_MONTH, 1)
                }

                // 计算选中日期的支出与标题
                val selectedCal = dailyDates[selectedDayIndex]
                val selectedExp = dailyExpenses[selectedDayIndex].second
                val isSelectedToday = (selectedDayIndex == 6)
                val dayExpenseTitle = com.siangloh.ledger.util.AppLanguageManager.formatWidgetDayExpenseTitle(
                    selectedCal,
                    isSelectedToday,
                    lang
                )

                when (chartPattern) {
                    WidgetChartRenderer.PATTERN_TREND -> {
                        val trendPoints = mutableListOf<Double>()
                        var cumulative = 0.0
                        val txByDate = monthTransactions.groupBy { it.date }
                        for (d in 1..day) {
                            val dStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, d)
                            val dayTxList = txByDate[dStr] ?: emptyList()
                            for (tx in dayTxList) {
                                if (tx.type == "income") cumulative += tx.amount
                                else if (tx.type == "expense") cumulative -= tx.amount
                            }
                            trendPoints.add(cumulative)
                        }
                        if (trendPoints.size <= 1) {
                            trendPoints.add(0, 0.0)
                        }
                        // 现金流走势图：折算到走势图对应的后7个数据点 (兼容月初天数不足7天的情况)
                        val trendSelectedIdx = WidgetChartRenderer.computeTrendSelectedIndex(trendPoints.size, selectedDayIndex)
                        val chartBitmap = WidgetChartRenderer.drawCashflowTrend(480, 110, trendPoints, trendSelectedIdx, isDarkTheme)
                        views.setImageViewBitmap(R.id.iv_widget_chart, chartBitmap)
                        views.setViewVisibility(R.id.iv_widget_chart, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.layout_chart_container, android.view.View.VISIBLE)
                    }
                    WidgetChartRenderer.PATTERN_BARS -> {
                        val chartBitmap = WidgetChartRenderer.draw7DaySpendingBars(480, 110, dailyExpenses, selectedDayIndex, isDarkTheme)
                        views.setImageViewBitmap(R.id.iv_widget_chart, chartBitmap)
                        views.setViewVisibility(R.id.iv_widget_chart, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.layout_chart_container, android.view.View.VISIBLE)
                    }
                    else -> {
                        views.setViewVisibility(R.id.layout_chart_container, android.view.View.GONE)
                    }
                }

                // 绑定 7 个独立点击热区 (btn_bar_day_0 ~ btn_bar_day_6)，携带 appWidgetId 实现多实例状态隔离
                val barSlotIds = intArrayOf(
                    R.id.btn_bar_day_0,
                    R.id.btn_bar_day_1,
                    R.id.btn_bar_day_2,
                    R.id.btn_bar_day_3,
                    R.id.btn_bar_day_4,
                    R.id.btn_bar_day_5,
                    R.id.btn_bar_day_6
                )
                for (i in 0..6) {
                    val selectIntent = Intent(appCtx, LedgerAppWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_SELECT_DAY
                        putExtra(EXTRA_DAY_INDEX, i)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                    val selectPendingIntent = PendingIntent.getBroadcast(
                        appCtx,
                        appWidgetId * 100 + i,
                        selectIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(barSlotIds[i], selectPendingIntent)
                }

                // 月份标签 (根据多语言自适应)
                val monthDisplay = com.siangloh.ledger.util.AppLanguageManager.formatWidgetMonth(cal, lang)
                views.setTextViewText(R.id.tv_widget_month, monthDisplay)

                val positiveColor = ContextCompat.getColor(appCtx, R.color.primary)
                val negativeColor = ContextCompat.getColor(appCtx, R.color.expense_red)
                val warningColor = ContextCompat.getColor(appCtx, R.color.warning_amber)

                // 当月净结余数值与颜色
                val sign = if (netBalance >= 0) "+" else "-"
                val absVal = abs(netBalance)
                val netStr = "$sign$currency ${String.format(Locale.getDefault(), "%,.2f", absVal)}"
                views.setTextViewText(R.id.tv_widget_net_balance, netStr)
                views.setTextColor(R.id.tv_widget_net_balance, if (netBalance >= 0) positiveColor else negativeColor)

                // 预估安全现金流 (扣除待付账单后的安全结余)
                val safeSign = if (safeCashflow >= 0) "+" else "-"
                val safeAbsVal = abs(safeCashflow)
                val safeStr = "$safeSign$currency ${String.format(Locale.getDefault(), "%,.2f", safeAbsVal)}"
                views.setTextViewText(R.id.tv_widget_projected_cashflow, safeStr)
                views.setTextColor(R.id.tv_widget_projected_cashflow, if (safeCashflow >= 0) positiveColor else negativeColor)

                // 展示选中日期的支出与标题 (如 "今日支出" 或 "11月10日 支出")
                views.setTextViewText(R.id.tv_widget_today_label, dayExpenseTitle)
                val dayStrDisplay = "$currency ${String.format(Locale.getDefault(), "%,.2f", selectedExp)}"
                views.setTextViewText(R.id.tv_widget_today_expense, dayStrDisplay)

                // 待付账单与金额
                if (pendingCount == 0) {
                    views.setTextViewText(R.id.tv_widget_pending_bills, appCtx.getString(R.string.widget_bills_all_clear))
                    views.setTextColor(R.id.tv_widget_pending_bills, positiveColor)
                } else {
                    val pendingAmtStr = "$currency ${String.format(Locale.getDefault(), "%,.2f", pendingAmount)}"
                    val pendingDisplay = appCtx.getString(R.string.widget_bills_pending_fmt, pendingCount, pendingAmtStr)
                    views.setTextViewText(R.id.tv_widget_pending_bills, pendingDisplay)
                    views.setTextColor(R.id.tv_widget_pending_bills, warningColor)
                }

                // 点击主卡片与顶部余额 -> 打开主 App
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

                // 点击支出卡片：如果当前查看的是历史某天，点击直接还原重置回今天；如果已经是今天，点击打开主 App
                if (!isSelectedToday) {
                    val resetTodayIntent = Intent(appCtx, LedgerAppWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_SELECT_DAY
                        putExtra(EXTRA_DAY_INDEX, 6)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                    val resetTodayPendingIntent = PendingIntent.getBroadcast(
                        appCtx,
                        appWidgetId * 100 + 50,
                        resetTodayIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.layout_widget_today, resetTodayPendingIntent)
                } else {
                    views.setOnClickPendingIntent(R.id.layout_widget_today, mainPendingIntent)
                }

                // 点击现金流与待付账单卡片 -> 打开主 App 并直接定位到现金流分析
                val billsIntent = Intent(appCtx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_tab", "bills")
                    putExtra("open_cashflow", true)
                }
                val billsPendingIntent = PendingIntent.getActivity(
                    appCtx,
                    1,
                    billsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.layout_widget_bills, billsPendingIntent)
                views.setOnClickPendingIntent(R.id.layout_widget_cashflow_badge, billsPendingIntent)
                views.setOnClickPendingIntent(R.id.iv_widget_chart, billsPendingIntent)

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

