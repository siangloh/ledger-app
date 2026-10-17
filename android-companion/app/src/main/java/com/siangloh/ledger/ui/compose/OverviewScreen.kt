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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AppDatabase
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

data class TransactionItem(
    val id: Long,
    val date: String,
    val type: String,
    val category: String,
    val amount: Double,
    val note: String,
    val source: String,
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
    val isPendingOnHold: Boolean = false
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

fun formatFriendlyTime(rawIso: String): String {
    if (rawIso.isBlank() || rawIso == "无近期记录" || rawIso == "离线本地缓存" || rawIso == "守护中" || rawIso == "刚刚") {
        return rawIso.ifBlank { "刚刚" }
    }
    return try {
        val cleanStr = rawIso.replace('T', ' ').substringBefore('.')
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(cleanStr) ?: return rawIso
        val diffMs = System.currentTimeMillis() - date.time
        val diffMinutes = diffMs / (60 * 1000)
        when {
            diffMinutes < 1 -> "刚刚"
            diffMinutes < 60 -> "${diffMinutes}分钟前"
            else -> {
                val cal = Calendar.getInstance().apply { time = date }
                val nowCal = Calendar.getInstance()
                val isToday = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                              cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
                if (isToday) {
                    SimpleDateFormat("今天 HH:mm", Locale.getDefault()).format(date)
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
    val json = JSONObject(result)
    val totalSpending = json.optDouble("total_spending", 0.0)
    val expenseAmount = json.optDouble("expense_amount", 0.0)
    val transferAmount = json.optDouble("transfer_amount", 0.0)
    val uncategorizedCount = json.optInt("uncategorized_count", 0)
    val uncategorizedAmount = json.optDouble("uncategorized_amount", 0.0)

    val sparkArray = json.optJSONArray("sparkline")
    val sparkList = mutableListOf<SparklinePoint>()
    if (sparkArray != null) {
        for (i in 0 until sparkArray.length()) {
            val item = sparkArray.getJSONObject(i)
            sparkList.add(
                SparklinePoint(
                    date = item.optString("date"),
                    amount = item.optDouble("amount")
                )
            )
        }
    }

    val txArray = json.optJSONArray("recent_transactions")
    val txList = mutableListOf<TransactionItem>()
    if (txArray != null) {
        for (i in 0 until txArray.length()) {
            val item = txArray.getJSONObject(i)
            txList.add(
                TransactionItem(
                    id = item.optLong("id"),
                    date = item.optString("date"),
                    type = item.optString("type"),
                    category = item.optString("category"),
                    amount = item.optDouble("amount"),
                    note = item.optString("note"),
                    source = item.optString("source")
                )
            )
        }
    }

    val listeningObj = json.optJSONObject("listening_status")
    val lastActive = listeningObj?.optString("last_active_time") ?: "今天"

    val uncatArray = json.optJSONArray("uncategorized_items")
    val uncatList = mutableListOf<UncategorizedItem>()
    if (uncatArray != null) {
        for (i in 0 until uncatArray.length()) {
            val item = uncatArray.getJSONObject(i)
            uncatList.add(
                UncategorizedItem(
                    id = item.optLong("id"),
                    date = item.optString("date"),
                    amount = item.optDouble("amount"),
                    note = item.optString("note"),
                    category = item.optString("category"),
                    isPendingOnHold = item.optBoolean("is_pending_on_hold", false)
                )
            )
        }
    }

    val catArray = json.optJSONArray("categories")
    val catList = mutableListOf<String>()
    if (catArray != null) {
        for (i in 0 until catArray.length()) {
            val c = catArray.optString(i)
            if (c.isNotBlank()) catList.add(c)
        }
    }

    return OverviewData(
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onNavigateToWeb: () -> Unit,
    onQuickAdd: () -> Unit,
    onScanReceipt: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var period by remember { mutableStateOf("month") } // "month", "3m", "year"
    val calendar = remember { Calendar.getInstance() }
    var currentMonthStr by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time))
    }
    var displayMonthLabel by remember {
        mutableStateOf(SimpleDateFormat("yyyy年M月", Locale.CHINESE).format(calendar.time))
    }

    var overviewData by remember { mutableStateOf(OverviewData()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasNetworkError by remember { mutableStateOf(false) }
    var networkErrorMessage by remember { mutableStateOf("") }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isListeningActual by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

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

    fun refreshData() {
        isLoading = true
        coroutineScope.launch {
            try {
                val serverUrl = NetworkHelper.getServerUrl(context)
                val apiKey = NetworkHelper.getApiKey(context)
                val username = NetworkHelper.getUsername(context)

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

                val cachePrefs = context.getSharedPreferences("ledger_overview_cache", Context.MODE_PRIVATE)
                val cacheKey = "cache_${username}_${period}_${currentMonthStr}"

                if (result != null) {
                    try {
                        cachePrefs.edit().putString(cacheKey, result).apply()
                    } catch (_: Exception) {}
                    overviewData = parseOverviewJson(result, isListeningActual, isOfflineCache = false)
                    hasNetworkError = false
                    networkErrorMessage = ""
                } else {
                    hasNetworkError = true
                    val cachedJson = cachePrefs.getString(cacheKey, null)
                    if (!cachedJson.isNullOrBlank()) {
                        overviewData = parseOverviewJson(cachedJson, isListeningActual, isOfflineCache = true)
                        networkErrorMessage = "网络连接受阻，已呈现最近快照数据"
                    } else {
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
                hasNetworkError = true
                val cachePrefs = context.getSharedPreferences("ledger_overview_cache", Context.MODE_PRIVATE)
                val username = NetworkHelper.getUsername(context)
                val cacheKey = "cache_${username}_${period}_${currentMonthStr}"
                val cachedJson = cachePrefs.getString(cacheKey, null)
                if (!cachedJson.isNullOrBlank()) {
                    overviewData = parseOverviewJson(cachedJson, isListeningActual, isOfflineCache = true)
                    networkErrorMessage = "网络连接异常，已呈现最近快照数据"
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(period, currentMonthStr) {
        refreshData()
    }

    Scaffold(
        containerColor = Slate50,
        bottomBar = {
            NativeBottomBar(
                onHome = { refreshData() },
                onAdd = onQuickAdd,
                onScan = onScanReceipt,
                onWeb = onNavigateToWeb
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                // 1. Status Bar Header (● Listening 状态与待分类角标)
                ListeningStatusHeader(
                    isListening = overviewData.isListening,
                    lastActive = overviewData.lastActiveTime,
                    toReviewCount = overviewData.uncategorizedCount,
                    hasOverlayPermission = hasOverlayPermission,
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRefresh = { refreshData() },
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
                            .clickable { refreshData() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️ 网络暂不可用，已呈现最近快照 · 点击重试",
                                color = Color(0xFF92400E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = Color(0xFF92400E),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            item {
                // 2. Period Filter Segmented Control & Month Navigation
                PeriodSelector(
                    period = period,
                    displayMonth = displayMonthLabel,
                    onPeriodChanged = { newPeriod -> period = newPeriod },
                    onPrevMonth = {
                        calendar.add(Calendar.MONTH, -1)
                        currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
                        displayMonthLabel = SimpleDateFormat("yyyy年M月", Locale.CHINESE).format(calendar.time)
                    },
                    onNextMonth = {
                        calendar.add(Calendar.MONTH, 1)
                        currentMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
                        displayMonthLabel = SimpleDateFormat("yyyy年M月", Locale.CHINESE).format(calendar.time)
                    }
                )
            }

            item {
                // 3. ExpenseMY Style Dark Hero Card
                SpendingHeroCard(
                    totalSpending = overviewData.totalSpending,
                    expenseAmount = overviewData.expenseAmount,
                    transferAmount = overviewData.transferAmount
                )
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
                        text = "近期流水明细",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "向左滑快捷修改",
                        fontSize = 12.sp,
                        color = Slate400
                    )
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
                            text = if (isLoading) "正在同步最新财务数据..." else "本月暂无记录，付完钱账将自动记入",
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
                    isPendingOnHold = false
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
                        refreshData()
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
                        refreshData()
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
                            refreshData()
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
    toReviewCount: Int,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
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
            // Left: Listening Pulse Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE6F4EA))
                    .clickable { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Emerald500.copy(alpha = if (isListening) pulseAlpha else 0.4f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isListening) {
                        "● 实时监听中 · ${formatFriendlyTime(lastActive)}"
                    } else "● 未连接",
                    color = if (isListening) Emerald600 else Slate500,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Right: To Review Count Badge + Settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (toReviewCount > 0) Color(0xFFFEF3C7) else Slate100,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = if (toReviewCount > 0) "⚠️ $toReviewCount 待整理" else "0 待整理",
                        color = if (toReviewCount > 0) Amber500 else Slate600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
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

        if (!hasOverlayPermission) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRequestOverlayPermission() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ 开启付款悬浮识别卡片 (仿 ExpenseMY) >",
                        color = Color(0xFF1D4ED8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF1D4ED8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(
    period: String,
    displayMonth: String,
    onPeriodChanged: (String) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Segmented Tabs: [ 按月 ] [ 近3月 ] [ 按年 ]
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Slate100,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val tabs = listOf("month" to "按月", "3m" to "近3月", "year" to "按年")
                tabs.forEach { (key, label) ->
                    val isSelected = period == key
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color.White else Color.Transparent,
                        shadowElevation = if (isSelected) 2.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPeriodChanged(key) }
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Slate900 else Slate600,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Month pagination: < 2026年10月 >
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Previous Month",
                    tint = Slate600
                )
            }
            Text(
                text = displayMonth,
                color = Slate700,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = onNextMonth, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Next Month",
                    tint = Slate600
                )
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
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
                .padding(20.dp)
        ) {
            Text(
                text = "当期总支出",
                color = Slate400,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "MYR ${String.format("%,.2f", totalSpending)}",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sub-metrics pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Emerald400)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "日常消费: MYR ${String.format("%,.2f", expenseAmount)}",
                        color = Slate200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Sky400)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "对外转账: MYR ${String.format("%,.2f", transferAmount)}",
                        color = Slate200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "已自动剔除本人同名账户互转",
                color = Slate400,
                fontSize = 11.sp
            )
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
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFEF3C7),
        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFBBF24)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🏷️", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "$count expenses need a category",
                        color = Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "MYR ${String.format("%,.2f", amount)} in total",
                        color = Color(0xFFB45309),
                        fontSize = 11.sp
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFB45309)
            ) {
                Text(
                    text = "Sort out >",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Spending trend",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Text(
                    text = "MYR ${String.format("%,.2f", total)} total",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Native Canvas Sparkline
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                if (sparkline.size < 2) return@Canvas
                val maxAmt = sparkline.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 100.0
                val w = size.width
                val h = size.height

                val points = sparkline.mapIndexed { idx, pt ->
                    val x = idx * (w / (sparkline.size - 1))
                    val y = h - ((pt.amount / maxAmt) * (h * 0.85f)).toFloat()
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
                        colors = listOf(Emerald400.copy(alpha = 0.35f), Color.Transparent)
                    )
                )

                // Line stroke
                drawPath(
                    path = path,
                    color = Emerald500,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
fun TransactionCardItem(
    tx: TransactionItem,
    onClick: () -> Unit
) {
    val emoji = when (tx.category) {
        "餐饮" -> "🍔"
        "交通" -> "🚗"
        "杂货" -> "🛒"
        "账单" -> "💡"
        "购物" -> "🛍️"
        "娱乐" -> "🎮"
        "医疗" -> "💊"
        "内部划转" -> "🔄"
        else -> "🏷️"
    }

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
                        text = tx.note.ifBlank { tx.category },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100
                        ) {
                            Text(
                                text = tx.category,
                                color = Slate600,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tx.date,
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Text(
                text = "${if (tx.type == "income") "+" else "-"}MYR ${String.format("%.2f", tx.amount)}",
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
    onWeb: () -> Unit
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
                tint = Emerald600,
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
                icon = Icons.Default.Language,
                label = "Web后台",
                tint = Indigo600,
                selected = false,
                onClick = onWeb,
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
                        text = "待整理账单与转账 (${items.size})",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "点击下方条目快速归类或核验转账状态",
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
                    Text(text = "🎉 所有待整理账单均已处理完毕 (Inbox Zero)", color = Slate400, fontSize = 14.sp)
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
                                        Text(
                                            text = item.note.ifBlank { if (item.isPendingOnHold) "待确认银行转账" else "未命名消费" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate800,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${item.date} · ${if (item.isPendingOnHold) "待确认转账" else (item.category.ifBlank { "待分类" })}",
                                            fontSize = 12.sp,
                                            color = if (item.isPendingOnHold) Amber600 else Slate500
                                        )
                                    }
                                    Text(
                                        text = "MYR ${String.format("%.2f", item.amount)}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (item.isPendingOnHold) Amber600 else Rose500
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (item.isPendingOnHold) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { onResolvePending(item.id, "confirm") },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("记为支出", fontSize = 12.sp, color = Color.White)
                                        }
                                        OutlinedButton(
                                            onClick = { onResolvePending(item.id, "ignore") },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("本人自转", fontSize = 12.sp, color = Slate700)
                                        }
                                        OutlinedButton(
                                            onClick = { onResolvePending(item.id, "transfer") },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("内部调拨", fontSize = 12.sp, color = Slate700)
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
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("⚡ 选择分类", fontSize = 13.sp, color = Color.White)
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
