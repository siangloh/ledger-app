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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.siangloh.ledger.R
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.*
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionActivity
import com.siangloh.ledger.ui.NotificationLogActivity
import com.siangloh.ledger.ui.compose.components.MoreToolsSkeletonScreen
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreToolsScreen(
    onScanReceipt: () -> Unit = {},
    onNavigateToAdd: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var toolsSummary by remember {
        mutableStateOf<ToolsSummary?>(
            LocalDataStore.getTools(context)?.let {
                try {
                    NativeLedgerApi.parseToolsSummaryJson(JSONObject(it))
                } catch (_: Exception) { null }
            }
        )
    }
    var toolsError by remember { mutableStateOf<String?>(null) }
    var pendingItems by remember { mutableStateOf<List<UncategorizedItem>>(emptyList()) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var categorizingItem by remember { mutableStateOf<UncategorizedItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isPendingLoading by remember { mutableStateOf(true) }
    var userCategories by remember { mutableStateOf<List<String>>(emptyList()) }
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
        if (toolsSummary == null) isLoading = true
        isPendingLoading = true
        toolsError = null
        pendingError = null
        scope.launch {
            // 0. Load User Categories
            try {
                NativeLedgerApi.getCategories(context).onSuccess { catRes ->
                    val names = catRes.expenses.map { it.name }.filter { it.isNotBlank() }
                    if (names.isNotEmpty()) userCategories = names
                }
            } catch (_: Exception) {}

            // 1. Load Tools (Subscriptions & Installments)
            val toolsRes = NativeLedgerApi.getToolsSummary(context)
            toolsRes.onSuccess {
                toolsSummary = it
                toolsError = null
            }.onFailure {
                toolsError = it.message ?: "加载订阅与分期数据失败"
            }

            // 2. Load Pending Transactions from Overview API
            try {
                val serverUrl = NetworkHelper.getServerUrl(context).trimEnd('/')
                val apiKey = NetworkHelper.getApiKey(context)
                val username = NetworkHelper.getUsername(context)
                val urlStr = "$serverUrl/api/native/overview?period=month" +
                        if (username.isNotEmpty()) "&username=${URLEncoder.encode(username, "UTF-8")}" else ""

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
                            val isPending = item.optBoolean("is_pending_on_hold", false)
                            val srcType = item.optString("source_type", if (isPending) "pending" else "transaction")
                            list.add(
                                UncategorizedItem(
                                    id = item.optLong("id"),
                                    date = item.optString("date"),
                                    amount = item.optDouble("amount"),
                                    note = item.optString("note"),
                                    category = item.optString("category"),
                                    isPendingOnHold = isPending,
                                    sourceType = srcType
                                )
                            )
                        }
                    }
                    pendingItems = list
                    pendingError = null
                } else {
                    pendingError = "无法连接服务器获取待整理条目"
                }
            } catch (e: Exception) {
                pendingError = e.message ?: "获取待整理条目异常"
            }

            isPendingLoading = false
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
        if (isLoading && toolsSummary == null && pendingItems.isEmpty()) {
            MoreToolsSkeletonScreen(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // 0. 全功能账本大厅直达与功能矩阵
                item {
                    FullLedgerFeaturesCard(
                        onOpenFullLedger = { onOpenWebUrl("/") },
                        onOpenFeature = onOpenWebUrl,
                        onOpenSettings = onOpenSettings
                    )
                }

            // 1. 待整理收件箱 (Pending Review)
            item {
                PendingInboxCard(
                    pendingCount = pendingItems.size,
                    pendingItems = pendingItems,
                    errorMessage = pendingError,
                    isLoading = isPendingLoading,
                    onRetry = { refreshAll() },
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
                    },
                    onCategorizeItem = { item ->
                        categorizingItem = item
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
                    totalMonthly = toolsSummary?.totalMonthlySub ?: 0.0,
                    errorMessage = toolsError,
                    onRetry = { refreshAll() },
                    onOpenManage = { onOpenWebUrl("/subscriptions") }
                )
            }

            // 4. 分期与债务追踪 (Installments)
            item {
                InstallmentsCard(
                    installments = toolsSummary?.installments ?: emptyList(),
                    totalRemaining = toolsSummary?.totalRemainingInst ?: 0.0,
                    errorMessage = toolsError,
                    onRetry = { refreshAll() },
                    onOpenManage = { onOpenWebUrl("/liabilities") }
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

            // 7. 界面语言设置 (Language Settings)
            item {
                val currentLang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(context)
                LanguageSettingsCard(
                    currentLang = currentLang,
                    onSelectLanguage = { newLang ->
                        com.siangloh.ledger.util.AppLanguageManager.setLanguage(context, newLang)
                        (context as? android.app.Activity)?.recreate()
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
                        text = "Ledger Mobile v${com.siangloh.ledger.BuildConfig.VERSION_NAME} (Build ${com.siangloh.ledger.BuildConfig.VERSION_CODE})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate500
                    )
                    Text(
                        text = "原生伴侣架构 · 数据实时双向同步",
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
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

    categorizingItem?.let { item ->
        QuickCategorizeDialog(
            item = item,
            userCategories = userCategories,
            onDismiss = { categorizingItem = null },
            onConfirmCategory = { cat ->
                scope.launch {
                    val res = NativeLedgerApi.quickCategorize(context, item.id, cat)
                    res.onSuccess {
                        Toast.makeText(context, "已归类为【$cat】", Toast.LENGTH_SHORT).show()
                        categorizingItem = null
                        refreshAll()
                    }.onFailure {
                        Toast.makeText(context, "归类失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
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
    errorMessage: String? = null,
    isLoading: Boolean = false,
    onRetry: () -> Unit = {},
    onItemAction: (Long, String) -> Unit,
    onCategorizeItem: (UncategorizedItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

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
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = PrimaryTeal
                            )
                        } else {
                            Icon(
                                imageVector = if (pendingCount > 0) Icons.Default.Inbox else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (pendingCount > 0) Color.White else PrimaryTeal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                            text = if (isLoading) "正在同步待整理收件箱..."
                                   else if (errorMessage != null) "加载失败：$errorMessage"
                                   else if (pendingCount > 0) "有 $pendingCount 笔账目需整理确认"
                                   else "所有账目已整理完毕 (Inbox Zero)",
                            fontSize = 12.sp,
                            color = if (errorMessage != null) ExpenseRed else if (pendingCount > 0) Color(0xFFD97706) else Slate500
                        )
                    }
                }

                if (errorMessage != null) {
                    TextButton(onClick = onRetry) {
                        Text("重试", fontSize = 13.sp, color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                } else if (pendingCount > 0) {
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
                    val displayedList = if (showAll) pendingItems else pendingItems.take(5)
                    displayedList.forEach { item ->
                        val isPending = item.sourceType == "pending" || item.isPendingOnHold
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isPending) Color(0xFFFEF3C7) else Color(0xFFFFE4E6),
                                                modifier = Modifier.padding(end = 6.dp)
                                            ) {
                                                Text(
                                                    text = if (isPending) "待确认划转" else "未分类支出",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isPending) Color(0xFFD97706) else ExpenseRed,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                            Text(
                                                text = item.note.ifBlank { if (isPending) "待确认转账" else "自动捕获账单" },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Slate800,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${item.date} · ${item.category.ifBlank { if (isPending) "转账" else "未分类" }}",
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
                                    if (isPending) {
                                        OutlinedButton(
                                            onClick = { onItemAction(item.id, "ignore") },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("本人自转", fontSize = 12.sp, color = Slate600)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { onItemAction(item.id, "transfer") },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("内部调拨", fontSize = 12.sp, color = Slate600)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Button(
                                            onClick = { onItemAction(item.id, "confirm") },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("记为支出", fontSize = 12.sp, color = Color.White)
                                        }
                                    } else {
                                        Button(
                                            onClick = { onCategorizeItem(item) },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("去分类", fontSize = 12.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (pendingItems.size > 5) {
                        TextButton(
                            onClick = { showAll = !showAll },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = if (showAll) "收起仅显示前 5 条" else "展开查看全部待整理条目 (共 ${pendingItems.size} 条)",
                                fontSize = 12.sp,
                                color = Slate600,
                                fontWeight = FontWeight.Medium
                            )
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
    totalMonthly: Double,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onOpenManage: () -> Unit = {}
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
                            .background(PrimaryTealSubtle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = null,
                            tint = PrimaryTeal,
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
                            text = if (errorMessage != null) "加载失败：$errorMessage"
                                   else "月均支出 RM ${String.format(Locale.getDefault(), "%.2f", totalMonthly)} · ${subscriptions.size} 项生效中",
                            fontSize = 12.sp,
                            color = if (errorMessage != null) ExpenseRed else Slate500
                        )
                    }
                }

                if (errorMessage != null) {
                    TextButton(onClick = onRetry) {
                        Text("重试", fontSize = 13.sp, color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                } else if (subscriptions.isNotEmpty()) {
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

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onOpenManage,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("前往订阅中心维护与新增周期扣款", fontSize = 12.sp, color = PrimaryTeal)
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
    totalRemaining: Double,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onOpenManage: () -> Unit = {}
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
                            text = if (errorMessage != null) "加载失败：$errorMessage"
                                   else "剩余待还 RM ${String.format(Locale.getDefault(), "%.2f", totalRemaining)} · ${installments.size} 项进行中",
                            fontSize = 12.sp,
                            color = if (errorMessage != null) ExpenseRed else Slate500
                        )
                    }
                }

                if (errorMessage != null) {
                    TextButton(onClick = onRetry) {
                        Text("重试", fontSize = 13.sp, color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                } else if (installments.isNotEmpty()) {
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

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onOpenManage,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("前往债务中心维护负债、车房贷与分期", fontSize = 12.sp, color = Rose500)
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
// -----------------------------------------------------------------------------
// Component: 全功能账本大厅直达与高级功能矩阵
// -----------------------------------------------------------------------------
@Composable
fun FullLedgerFeaturesCard(
    onOpenFullLedger: () -> Unit,
    onOpenFeature: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
        border = BorderStroke(1.dp, Color(0xFFBBF7D0))
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
                            .background(Emerald600),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "全功能账务大厅 (高级管理)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = "全景维护订阅大厅、负债车房贷、固定收支与分类报表",
                            fontSize = 12.sp,
                            color = Slate600
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onOpenFullLedger,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald600)
            ) {
                Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("进入全功能账务管理大厅", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text("快捷功能直达：", fontSize = 11.sp, color = Slate500, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

            val quickLinks = listOf(
                Triple(stringResource(R.string.quick_subscriptions), "/subscriptions", PrimaryTeal),
                Triple(stringResource(R.string.quick_liabilities), "/liabilities", Rose500),
                Triple(stringResource(R.string.quick_insights), "/categories/insights", PrimaryTeal),
                Triple(stringResource(R.string.quick_categories), "/categories", Amber600),
                Triple(stringResource(R.string.quick_import), "/import", Slate700),
                Triple(stringResource(R.string.quick_user_binding), "settings", Emerald600)
            )

            val chunked = quickLinks.chunked(3)
            chunked.forEach { rowLinks ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowLinks.forEach { (label, route, color) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (route == "settings") onOpenSettings() else onOpenFeature(route)
                                }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = color,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: 界面语言选择卡片 (Language Settings)
// -----------------------------------------------------------------------------
@Composable
fun LanguageSettingsCard(
    currentLang: String,
    onSelectLanguage: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val langLabel = when (currentLang) {
        "en" -> "English"
        "ms" -> "Bahasa Melayu"
        else -> "中文 (简体)"
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PrimaryTealSubtle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.language_settings_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = langLabel,
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, PrimaryTeal)
                ) {
                    Text(
                        text = stringResource(R.string.language_select_dialog_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryTeal
                    )
                }
            }
        }
    }

    if (showDialog) {
        val langs = listOf(
            "zh" to stringResource(R.string.lang_zh),
            "en" to stringResource(R.string.lang_en),
            "ms" to stringResource(R.string.lang_ms)
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.language_select_dialog_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    langs.forEach { (code, name) ->
                        val isSelected = code == currentLang
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) PrimaryTealSubtle else Slate100,
                            border = if (isSelected) BorderStroke(1.dp, PrimaryTeal) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    onSelectLanguage(code)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PrimaryTeal else Slate800
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = PrimaryTeal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Component: 快速归类弹窗 (Quick Categorize)
// -----------------------------------------------------------------------------
@Composable
fun QuickCategorizeDialog(
    item: UncategorizedItem,
    userCategories: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmCategory: (String) -> Unit
) {
    var customCategory by remember { mutableStateOf("") }
    val displayCategories = remember(userCategories) {
        if (userCategories.isNotEmpty()) {
            userCategories
        } else {
            listOf("餐饮", "交通", "杂货", "账单", "购物", "娱乐", "医疗", "居家", "数码", "理财", "教育", "其他")
        }
    }

    val emojiMap = mapOf(
        "餐饮" to "🍔", "交通" to "🚗", "杂货" to "🛒", "账单" to "💡",
        "购物" to "🛍️", "娱乐" to "🎮", "医疗" to "💊", "居家" to "🏠",
        "数码" to "📱", "理财" to "📈", "教育" to "📚", "其他" to "🏷️",
        "工资" to "💰", "奖金" to "🎁", "储蓄" to "🏦"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = { Text("为交易选择分类", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${item.note.ifBlank { "未命名消费" }} · RM ${String.format(Locale.getDefault(), "%.2f", item.amount)}",
                    fontSize = 13.sp,
                    color = Slate600
                )

                // 自定义分类输入框
                OutlinedTextField(
                    value = customCategory,
                    onValueChange = { customCategory = it },
                    placeholder = { Text("自定义分类...", fontSize = 12.sp, color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    trailingIcon = {
                        if (customCategory.isNotBlank()) {
                            IconButton(onClick = { onConfirmCategory(customCategory.trim()) }) {
                                Icon(Icons.Default.Check, contentDescription = "确认", tint = PrimaryTeal)
                            }
                        }
                    },
                    singleLine = true
                )

                val chunked = displayCategories.chunked(3)
                chunked.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { catName ->
                            val emoji = emojiMap[catName] ?: "🏷️"
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Slate100,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onConfirmCategory(catName) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(emoji, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = catName,
                                        fontSize = 12.sp,
                                        color = Slate800,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (customCategory.isNotBlank()) {
                Button(
                    onClick = { onConfirmCategory(customCategory.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("使用「${customCategory.trim()}」")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Slate600)
            }
        }
    )
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
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = {
            Text("修改服务器连接", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
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
                Text("保存配置", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Slate600)
            }
        }
    )
}
