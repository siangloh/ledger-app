package com.siangloh.ledger.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.ui.compose.components.OverviewSkeletonScreen
import com.siangloh.ledger.ui.compose.components.SpendingHeroCardSkeleton
import com.siangloh.ledger.ui.compose.components.SpendingTrendSkeleton
import com.siangloh.ledger.ui.compose.components.RecentTransactionsSkeleton
import com.siangloh.ledger.ui.compose.theme.*
import com.siangloh.ledger.ui.overlay.InstantReviewManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import com.siangloh.ledger.ui.compose.components.SourceBrandBadge

data class TransactionItem(
    val id: Long,
    val date: String,
    val type: String,
    val category: String,
    val amount: Double,
    val note: String,
    val source: String,
    val channel: String? = null,
    val badgeText: String = "",
    val badgeEmoji: String = "",
    val badgeColor: String = "",
    val isEdited: Boolean = false,
    val isOfflineLocal: Boolean = false
)

data class SparklinePoint(
    val date: String,
    val amount: Double
)

data class UncategorizedItem(
    val id: Long,
    val date: String,
    val amount: Double,
    val note: String,
    val category: String,
    val isPendingOnHold: Boolean = false,
    val sourceType: String = if (isPendingOnHold) "pending" else "transaction",
    val reviewReason: String = "unclear_category",
    val onHoldReason: String = "",
    val suspectedDuplicateId: Long? = null,
    val suspectedDuplicateNote: String? = null,
    val suspectedDuplicateAmount: Double? = null,
    val suspectedDuplicateCategory: String? = null,
    val suspectedDuplicateDate: String? = null
)

data class OverviewData(
    val totalSpending: Double = 0.0,
    val expenseAmount: Double = 0.0,
    val transferAmount: Double = 0.0,
    val uncategorizedCount: Int = 0,
    val uncategorizedAmount: Double = 0.0,
    val uncategorizedItems: List<UncategorizedItem> = emptyList(),
    val categories: List<String> = emptyList(),
    val sparkline: List<SparklinePoint> = emptyList(),
    val recentTransactions: List<TransactionItem> = emptyList(),
    val lastActiveTime: String = "刚刚",
    val isListening: Boolean = true,
    val isOfflineCache: Boolean = false
)

fun formatFriendlyTime(rawIso: String, context: Context? = null): String {
    if (rawIso.isBlank() || rawIso == "无近期记录" || rawIso == "离线本地缓存" || rawIso == "守护中" || rawIso == "刚刚") {
        if (context != null) {
            return when (rawIso) {
                "守护中" -> context.getString(R.string.sync_status_guarding)
                "离线本地缓存" -> context.getString(R.string.sync_status_offline)
                "刚刚" -> context.getString(R.string.sync_status_just_now)
                else -> rawIso.ifBlank { context.getString(R.string.sync_status_just_now) }
            }
        }
        return rawIso.ifBlank { "刚刚" }
    }
    return try {
        val cleanStr = rawIso.replace('T', ' ').substringBefore('.')
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(cleanStr) ?: return rawIso
        val diffMs = System.currentTimeMillis() - date.time
        val diffMinutes = diffMs / (60 * 1000)
        when {
            diffMinutes < 1 -> context?.getString(R.string.sync_status_just_now) ?: "刚刚"
            diffMinutes < 60 -> context?.getString(R.string.sync_status_mins_ago, diffMinutes) ?: "${diffMinutes}分钟前"
            else -> {
                val cal = Calendar.getInstance().apply { time = date }
                val nowCal = Calendar.getInstance()
                val isToday = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                              cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
                if (isToday) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                    context?.getString(R.string.sync_today_fmt, timeStr) ?: "今天 $timeStr"
                } else {
                    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(date)
                }
            }
        }
    } catch (_: Exception) {
        rawIso
    }
}

fun parseOverviewJson(result: String, isListeningActual: Boolean, isOfflineCache: Boolean): OverviewData {
    return try {
        if (result.isBlank()) {
            return OverviewData(isListening = isListeningActual, isOfflineCache = isOfflineCache)
        }
        val json = JSONObject(result)
        val totalSpendingRaw = if (json.isNull("total_spending")) 0.0 else json.optDouble("total_spending", 0.0)
        val totalSpending = if (totalSpendingRaw.isNaN() || totalSpendingRaw.isInfinite()) 0.0 else totalSpendingRaw

        val expenseAmountRaw = if (json.isNull("expense_amount")) 0.0 else json.optDouble("expense_amount", 0.0)
        val expenseAmount = if (expenseAmountRaw.isNaN() || expenseAmountRaw.isInfinite()) 0.0 else expenseAmountRaw

        val transferAmountRaw = if (json.isNull("transfer_amount")) 0.0 else json.optDouble("transfer_amount", 0.0)
        val transferAmount = if (transferAmountRaw.isNaN() || transferAmountRaw.isInfinite()) 0.0 else transferAmountRaw

        val uncategorizedCount = if (json.isNull("uncategorized_count")) 0 else json.optInt("uncategorized_count", 0)

        val uncategorizedAmountRaw = if (json.isNull("uncategorized_amount")) 0.0 else json.optDouble("uncategorized_amount", 0.0)
        val uncategorizedAmount = if (uncategorizedAmountRaw.isNaN() || uncategorizedAmountRaw.isInfinite()) 0.0 else uncategorizedAmountRaw

        val sparkArray = json.optJSONArray("sparkline")
        val sparkList = mutableListOf<SparklinePoint>()
        if (sparkArray != null) {
            for (i in 0 until sparkArray.length()) {
                try {
                    val item = sparkArray.optJSONObject(i) ?: continue
                    val amtRaw = if (item.isNull("amount")) 0.0 else item.optDouble("amount", 0.0)
                    val amt = if (amtRaw.isNaN() || amtRaw.isInfinite()) 0.0 else amtRaw
                    val d = item.optString("date", "")
                    if (d.isNotBlank() && d != "null") {
                        sparkList.add(SparklinePoint(date = d, amount = amt))
                    }
                } catch (_: Exception) {}
            }
        }

        val txArray = json.optJSONArray("recent_transactions")
        val txList = mutableListOf<TransactionItem>()
        if (txArray != null) {
            for (i in 0 until txArray.length()) {
                try {
                    val item = txArray.optJSONObject(i) ?: continue
                    val amtRaw = if (item.isNull("amount")) 0.0 else item.optDouble("amount", 0.0)
                    val amt = if (amtRaw.isNaN() || amtRaw.isInfinite()) 0.0 else amtRaw
                    val ch = item.optString("channel").takeIf { it.isNotBlank() && it != "null" }
                    txList.add(
                        TransactionItem(
                            id = item.optLong("id", 0L),
                            date = item.optString("date", "").takeIf { it != "null" } ?: "",
                            type = item.optString("type", "expense").takeIf { it != "null" } ?: "expense",
                            category = item.optString("category", "其他").takeIf { it != "null" } ?: "其他",
                            amount = amt,
                            note = item.optString("note", "").takeIf { it != "null" } ?: "",
                            source = item.optString("source", "unknown").takeIf { it != "null" } ?: "unknown",
                            channel = ch,
                            badgeText = item.optString("badge_text", ""),
                            badgeEmoji = item.optString("badge_emoji", ""),
                            badgeColor = item.optString("badge_color", ""),
                            isEdited = item.optBoolean("is_edited", false)
                        )
                    )
                } catch (_: Exception) {}
            }
        }

        val listeningObj = json.optJSONObject("listening_status")
        val lastActive = listeningObj?.optString("last_active_time")?.takeIf { it.isNotBlank() && it != "null" } ?: "今天"

        val uncatArray = json.optJSONArray("uncategorized_items")
        val uncatList = mutableListOf<UncategorizedItem>()
        if (uncatArray != null) {
            for (i in 0 until uncatArray.length()) {
                try {
                    val item = uncatArray.optJSONObject(i) ?: continue
                    val isPending = item.optBoolean("is_pending_on_hold", false)
                    val srcType = item.optString("source_type", if (isPending) "pending" else "transaction").takeIf { it != "null" } ?: "transaction"
                    val reviewReason = item.optString("review_reason", if (isPending) "on_hold_transfer" else "unclear_category").takeIf { it != "null" } ?: "unclear_category"
                    val onHoldReason = item.optString("on_hold_reason", "").takeIf { it != "null" } ?: ""
                    val dupObj = item.optJSONObject("suspected_duplicate")
                    val dupId = if (item.has("suspected_duplicate_id") && !item.isNull("suspected_duplicate_id")) item.optLong("suspected_duplicate_id") else null
                    val amtRaw = if (item.isNull("amount")) 0.0 else item.optDouble("amount", 0.0)
                    val amt = if (amtRaw.isNaN() || amtRaw.isInfinite()) 0.0 else amtRaw

                    val dupAmt = if (dupObj != null && dupObj.has("amount") && !dupObj.isNull("amount")) {
                        val dRaw = dupObj.optDouble("amount", 0.0)
                        if (dRaw.isNaN() || dRaw.isInfinite()) null else dRaw
                    } else null

                    uncatList.add(
                        UncategorizedItem(
                            id = item.optLong("id", 0L),
                            date = item.optString("date", "").takeIf { it != "null" } ?: "",
                            amount = amt,
                            note = item.optString("note", "").takeIf { it != "null" } ?: "",
                            category = item.optString("category", "待分类").takeIf { it != "null" } ?: "待分类",
                            isPendingOnHold = isPending,
                            sourceType = srcType,
                            reviewReason = reviewReason,
                            onHoldReason = onHoldReason,
                            suspectedDuplicateId = dupId,
                            suspectedDuplicateNote = dupObj?.optString("note")?.takeIf { it != "null" },
                            suspectedDuplicateAmount = dupAmt,
                            suspectedDuplicateCategory = dupObj?.optString("category")?.takeIf { it != "null" },
                            suspectedDuplicateDate = dupObj?.optString("date")?.takeIf { it != "null" }
                        )
                    )
                } catch (_: Exception) {}
            }
        }

        val catArray = json.optJSONArray("categories")
        val catList = mutableListOf<String>()
        if (catArray != null) {
            for (i in 0 until catArray.length()) {
                try {
                    val c = catArray.optString(i)
                    if (c.isNotBlank() && c != "null") catList.add(c)
                } catch (_: Exception) {}
            }
        }

        OverviewData(
            totalSpending = totalSpending,
            expenseAmount = expenseAmount,
            transferAmount = transferAmount,
            uncategorizedCount = uncategorizedCount,
            uncategorizedAmount = uncategorizedAmount,
            uncategorizedItems = uncatList,
            categories = catList,
            sparkline = sparkList,
            recentTransactions = txList,
            lastActiveTime = lastActive,
            isListening = isListeningActual,
            isOfflineCache = isOfflineCache
        )
    } catch (_: Exception) {
        OverviewData(
            lastActiveTime = "刚刚",
            isListening = isListeningActual,
            isOfflineCache = isOfflineCache
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    showBottomBar: Boolean = false,
    onNavigateToRecords: () -> Unit = {},
    onNavigateToAdd: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToTools: () -> Unit = {},
    onScanReceipt: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNavigateToWeb: () -> Unit = onNavigateToRecords,
    onQuickAdd: () -> Unit = onNavigateToAdd,
    onNavigateToCashflow: (String) -> Unit = {},
    cashflowRevision: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var period by remember { mutableStateOf("month") } // "month", "3m", "year"
    val calendar = remember { Calendar.getInstance() }
    var currentMonthStr by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time))
    }
    val currentLang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(context)
    val displayRangeLabel = remember(period, currentMonthStr, currentLang) {
        when (period) {
            "month" -> com.siangloh.ledger.util.AppLanguageManager.formatMonth(calendar, currentLang)
            "3m" -> com.siangloh.ledger.util.AppLanguageManager.format3mRange(calendar, currentLang)
            "year" -> com.siangloh.ledger.util.AppLanguageManager.formatYearRange(calendar, currentLang)
            else -> currentMonthStr
        }
    }

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isListeningActual by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    val initialCache = remember {
        val username = NetworkHelper.getUsername(context)
        val cached = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, period, currentMonthStr)
        if (!cached.isNullOrBlank()) {
            try {
                parseOverviewJson(cached, isListeningActual, isOfflineCache = true)
            } catch (_: Exception) { null }
        } else null
    }
    var overviewData by remember { mutableStateOf(initialCache ?: OverviewData()) }
    var isLoading by remember { mutableStateOf(initialCache == null) }
    var hasNetworkError by remember { mutableStateOf(false) }
    var networkErrorMessage by remember { mutableStateOf("") }

    // Quick Categorize & Uncategorized List Sheet States
    var showCategorizeDialog by remember { mutableStateOf(false) }
    var showUncategorizedSheet by remember { mutableStateOf(false) }
    var selectedTxForCategorize by remember { mutableStateOf<TransactionItem?>(null) }

    // U5: 在 ON_RESUME 生命周期动态感知并实时刷新悬浮窗权限与通知监听权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                isListeningActual = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun prefetchAdjacentOverview(targetMonth: String, currentPeriod: String) {
        if (currentPeriod != "month") return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val username = NetworkHelper.getUsername(context)
                val serverUrl = NetworkHelper.getServerUrl(context)
                val apiKey = NetworkHelper.getApiKey(context)
                val prevM = shiftMonthString(targetMonth, -1)
                val nextM = shiftMonthString(targetMonth, 1)

                for (adjMonth in listOf(prevM, nextM)) {
                    val existing = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, currentPeriod, adjMonth)
                    if (existing.isNullOrBlank()) {
                        val queryParams = mutableListOf("period=$currentPeriod", "month=$adjMonth")
                        if (username.isNotEmpty()) queryParams.add("username=$username")
                        val urlStr = "$serverUrl/api/native/overview?" + queryParams.joinToString("&")
                        val url = URL(urlStr)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "LedgerAppCompanion/1.0 (Android; Dalvik)")
                        if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                        conn.connectTimeout = 6000
                        conn.readTimeout = 6000
                        if (conn.responseCode in 200..299) {
                            val text = conn.inputStream.bufferedReader().use { it.readText() }
                            com.siangloh.ledger.data.LocalDataStore.saveOverview(context, username, currentPeriod, adjMonth, text)
                        }
                        conn.disconnect()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun refreshData(forceRefresh: Boolean = false) {
        val username = NetworkHelper.getUsername(context)
        val todayMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val isFutureMonth = period == "month" && currentMonthStr > todayMonthStr

        // 1. 未来月份：天然 0.00 空态，严禁请求网络（除非用户强制刷新）
        if (isFutureMonth) {
            overviewData = OverviewData(lastActiveTime = "刚刚", isListening = isListeningActual)
            isLoading = false
            hasNetworkError = false
            networkErrorMessage = ""
            if (!forceRefresh) return
        }

        val cachedJson = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, period, currentMonthStr)
        val isFresh = com.siangloh.ledger.data.LocalDataStore.isOverviewFresh(context, username, period, currentMonthStr)

        // 2. 本地缓存命中：立即呈现
        if (!cachedJson.isNullOrBlank()) {
            try {
                overviewData = parseOverviewJson(cachedJson, isListeningActual, isOfflineCache = !isFresh)
                isLoading = false
                hasNetworkError = false
                networkErrorMessage = ""
                // 核心：若非用户主动点击刷新，只要本地有缓存，100% 消费本地快照，直接 return 绝不访问网络！
                if (!forceRefresh) {
                    return
                }
            } catch (_: Exception) {
                isLoading = true
            }
        } else {
            // 切换时期（如切换到 近3个月 或 年度）未命中缓存时：
            // 绝不能直接把 overviewData 清空！保留已有状态，仅在后台发起拉取，避免界面突兀跳动
            isLoading = true
        }

        coroutineScope.launch {
            try {
                val serverUrl = NetworkHelper.getServerUrl(context)
                val apiKey = NetworkHelper.getApiKey(context)

                val queryParams = mutableListOf("period=$period", "month=$currentMonthStr")
                if (username.isNotEmpty()) queryParams.add("username=$username")
                val urlStr = "$serverUrl/api/native/overview?" + queryParams.joinToString("&")

                val result = withContext(Dispatchers.IO) {
                    try {
                        val url = URL(urlStr)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "LedgerAppCompanion/1.0 (Android; Dalvik)")
                        if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000

                        if (conn.responseCode in 200..299) {
                            val text = conn.inputStream.bufferedReader().use { it.readText() }
                            conn.disconnect()
                            text
                        } else {
                            conn.disconnect()
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (result != null) {
                    try {
                        com.siangloh.ledger.data.LocalDataStore.saveOverview(context, username, period, currentMonthStr, result)
                    } catch (_: Exception) {}
                    val parsed = withContext(Dispatchers.Default) {
                        parseOverviewJson(result, isListeningActual, isOfflineCache = false)
                    }
                    overviewData = parsed
                    hasNetworkError = false
                    networkErrorMessage = ""
                    // 异步双向预取相邻月份（上一月与下一月），提前预热缓存，彻底消除后续切月 delay
                    if (period == "month") {
                        prefetchAdjacentOverview(currentMonthStr, period)
                    }
                } else {
                    val cachedJsonRetry = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, period, currentMonthStr)
                    if (!cachedJsonRetry.isNullOrBlank()) {
                        val parsed = withContext(Dispatchers.Default) {
                            parseOverviewJson(cachedJsonRetry, isListeningActual, isOfflineCache = true)
                        }
                        overviewData = parsed
                        hasNetworkError = true
                        networkErrorMessage = "网络连接受阻，已呈现最近快照数据"
                    } else if (isFutureMonth) {
                        overviewData = OverviewData(lastActiveTime = "刚刚", isListening = isListeningActual)
                        hasNetworkError = false
                        networkErrorMessage = ""
                    } else {
                        hasNetworkError = true
                        // 无网络且无缓存：回退至本地 Room 未同步队列
                        val db = AppDatabase.getInstance(context)
                        val offlineTxs = withContext(Dispatchers.IO) {
                            db.pendingTransactionDao().getPendingTransactions()
                        }
                        val txList = offlineTxs.map {
                            TransactionItem(
                                id = it.id,
                                date = it.date,
                                type = it.type,
                                category = it.category,
                                amount = it.amount,
                                note = it.note,
                                source = it.source,
                                isOfflineLocal = true
                            )
                        }
                        val total = txList.sumOf { it.amount }
                        overviewData = OverviewData(
                            totalSpending = total,
                            expenseAmount = total,
                            recentTransactions = txList,
                            lastActiveTime = "离线本地缓存",
                            isListening = isListeningActual,
                            isOfflineCache = true
                        )
                        networkErrorMessage = "网络未连接，且尚无历史缓存"
                    }
                }
            } catch (e: Exception) {
                if (isFutureMonth) {
                    overviewData = OverviewData(lastActiveTime = "刚刚", isListening = isListeningActual)
                    hasNetworkError = false
                } else {
                    hasNetworkError = true
                    val cachedJsonRetry = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, period, currentMonthStr)
                    if (!cachedJsonRetry.isNullOrBlank()) {
                        val parsed = withContext(Dispatchers.Default) {
                            parseOverviewJson(cachedJsonRetry, isListeningActual, isOfflineCache = true)
                        }
                        overviewData = parsed
                        networkErrorMessage = "网络连接异常，已呈现最近快照数据"
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(period, currentMonthStr) {
        refreshData(forceRefresh = false)
    }

    // 监听后台定期全量同步广播 (DATA_SYNCED) 与同步队列完成广播 (SYNC_COMPLETED)，静默从本地存储更新 UI
    DisposableEffect(period, currentMonthStr) {
        val filter = android.content.IntentFilter().apply {
            addAction("com.siangloh.ledger.DATA_SYNCED")
            addAction("com.siangloh.ledger.SYNC_COMPLETED")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val username = NetworkHelper.getUsername(context)
                val newCachedJson = com.siangloh.ledger.data.LocalDataStore.getOverview(context, username, period, currentMonthStr)
                if (!newCachedJson.isNullOrBlank()) {
                    try {
                        overviewData = parseOverviewJson(newCachedJson, isListeningActual, isOfflineCache = false)
                        isLoading = false
                    } catch (_: Exception) {}
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        containerColor = Slate50,
        bottomBar = {
            if (showBottomBar) {
                NativeBottomBar(
                    onHome = { refreshData() },
                    onAdd = onNavigateToAdd,
                    onScan = onScanReceipt,
                    onRecords = onNavigateToRecords
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // 1. Status Bar Header (● Listening 状态与待分类角标)
                ListeningStatusHeader(
                    isListening = overviewData.isListening,
                    lastActive = overviewData.lastActiveTime,
                    isOfflineCache = overviewData.isOfflineCache,
                    toReviewCount = overviewData.uncategorizedCount,
                    hasOverlayPermission = hasOverlayPermission,
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRefresh = { refreshData(forceRefresh = true) },
                    onSettings = onOpenSettings
                )
            }

            if (hasNetworkError) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFEF3C7),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { refreshData(forceRefresh = true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.network_error_cached),
                                color = Color(0xFF92400E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.network_retry),
                                tint = Color(0xFF92400E),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            item {
                // 2. Period Filter Segmented Control & Month Navigation (Two-row responsive design with >=48dp touch targets)
                PeriodSelector(
                    period = period,
                    rangeLabel = displayRangeLabel,
                    onPeriodChanged = { newPeriod -> period = newPeriod },
                    onPrevMonth = {
                        calendar.add(Calendar.MONTH, -1)
                        currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
                    },
                    onNextMonth = {
                        calendar.add(Calendar.MONTH, 1)
                        currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
                    }
                )
            }

            // 3. 内容区局部动态数据：仅在数据为空且加载中时，对特定组件展示骨架屏！
            // 顶栏状态与周期切换 Tab 始终保持可见可交互，杜绝整屏骨架白闪！
            if (isLoading && overviewData.recentTransactions.isEmpty() && overviewData.totalSpending == 0.0) {
                item {
                    SpendingHeroCardSkeleton()
                }
                item {
                    SpendingTrendSkeleton()
                }
                item {
                    RecentTransactionsSkeleton()
                }
            } else {
                if (period == "month") {
                    item { CashflowSummaryCard(currentMonthStr, cashflowRevision) { onNavigateToCashflow(currentMonthStr) } }
                } else {
                    item {
                        SpendingHeroCard(
                            totalSpending = overviewData.totalSpending,
                            expenseAmount = overviewData.expenseAmount,
                            transferAmount = overviewData.transferAmount
                        )
                    }
                }

                if (overviewData.uncategorizedCount > 0) {
                    item {
                        // 4. Inbox Zero Banner (U3: 点击唤出待整理与待确认转账全量面板)
                        UncategorizedInboxBanner(
                            count = overviewData.uncategorizedCount,
                            amount = overviewData.uncategorizedAmount,
                            onClick = {
                                showUncategorizedSheet = true
                            }
                        )
                    }
                }

                if (overviewData.sparkline.isNotEmpty()) {
                    item {
                        // 5. Spending Trend Sparkline
                        SpendingTrendSection(
                            sparkline = overviewData.sparkline,
                            total = overviewData.totalSpending
                        )
                    }
                }

                item {
                    // 6. Recent Transactions Section Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.recent_transactions),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToRecords() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.view_all_records),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryTeal
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = PrimaryTeal
                            )
                        }
                    }
                }

                // 7. Recent Transactions List Items
                if (overviewData.recentTransactions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isLoading) "..." else stringResource(R.string.no_transactions_yet),
                                color = Slate400,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(overviewData.recentTransactions) { tx ->
                        TransactionCardItem(
                            tx = tx,
                            onClick = {
                                selectedTxForCategorize = tx
                                showCategorizeDialog = true
                            }
                        )
                    }
                }

                if (overviewData.recentTransactions.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = onNavigateToRecords,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.3f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = PrimaryTeal
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.view_all_records_detail),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryTeal
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // U3: Uncategorized Items Review Bottom Sheet
    if (showUncategorizedSheet) {
        val displayItems = if (overviewData.uncategorizedItems.isNotEmpty()) {
            overviewData.uncategorizedItems
        } else {
            overviewData.recentTransactions.filter {
                it.category in listOf("其他", "未分类", "待分类", "")
            }.map {
                UncategorizedItem(
                    id = it.id,
                    date = it.date,
                    amount = it.amount,
                    note = it.note,
                    category = it.category,
                    isPendingOnHold = false,
                    sourceType = "transaction"
                )
            }
        }

        UncategorizedReviewBottomSheet(
            items = displayItems,
            onDismiss = { showUncategorizedSheet = false },
            onSelectForCategorize = { tx ->
                showUncategorizedSheet = false
                selectedTxForCategorize = tx
                showCategorizeDialog = true
            },
            onResolvePending = { pendingId, action ->
                coroutineScope.launch {
                    val serverUrl = NetworkHelper.getServerUrl(context)
                    val apiKey = NetworkHelper.getApiKey(context)
                    val username = NetworkHelper.getUsername(context)
                    var isSuccess = false
                    var msg = ""

                    withContext(Dispatchers.IO) {
                        try {
                            val url = URL("$serverUrl/api/pending-transactions/$pendingId/$action")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 6000
                            conn.readTimeout = 6000
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                            conn.doOutput = true
                            val payload = JSONObject().apply {
                                if (apiKey.isNotEmpty()) put("key", apiKey)
                                if (username.isNotEmpty()) put("username", username)
                            }.toString()
                            conn.outputStream.bufferedWriter().use { it.write(payload) }
                            if (conn.responseCode in 200..299) {
                                val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                                val respJson = JSONObject(respStr)
                                isSuccess = respJson.optBoolean("ok", false)
                                msg = respJson.optString("message", "操作完成")
                            } else {
                                msg = "HTTP ${conn.responseCode}"
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            msg = e.message ?: "网络异常"
                        }
                    }

                    if (isSuccess) {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        refreshData(forceRefresh = true)
                    } else {
                        Toast.makeText(context, "操作失败: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Quick Categorize Dialog
    if (showCategorizeDialog && selectedTxForCategorize != null) {
        val tx = selectedTxForCategorize!!
        QuickCategoryBottomSheet(
            transaction = tx,
            availableCategories = overviewData.categories,
            onDismiss = {
                showCategorizeDialog = false
                selectedTxForCategorize = null
            },
            onConfirmCategory = { newCategory, remember ->
                showCategorizeDialog = false
                selectedTxForCategorize = null
                coroutineScope.launch {
                    if (tx.isOfflineLocal) {
                        // D3: 离线账单仅修改本地 Room 数据库，防止本地自增 ID 提交至服务端与他人账单碰撞
                        withContext(Dispatchers.IO) {
                            val db = AppDatabase.getInstance(context)
                            db.pendingTransactionDao().updateCategory(tx.id, newCategory)
                        }
                        Toast.makeText(context, "已更新离线账单分类为【$newCategory】", Toast.LENGTH_SHORT).show()
                        refreshData(forceRefresh = true)
                    } else {
                        val serverUrl = NetworkHelper.getServerUrl(context)
                        val apiKey = NetworkHelper.getApiKey(context)
                        val username = NetworkHelper.getUsername(context)

                        var isSuccess = false
                        var errorMsg = ""

                        withContext(Dispatchers.IO) {
                            try {
                                val url = URL("$serverUrl/api/native/quick-categorize")
                                val conn = url.openConnection() as HttpURLConnection
                                conn.connectTimeout = 5000
                                conn.readTimeout = 5000
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                                conn.doOutput = true
                                val payload = JSONObject().apply {
                                    put("key", apiKey)
                                    put("transaction_id", tx.id)
                                    put("category", newCategory)
                                    put("remember_merchant", remember)
                                    put("note", tx.note)
                                    if (username.isNotEmpty()) put("username", username)
                                }.toString()
                                conn.outputStream.bufferedWriter().use { it.write(payload) }
                                val code = conn.responseCode
                                if (code in 200..299) {
                                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                                    val respJson = JSONObject(respStr)
                                    isSuccess = respJson.optBoolean("ok", false)
                                    if (!isSuccess) {
                                        errorMsg = respJson.optString("message", "分类修改失败")
                                    }
                                } else {
                                    errorMsg = "HTTP $code"
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                errorMsg = e.message ?: "网络异常"
                            }
                        }
                        // U2: 真实校验结果再提示反馈
                        if (isSuccess) {
                            Toast.makeText(context, "已修改为【$newCategory】", Toast.LENGTH_SHORT).show()
                            refreshData(forceRefresh = true)
                        } else {
                            Toast.makeText(context, "保存失败: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun ListeningStatusHeader(
    isListening: Boolean,
    lastActive: String,
    isOfflineCache: Boolean,
    toReviewCount: Int,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var isOverlayDismissed by remember {
        mutableStateOf(
            context.getSharedPreferences("ledger_overview_prefs", Context.MODE_PRIVATE)
                .getBoolean("overlay_banner_dismissed", false)
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: 监听状态与同步时间 (清晰区分常驻服务与数据同步时间)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isListening) PrimaryTealSubtle else Slate100)
                    .clickable { onRefresh() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isListening) Emerald500.copy(alpha = pulseAlpha) else Slate400)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = if (isListening) stringResource(R.string.status_listening_active) else stringResource(R.string.status_listening_paused),
                        color = if (isListening) PrimaryTeal else Slate600,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isOfflineCache) "${stringResource(R.string.status_offline_prefix)} ${formatFriendlyTime(lastActive, context)}" else "${stringResource(R.string.status_synced_prefix)} · ${stringResource(R.string.sync_status_just_now)}",
                        color = Slate500,
                        fontSize = 10.sp
                    )
                }
            }

            // Right: To Review Count Badge + Settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (toReviewCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFEF3C7),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "⚠️ " + stringResource(R.string.pending_review_badge, toReviewCount),
                            color = Amber500,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Slate600,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (!hasOverlayPermission && !isOverlayDismissed) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PrimaryTealSubtle,
                border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onRequestOverlayPermission() }
                    ) {
                        Text(
                            text = stringResource(R.string.enable_overlay_banner),
                            color = PrimaryTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = {
                            isOverlayDismissed = true
                            context.getSharedPreferences("ledger_overview_prefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("overlay_banner_dismissed", true).apply()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(
    period: String,
    rangeLabel: String,
    onPeriodChanged: (String) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: 3 equal-width Segmented Tabs [ 月度 / Monthly / Bulanan ] [ 近3个月 / Last 3 months / 3 bulan terkini ] [ 年度 / Yearly / Tahunan ]
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Slate100,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    "month" to stringResource(R.string.period_monthly),
                    "3m" to stringResource(R.string.period_3m),
                    "year" to stringResource(R.string.period_yearly)
                )
                tabs.forEach { (key, label) ->
                    val isSelected = period == key
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (isSelected) Color.White else Color.Transparent,
                        shadowElevation = if (isSelected) 1.5.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onPeriodChanged(key) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) PrimaryTeal else Slate600,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Row 2: Independent date range row (min-height 48dp, touch targets >= 48dp)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = if (period == "month") Arrangement.SpaceBetween else Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (period == "month") {
                    IconButton(
                        onClick = onPrevMonth,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.prev_month),
                            tint = Slate700,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = rangeLabel,
                        color = Slate900,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = onNextMonth,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.next_month),
                            tint = Slate700,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (period == "3m") Icons.Default.DateRange else Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = rangeLabel,
                            color = Slate800,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpendingHeroCard(
    totalSpending: Double,
    expenseAmount: Double,
    transferAmount: Double
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.spending_title),
                    color = Slate400,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = stringResource(R.string.spending_subtitle_clean),
                    color = Slate500,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "MYR ${String.format(Locale.US, "%,.2f", totalSpending)}",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-metrics pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Emerald400)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${stringResource(R.string.expense_label)}: MYR ${String.format(Locale.US, "%,.2f", expenseAmount)}",
                        color = Slate200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Sky400)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${stringResource(R.string.transfer_label)}: MYR ${String.format(Locale.US, "%,.2f", transferAmount)}",
                        color = Slate200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun UncategorizedInboxBanner(
    count: Int,
    amount: Double,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFEF3C7),
        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFBBF24)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🏷️", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.uncategorized_banner_count, count),
                        color = Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.uncategorized_total_fmt, "MYR ${String.format(Locale.US, "%,.2f", amount)}"),
                        color = Color(0xFFB45309),
                        fontSize = 11.sp
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFB45309)
            ) {
                Text(
                    text = stringResource(R.string.uncategorized_action_go),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
fun SpendingTrendSection(
    sparkline: List<SparklinePoint>,
    total: Double
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val activePoint = selectedIndex?.let { if (it in sparkline.indices) sparkline[it] else null }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (activePoint != null) "${stringResource(R.string.trend_title)} · ${activePoint.date}" else stringResource(R.string.trend_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    if (activePoint != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.trend_drag_hint),
                            fontSize = 10.sp,
                            color = PrimaryTeal,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    text = if (activePoint != null) {
                        "MYR ${String.format(Locale.US, "%,.2f", activePoint.amount)}"
                    } else {
                        stringResource(R.string.trend_total_fmt, "MYR ${String.format(Locale.US, "%,.2f", total)}")
                    },
                    fontSize = if (activePoint != null) 14.sp else 12.sp,
                    fontWeight = if (activePoint != null) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (activePoint != null) PrimaryTeal else Slate600
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Native Canvas Sparkline with interactive touch / long-press inspection
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .pointerInput(sparkline) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (sparkline.size >= 2) {
                                    val step = size.width / (sparkline.size - 1)
                                    val idx = ((offset.x + step / 2) / step).toInt().coerceIn(0, sparkline.size - 1)
                                    selectedIndex = idx
                                }
                            },
                            onDrag = { change, _ ->
                                if (sparkline.size >= 2) {
                                    val step = size.width / (sparkline.size - 1)
                                    val idx = ((change.position.x + step / 2) / step).toInt().coerceIn(0, sparkline.size - 1)
                                    selectedIndex = idx
                                }
                            },
                            onDragEnd = {},
                            onDragCancel = {
                                selectedIndex = null
                            }
                        )
                    }
                    .pointerInput(sparkline) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (sparkline.size >= 2) {
                                    val step = size.width / (sparkline.size - 1)
                                    val idx = ((offset.x + step / 2) / step).toInt().coerceIn(0, sparkline.size - 1)
                                    selectedIndex = if (selectedIndex == idx) null else idx
                                }
                            },
                            onLongPress = { offset ->
                                if (sparkline.size >= 2) {
                                    val step = size.width / (sparkline.size - 1)
                                    val idx = ((offset.x + step / 2) / step).toInt().coerceIn(0, sparkline.size - 1)
                                    selectedIndex = idx
                                }
                            }
                        )
                    }
            ) {
                if (sparkline.size < 2) return@Canvas
                val maxAmt = sparkline.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 100.0
                val w = size.width
                val h = size.height

                val points = sparkline.mapIndexed { idx, pt ->
                    val x = idx * (w / (sparkline.size - 1))
                    val y = h - ((pt.amount / maxAmt) * (h * 0.82f)).toFloat() - 4.dp.toPx()
                    Offset(x, y)
                }

                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 0 until points.size - 1) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val cx = (p0.x + p1.x) / 2
                        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }
                }

                // Fill gradient underneath curve
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(points.last().x, h)
                    lineTo(points.first().x, h)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(PrimaryTeal.copy(alpha = 0.25f), Color.Transparent)
                    )
                )

                // Line stroke
                drawPath(
                    path = path,
                    color = PrimaryTeal,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Draw inspection indicator if point selected
                selectedIndex?.let { idx ->
                    if (idx in points.indices) {
                        val pt = points[idx]
                        // Vertical guideline
                        drawLine(
                            color = PrimaryTeal.copy(alpha = 0.6f),
                            start = Offset(pt.x, 0f),
                            end = Offset(pt.x, h),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                        // Outer halo
                        drawCircle(
                            color = PrimaryTeal.copy(alpha = 0.22f),
                            radius = 9.dp.toPx(),
                            center = pt
                        )
                        // Middle solid point
                        drawCircle(
                            color = PrimaryTeal,
                            radius = 4.5.dp.toPx(),
                            center = pt
                        )
                        // Inner core
                        drawCircle(
                            color = Color.White,
                            radius = 2.dp.toPx(),
                            center = pt
                        )
                    }
                }
            }

            if (activePoint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📅 ${activePoint.date}",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                    Text(
                        text = "点击图表可取消固定",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }
        }
    }
}

@Composable
fun TransactionCardItem(
    tx: TransactionItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val emoji = CategoryUiHelper.getCategoryEmoji(tx.category, tx.type)
    val localizedCategory = CategoryUiHelper.getLocalizedCategoryName(context, tx.category)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Slate100),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = tx.note.ifBlank { localizedCategory },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1. 来源与渠道专属 Brand Badge (Maybank / TNG / PB / Grab / 手动 等)
                        SourceBrandBadge(
                            source = tx.source,
                            channel = tx.channel,
                            note = tx.note,
                            isEdited = tx.isEdited,
                            fallbackBadgeText = tx.badgeText,
                            fallbackBadgeEmoji = tx.badgeEmoji,
                            fallbackBadgeColor = tx.badgeColor
                        )

                        // 2. 分类微胶囊
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100
                        ) {
                            Text(
                                text = localizedCategory,
                                color = Slate600,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // 3. 日期
                        Text(
                            text = tx.date,
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Text(
                text = "${if (tx.type == "income") "+" else "-"}MYR ${String.format(Locale.US, "%,.2f", tx.amount)}",
                color = if (tx.type == "income") Emerald600 else Slate900,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun NativeBottomBar(
    onHome: () -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onRecords: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color.White,
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Default.Dashboard,
                label = "概览",
                tint = PrimaryTeal,
                selected = true,
                onClick = onHome,
                modifier = Modifier.weight(1f)
            )

            BottomNavItem(
                icon = Icons.Default.AddCircle,
                label = "记一笔",
                tint = Slate700,
                selected = false,
                onClick = onAdd,
                modifier = Modifier.weight(1f)
            )

            BottomNavItem(
                icon = Icons.Default.CameraAlt,
                label = "拍小票",
                tint = Slate700,
                selected = false,
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )

            BottomNavItem(
                icon = Icons.Default.ReceiptLong,
                label = "流水明细",
                tint = Slate700,
                selected = false,
                onClick = onRecords,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCategoryBottomSheet(
    transaction: TransactionItem,
    availableCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmCategory: (String, Boolean) -> Unit
) {
    var selectedCat by remember { mutableStateOf(transaction.category.ifBlank { "餐饮" }) }
    var rememberRule by remember { mutableStateOf(true) }

    val emojiMap = mapOf(
        "餐饮" to "🍔", "交通" to "🚗", "杂货" to "🛒", "账单" to "💡",
        "购物" to "🛍️", "娱乐" to "🎮", "医疗" to "💊", "转账" to "🔄",
        "工资" to "💰", "理财" to "📈", "教育" to "📚", "居家" to "🏠",
        "旅行" to "✈️", "数码" to "📱", "其他" to "🏷️"
    )

    val allCategories = remember(availableCategories, transaction.category) {
        val list = mutableListOf<String>()
        if (availableCategories.isNotEmpty()) {
            list.addAll(availableCategories)
        } else {
            list.addAll(InstantReviewManager.COMMON_CATEGORIES.map { it.first })
        }
        if (transaction.category.isNotBlank() && !list.contains(transaction.category)) {
            list.add(transaction.category)
        }
        if (!list.contains("其他")) {
            list.add("其他")
        }
        list.distinct()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "修改交易分类",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${transaction.note.ifBlank { "未知商户" }} · MYR ${String.format("%.2f", transaction.amount)}",
                fontSize = 13.sp,
                color = Slate600
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Category Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allCategories.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { catName ->
                            val isSelected = catName == selectedCat
                            val emoji = emojiMap[catName] ?: "🏷️"
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Emerald500 else Slate100,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedCat = catName }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = emoji, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = catName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Slate700,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (rowItems.size < 4) {
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { rememberRule = !rememberRule }
            ) {
                Checkbox(
                    checked = rememberRule,
                    onCheckedChange = { rememberRule = it },
                    colors = CheckboxDefaults.colors(checkedColor = Emerald500)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "以后该商户所有消费自动归为此分类 (商户记忆)",
                    fontSize = 13.sp,
                    color = Slate700
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onConfirmCategory(selectedCat, rememberRule) },
                colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("保存分类", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// =============================================================================
// U3: 待整理全量列表与待确认转账审查抽屉 (Uncategorized Review Bottom Sheet)
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UncategorizedReviewBottomSheet(
    items: List<UncategorizedItem>,
    onDismiss: () -> Unit,
    onSelectForCategorize: (TransactionItem) -> Unit,
    onResolvePending: (Long, String) -> Unit // pendingId, action ("confirm" | "ignore" | "transfer")
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.uncategorized_sheet_title, items.size),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = stringResource(R.string.uncategorized_sheet_desc),
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stringResource(R.string.uncategorized_inbox_zero), color = Slate400, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Amber50,
                                                border = BorderStroke(1.dp, Amber200),
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.pending_reason_duplicate),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Amber700,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (item.reviewReason == "unmatched_account") {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFFF3E8FF),
                                                border = BorderStroke(1.dp, Color(0xFFD8B4FE)),
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.pending_reason_account),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF7E22CE),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = item.note.ifBlank { if (item.isPendingOnHold) stringResource(R.string.pending_hold_title) else stringResource(R.string.unnamed_expense) },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate800,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${item.date} · ${if (item.isPendingOnHold) stringResource(R.string.pending_transfer_status) else CategoryUiHelper.getLocalizedCategoryName(context, item.category)}",
                                            fontSize = 12.sp,
                                            color = if (item.isPendingOnHold) Amber600 else Slate500
                                        )
                                    }
                                    Text(
                                        text = "MYR ${String.format(Locale.US, "%,.2f", item.amount)}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (item.isPendingOnHold) Amber600 else Rose500
                                    )
                                }

                                if (item.suspectedDuplicateId != null || item.reviewReason == "suspected_duplicate") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Slate100,
                                        border = BorderStroke(1.dp, Slate200),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            val dupAmtStr = item.suspectedDuplicateAmount?.let { String.format(Locale.US, "%.2f", it) } ?: String.format(Locale.US, "%.2f", item.amount)
                                            val dupNoteStr = item.suspectedDuplicateNote?.ifBlank { "交易" } ?: "交易"
                                            Text(
                                                text = stringResource(R.string.pending_ref_tx, item.suspectedDuplicateId ?: 0L, dupNoteStr, dupAmtStr),
                                                fontSize = 11.sp,
                                                color = Slate600
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (item.isPendingOnHold || item.sourceType == "pending") {
                                    if (item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { onResolvePending(item.id, "merge") },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.pending_btn_merge), fontSize = 12.sp, color = Color.White)
                                            }
                                            OutlinedButton(
                                                onClick = { onResolvePending(item.id, "mark-not-duplicate") },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.pending_btn_not_duplicate), fontSize = 12.sp, color = Slate700)
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = stringResource(R.string.pending_hold_help),
                                            fontSize = 11.sp,
                                            color = Slate500,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { onResolvePending(item.id, "confirm") },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.btn_record_expense), fontSize = 12.sp, color = Color.White)
                                            }
                                            OutlinedButton(
                                                onClick = { onResolvePending(item.id, "ignore") },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.btn_self_transfer), fontSize = 12.sp, color = Slate700)
                                            }
                                            OutlinedButton(
                                                onClick = { onResolvePending(item.id, "transfer") },
                                                modifier = Modifier.weight(1f).height(48.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.btn_internal_allocate), fontSize = 12.sp, color = Slate700)
                                            }
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            onSelectForCategorize(
                                                TransactionItem(
                                                    id = item.id,
                                                    date = item.date,
                                                    type = "expense",
                                                    category = item.category,
                                                    amount = item.amount,
                                                    note = item.note,
                                                    source = "inbox_review"
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(stringResource(R.string.btn_select_category), fontSize = 13.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
