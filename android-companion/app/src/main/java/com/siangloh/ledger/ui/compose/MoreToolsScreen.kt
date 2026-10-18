package com.siangloh.ledger.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.*
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionActivity
import com.siangloh.ledger.ui.NotificationLogActivity
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreToolsScreen(
    onScanReceipt: () -> Unit = {},
    onNavigateToAdd: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var toolsSummary by remember { mutableStateOf<ToolsSummary?>(null) }
    var pendingItems by remember { mutableStateOf<List<UncategorizedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    // Settings & Permissions states
    var isListeningEnabled by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    var isIgnoringBatteryOptimizations by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    var canDrawOverlays by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    // Server Config Dialog State
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var currentServerUrl by remember { mutableStateOf(NetworkHelper.getServerUrl(context)) }
    var currentApiKey by remember { mutableStateOf(NetworkHelper.getApiKey(context)) }

    fun refreshAll() {
        isLoading = true
        scope.launch {
            // 1. Load Tools (Subscriptions & Installments)
            val toolsRes = NativeLedgerApi.getToolsSummary(context)
            toolsRes.onSuccess {
                toolsSummary = it
            }

            // 2. Load Pending Transactions from Overview API
            try {
                val serverUrl = NetworkHelper.getServerUrl(context).trimEnd('/')
                val apiKey = NetworkHelper.getApiKey(context)
                val username = NetworkHelper.getUsername(context)
                val urlStr = "$serverUrl/api/native/overview?period=month" +
                        if (username.isNotEmpty()) "&username=$username" else ""

                val result = withContext(Dispatchers.IO) {
                    try {
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        if (apiKey.isNotEmpty()) conn.setRequestProperty("X-API-KEY", apiKey)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        if (conn.responseCode in 200..299) {
                            conn.inputStream.bufferedReader().use { it.readText() }
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }

                if (result != null) {
                    val json = JSONObject(result)
                    val uncatArr = json.optJSONArray("uncategorized_items")
                    val list = mutableListOf<UncategorizedItem>()
                    if (uncatArr != null) {
                        for (i in 0 until uncatArr.length()) {
                            val item = uncatArr.getJSONObject(i)
                            list.add(
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
                    pendingItems = list
                }
            } catch (_: Exception) {}

            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "工具与设置",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Text(
                            text = "待办收件箱 · 智能OCR · 周期记账 · 自动化",
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }

                    IconButton(
                        onClick = {
                            isSyncing = true
                            SyncWorker.enqueueSync(context)
                            Toast.makeText(context, "已触发多端数据双向同步", Toast.LENGTH_SHORT).show()
                            refreshAll()
                            isSyncing = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "同步",
                            tint = PrimaryBlue
                        )
                    }
                }
            }
        },
        containerColor = Slate50
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. 待整理收件箱 (Pending Review)
            item {
                PendingInboxCard(
                    pendingCount = pendingItems.size,
                    pendingItems = pendingItems,
                    onItemAction = { id, action ->
                        scope.launch {
                            val res = NativeLedgerApi.handlePendingAction(context, id, action)
                            res.onSuccess {
                                Toast.makeText(context, "操作成功", Toast.LENGTH_SHORT).show()
                                refreshAll()
                            }.onFailure {
                                Toast.makeText(context, "失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // 2. 拍小票智能识别 (Receipt OCR)
            item {
                ReceiptOcrBannerCard(
                    onScanReceipt = onScanReceipt
                )
            }

            // 3. 订阅与周期记账 (Subscriptions)
            item {
                SubscriptionsCard(
                    subscriptions = toolsSummary?.subscriptions ?: emptyList(),
                    totalMonthly = toolsSummary?.totalMonthlySub ?: 0.0
                )
            }

            // 4. 分期与债务追踪 (Installments)
            item {
                InstallmentsCard(
                    installments = toolsSummary?.installments ?: emptyList(),
                    totalRemaining = toolsSummary?.totalRemainingInst ?: 0.0
                )
            }

            // 5. 自动化记账与权限设置 (Automation & Permissions)
            item {
                AutomationSettingsCard(
                    isListening = isListeningEnabled,
                    isBatteryOptimized = isIgnoringBatteryOptimizations,
                    canDrawOverlays = canDrawOverlays,
                    onManageListener = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    },
                    onManageBattery = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    },
                    onManageOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onOpenAppSelection = {
                        context.startActivity(Intent(context, AppSelectionActivity::class.java))
                    },
                    onOpenLogs = {
                        context.startActivity(Intent(context, NotificationLogActivity::class.java))
                    }
                )
            }

            // 6. 服务器连接与同步设置 (Connection Settings)
            item {
                ServerSettingsCard(
                    serverUrl = currentServerUrl,
                    apiKey = currentApiKey,
                    onEditConfig = {
                        showServerConfigDialog = true
                    }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ledger Mobile 1.0.3 · 100% 纯原生架构",
                        fontSize = 12.sp,
                        color = Slate400
                    )
                    Text(
                        text = "告别网页延迟与掉线，数据实时双向同步",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showServerConfigDialog) {
        ServerConfigDialog(
            initialUrl = currentServerUrl,
            initialKey = currentApiKey,
            onDismiss = { showServerConfigDialog = false },
            onSave = { newUrl, newKey ->
                NetworkHelper.setServerUrl(context, newUrl)
                NetworkHelper.setApiKey(context, newKey)
                currentServerUrl = newUrl
                currentApiKey = newKey
                showServerConfigDialog = false
                Toast.makeText(context, "配置已更新", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Component: 待整理收件箱 (Pending Review Card)
// -----------------------------------------------------------------------------
@Composable
fun PendingInboxCard(
    pendingCount: Int,
    pendingItems: List<UncategorizedItem>,
    onItemAction: (Long, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pendingCount > 0) Color(0xFFFFFBEB) else Color.White
        ),
        border = BorderStroke(
            1.dp,
            if (pendingCount > 0) Color(0xFFFDE68A) else Slate200
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (pendingCount > 0) Color(0xFFF59E0B) else Slate100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (pendingCount > 0) Icons.Default.Inbox else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (pendingCount > 0) Color.White else PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "待整理收件箱",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Text(
                            text = if (pendingCount > 0) "有 $pendingCount 笔自动抓取账单需确认" else "所有账目已整理完毕",
                            fontSize = 12.sp,
                            color = if (pendingCount > 0) Color(0xFFD97706) else Slate500
                        )
                    }
                }

                if (pendingCount > 0) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            text = if (expanded) "收起" else "查看 (${pendingCount})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD97706)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && pendingCount > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pendingItems.take(5).forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.note.ifBlank { "自动捕获账单" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Slate800,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.date} · ${item.category}",
                                            fontSize = 12.sp,
                                            color = Slate500
                                        )
                                    }
                                    Text(
                                        text = String.format(Locale.getDefault(), "-RM %.2f", item.amount),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ExpenseRed
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onItemAction(item.id, "ignore") },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("忽略", fontSize = 12.sp, color = Slate600)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { onItemAction(item.id, "confirm") },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("确认入账", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 拍小票 AI 识别 Banner
// -----------------------------------------------------------------------------
@Composable
fun ReceiptOcrBannerCard(
    onScanReceipt: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF2FF)),
        border = BorderStroke(1.dp, Color(0xFFC7D2FE))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "拍小票",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "拍小票 AI 智能识别",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    Text(
                        text = "本地 ML Kit 离线 OCR，提取金额商家",
                        fontSize = 12.sp,
                        color = Slate600
                    )
                }
            }

            Button(
                onClick = onScanReceipt,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("拍照/选图", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 订阅与周期记账
// -----------------------------------------------------------------------------
@Composable
fun SubscriptionsCard(
    subscriptions: List<SubscriptionRecord>,
    totalMonthly: Double
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (subscriptions.isNotEmpty()) expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEDE9FE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = null,
                            tint = Color(0xFF7C3AED),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "订阅与周期记账",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Text(
                            text = "月均支出 RM ${String.format(Locale.getDefault(), "%.2f", totalMonthly)} · ${subscriptions.size} 项生效中",
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }

                if (subscriptions.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Slate500
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && subscriptions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subscriptions.forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate50, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = sub.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Slate800
                                )
                                Text(
                                    text = "下次扣款: ${sub.nextBillingDate} · ${sub.category}",
                                    fontSize = 11.sp,
                                    color = Slate500
                                )
                            }
                            Text(
                                text = String.format(Locale.getDefault(), "RM %.2f", sub.cost),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 分期与债务追踪
// -----------------------------------------------------------------------------
@Composable
fun InstallmentsCard(
    installments: List<InstallmentRecord>,
    totalRemaining: Double
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (installments.isNotEmpty()) expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFEF3C7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "分期与债务追踪",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Text(
                            text = "剩余待还 RM ${String.format(Locale.getDefault(), "%.2f", totalRemaining)} · ${installments.size} 项进行中",
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }

                if (installments.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Slate500
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && installments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    installments.forEach { inst ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate50, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = inst.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Slate800
                                )
                                Text(
                                    text = "已还 ${inst.paidPeriods}/${inst.tenureMonths} 期 · 月供 RM ${String.format(Locale.getDefault(), "%.2f", inst.monthlyAmount)}",
                                    fontSize = 11.sp,
                                    color = Slate500
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = String.format(Locale.getDefault(), "RM %.2f", inst.remainingAmount),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ExpenseRed
                                )
                                Text(
                                    text = "待还",
                                    fontSize = 10.sp,
                                    color = Slate400
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 自动化与设备权限卡片
// -----------------------------------------------------------------------------
@Composable
fun AutomationSettingsCard(
    isListening: Boolean,
    isBatteryOptimized: Boolean,
    canDrawOverlays: Boolean,
    onManageListener: () -> Unit,
    onManageBattery: () -> Unit,
    onManageOverlay: () -> Unit,
    onOpenAppSelection: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "自动化记账守护",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate800
            )
            Text(
                text = "毫秒级拦截银行通知，无需手动打开应用",
                fontSize = 12.sp,
                color = Slate500
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 1. 通知监听服务
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isListening) IncomeGreen else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "通知监听服务",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate800
                        )
                        Text(
                            text = if (isListening) "运行中 (已授权)" else "未启用 (点击前往系统设置授权)",
                            fontSize = 11.sp,
                            color = if (isListening) IncomeGreen else Color(0xFFEF4444)
                        )
                    }
                }
                TextButton(onClick = onManageListener) {
                    Text("设置", fontSize = 13.sp, color = PrimaryBlue)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Slate100)

            // 2. 电池优化白名单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "后台省电白名单",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate800
                    )
                    Text(
                        text = if (isBatteryOptimized) "已豁免 (允许后台常驻)" else "未豁免 (可能被系统杀进程)",
                        fontSize = 11.sp,
                        color = if (isBatteryOptimized) IncomeGreen else Color(0xFFD97706)
                    )
                }
                TextButton(onClick = onManageBattery) {
                    Text("优化", fontSize = 13.sp, color = PrimaryBlue)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Slate100)

            // 3. 悬浮窗快速确认
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "悬浮窗即时记账弹窗",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate800
                    )
                    Text(
                        text = if (canDrawOverlays) "已开启 (捕获账单后弹出气泡)" else "未开启",
                        fontSize = 11.sp,
                        color = if (canDrawOverlays) IncomeGreen else Slate500
                    )
                }
                TextButton(onClick = onManageOverlay) {
                    Text("权限", fontSize = 13.sp, color = PrimaryBlue)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Slate100)

            // 4. 快捷入口：监控应用与抓取日志
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenAppSelection,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("监控的应用", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("抓取日志", fontSize = 12.sp)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 服务器连接配置卡片
// -----------------------------------------------------------------------------
@Composable
fun ServerSettingsCard(
    serverUrl: String,
    apiKey: String,
    onEditConfig: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "账本服务器连接",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    Text(
                        text = serverUrl.ifBlank { "未配置" },
                        fontSize = 12.sp,
                        color = PrimaryBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onEditConfig,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate100),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("修改", fontSize = 12.sp, color = Slate700)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "API Key: " + if (apiKey.isNotEmpty()) "${apiKey.take(4)}••••••••" else "未设置",
                fontSize = 11.sp,
                color = Slate400
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Dialog: 修改服务器与 API Key
// -----------------------------------------------------------------------------
@Composable
fun ServerConfigDialog(
    initialUrl: String,
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("修改服务器连接", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("后端服务器地址 (URL)") },
                    placeholder = { Text("https://example.com 或 http://192.168.x.x:5000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API 访问秘钥 (X-API-KEY)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(url.trim(), key.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text("保存配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
