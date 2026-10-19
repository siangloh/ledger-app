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
import androidx.compose.ui.text.style.TextAlign
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
    var showRecycleBinDialog by remember { mutableStateOf(false) }
    var showCategoryRulesDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }


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
                            val reviewReason = item.optString("review_reason", if (isPending) "on_hold_transfer" else "unclear_category")
                            val onHoldReason = item.optString("on_hold_reason", "")
                            val dupObj = item.optJSONObject("suspected_duplicate")
                            val dupId = if (item.has("suspected_duplicate_id") && !item.isNull("suspected_duplicate_id")) item.optLong("suspected_duplicate_id") else null
                            list.add(
                                UncategorizedItem(
                                    id = item.optLong("id"),
                                    date = item.optString("date"),
                                    amount = item.optDouble("amount"),
                                    note = item.optString("note"),
                                    category = item.optString("category"),
                                    isPendingOnHold = isPending,
                                    sourceType = srcType,
                                    reviewReason = reviewReason,
                                    onHoldReason = onHoldReason,
                                    suspectedDuplicateId = dupId,
                                    suspectedDuplicateNote = dupObj?.optString("note"),
                                    suspectedDuplicateAmount = if (dupObj != null && dupObj.has("amount")) dupObj.optDouble("amount") else null,
                                    suspectedDuplicateCategory = dupObj?.optString("category"),
                                    suspectedDuplicateDate = dupObj?.optString("date")
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

            // 6.5 回收站管理 (Recycle Bin)
            item {
                RecycleBinCard(
                    onOpenRecycleBin = { showRecycleBinDialog = true }
                )
            }

            // 6.6 商户分类规则与纠正记忆 (Category Rules & Memory)
            item {
                CategoryRulesCard(
                    onOpenCategoryRules = { showCategoryRulesDialog = true }
                )
            }

            // 6.7 预算规划与每日安全限额 (Budget Management)
            item {
                BudgetManagementCard(
                    onOpenBudget = { showBudgetDialog = true }
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
            initialWifiOnly = NetworkHelper.isWifiOnlySync(context),
            onDismiss = { showServerConfigDialog = false },
            onSave = { newUrl, newKey, newWifiOnly ->
                NetworkHelper.setServerUrl(context, newUrl)
                NetworkHelper.setApiKey(context, newKey)
                NetworkHelper.setWifiOnlySync(context, newWifiOnly)
                com.siangloh.ledger.sync.SyncWorker.enqueueSync(context)
                currentServerUrl = newUrl
                currentApiKey = newKey
                showServerConfigDialog = false
                Toast.makeText(context, "配置与同步规则已更新", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
        )
    }

    if (showRecycleBinDialog) {
        RecycleBinDialog(
            onDismiss = { showRecycleBinDialog = false }
        )
    }

    if (showCategoryRulesDialog) {
        CategoryRulesDialog(
            onDismiss = { showCategoryRulesDialog = false }
        )
    }

    if (showBudgetDialog) {
        BudgetManagementDialog(
            onDismiss = {
                showBudgetDialog = false
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
                                            val badgeText = when {
                                                item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null -> "⚠️ 疑似重复"
                                                item.reviewReason == "unmatched_account" -> "🏦 待指定账户"
                                                isPending -> "待确认划转"
                                                else -> "未分类支出"
                                            }
                                            val badgeBg = when {
                                                item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null -> Color(0xFFFEF3C7)
                                                item.reviewReason == "unmatched_account" -> Color(0xFFF3E8FF)
                                                isPending -> Color(0xFFFEF3C7)
                                                else -> Color(0xFFFFE4E6)
                                            }
                                            val badgeFg = when {
                                                item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null -> Color(0xFFD97706)
                                                item.reviewReason == "unmatched_account" -> Color(0xFF7E22CE)
                                                isPending -> Color(0xFFD97706)
                                                else -> ExpenseRed
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = badgeBg,
                                                modifier = Modifier.padding(end = 6.dp)
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeFg,
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

                                if (item.suspectedDuplicateId != null || item.reviewReason == "suspected_duplicate") {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Slate100,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "参考已有记录 #${item.suspectedDuplicateId ?: 0}: ${item.suspectedDuplicateNote ?: "交易"}",
                                            fontSize = 11.sp,
                                            color = Slate600,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isPending) {
                                        if (item.reviewReason == "suspected_duplicate" || item.suspectedDuplicateId != null) {
                                            OutlinedButton(
                                                onClick = { onItemAction(item.id, "mark-not-duplicate") },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("标记非重复", fontSize = 12.sp, color = Slate600)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = { onItemAction(item.id, "merge") },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("合并重复", fontSize = 12.sp, color = Color.White)
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onItemAction(item.id, "ignore") },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("本人自转", fontSize = 12.sp, color = Slate600)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            OutlinedButton(
                                                onClick = { onItemAction(item.id, "transfer") },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("内部调拨", fontSize = 12.sp, color = Slate600)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = { onItemAction(item.id, "confirm") },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Text("记为支出", fontSize = 12.sp, color = Color.White)
                                            }
                                        }
                                    } else {
                                        Button(
                                            onClick = { onCategorizeItem(item) },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                            modifier = Modifier.height(48.dp)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMatchDialog by remember { mutableStateOf(false) }
    var isLoadingMatches by remember { mutableStateOf(false) }
    var matchRecords by remember { mutableStateOf<List<TransactionMatchRecord>>(emptyList()) }

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

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenManage,
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, PrimaryTeal)
                        ) {
                            Text("订阅大厅", fontSize = 12.sp, color = PrimaryTeal)
                        }
                        Button(
                            onClick = {
                                isLoadingMatches = true
                                showMatchDialog = true
                                scope.launch {
                                    val res = NativeLedgerApi.getSubscriptionMatches(context)
                                    isLoadingMatches = false
                                    matchRecords = res.getOrDefault(emptyList())
                                }
                            },
                            modifier = Modifier.weight(1f).height(38.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("智能匹配流水", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showMatchDialog) {
        AlertDialog(
            onDismissRequest = { showMatchDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("智能流水配对候选", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Slate900)
                }
            },
            text = {
                if (isLoadingMatches) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PrimaryTeal)
                    }
                } else if (matchRecords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎉 暂无待匹配流水，所有近期扣费均已对齐或已关联！",
                            fontSize = 13.sp,
                            color = Slate500,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(matchRecords) { match ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate50),
                                border = BorderStroke(1.dp, Slate200)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📅 ${match.txDate} · ${match.txNote.ifEmpty { "支出" }}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate800,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "- RM ${String.format(Locale.getDefault(), "%.2f", match.txAmount)}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ExpenseRed
                                        )
                                    }

                                    if (match.matchedSubId != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (match.matchedSubTargetToCancel) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFFFEE2E2),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "⚠️【打算退订拦截预警】已标记退订但发生扣款！",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFB91C1C),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "💡 匹配订阅: ${match.matchedSubName} · 下期: ${match.matchedSubNextBilling ?: "-"}",
                                                fontSize = 11.sp,
                                                color = PrimaryTeal
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val res = NativeLedgerApi.applySubscriptionMatch(context, match.matchedSubId, match.txId)
                                                    if (res.isSuccess) {
                                                        Toast.makeText(context, "已成功顺延订阅下期扣款日", Toast.LENGTH_SHORT).show()
                                                        matchRecords = matchRecords.filter { it.txId != match.txId }
                                                    } else {
                                                        Toast.makeText(context, "操作失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("顺延下期扣款日", fontSize = 11.sp, color = Color.White)
                                        }
                                    } else if (match.matchedInstId != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "💡 匹配分期: ${match.matchedInstTitle} (第 ${match.matchedInstNextPeriod}/${match.matchedInstTenure} 期)",
                                            fontSize = 11.sp,
                                            color = PrimaryTeal
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val res = NativeLedgerApi.applyInstallmentMatch(context, match.matchedInstId, match.txId)
                                                    if (res.isSuccess) {
                                                        Toast.makeText(context, "已成功推进分期还款进度", Toast.LENGTH_SHORT).show()
                                                        matchRecords = matchRecords.filter { it.txId != match.txId }
                                                    } else {
                                                        Toast.makeText(context, "操作失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("记录当期分期已还", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMatchDialog = false }) {
                    Text("关闭", color = PrimaryTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
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
    initialWifiOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var wifiOnly by remember { mutableStateOf(initialWifiOnly) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = {
            Text("修改服务器与同步配置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
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
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { wifiOnly = !wifiOnly }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("仅在 WiFi 下自动同步", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            Text("无 WiFi 时先存入手机本地；连上 WiFi 时自动上传到云端", fontSize = 11.sp, color = Slate500)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { wifiOnly = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryTeal
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(url.trim(), key.trim(), wifiOnly)
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

// -----------------------------------------------------------------------------
// Component: 回收站管理卡片 (Recycle Bin Card)
// -----------------------------------------------------------------------------
@Composable
fun RecycleBinCard(
    onOpenRecycleBin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEF2F2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.tools_recycle_bin),
                        tint = Rose500,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.tools_recycle_bin),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    Text(
                        text = stringResource(R.string.tools_recycle_bin_desc),
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenRecycleBin,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Slate300),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = "查看",
                    fontSize = 13.sp,
                    color = Slate700,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Dialog: 回收站详情与恢复/彻底销毁 (Recycle Bin Dialog)
// -----------------------------------------------------------------------------
@Composable
fun RecycleBinDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var itemToDelete by remember { mutableStateOf<Long?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }

    fun loadItems() {
        isLoading = true
        errorMessage = null
        NetworkHelper.getRecycleBinItems(page = 1) { success, resultItems, total, error ->
            scope.launch(Dispatchers.Main) {
                isLoading = false
                if (success) {
                    items = resultItems
                    totalCount = total
                } else {
                    errorMessage = error ?: "加载回收站失败"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadItems()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = Rose500,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.tools_recycle_bin),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    if (totalCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = Slate100,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = "$totalCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate600,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    TextButton(
                        onClick = { showEmptyConfirm = true },
                        enabled = !isOperating,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.recycle_bin_empty_btn),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Rose500
                        )
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 460.dp)
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = PrimaryTeal,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = errorMessage ?: "加载失败",
                                fontSize = 13.sp,
                                color = ExpenseRed,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Button(
                                onClick = { loadItems() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text(stringResource(R.string.common_retry), color = Color.White)
                            }
                        }
                    }
                    items.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = Slate300,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.recycle_bin_empty_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate700
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.recycle_bin_empty_desc),
                                fontSize = 12.sp,
                                color = Slate400
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items) { item ->
                                val id = item.optLong("id")
                                val date = item.optString("date")
                                val type = item.optString("type")
                                val cat = item.optString("category")
                                val note = item.optString("note")
                                val amount = item.optDouble("amount")
                                val accName = item.optString("account_name", "默认账户")
                                val isExpense = type == "expense"

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Slate50,
                                    border = BorderStroke(1.dp, Slate200),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = note.ifBlank { cat.ifBlank { "已删除记录" } },
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Slate800,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "$date · $cat · $accName",
                                                    fontSize = 11.sp,
                                                    color = Slate500
                                                )
                                            }
                                            Text(
                                                text = String.format(Locale.getDefault(), if (isExpense) "-RM %.2f" else "+RM %.2f", amount),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isExpense) ExpenseRed else IncomeGreen
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 彻底删除按钮
                                            OutlinedButton(
                                                onClick = { itemToDelete = id },
                                                enabled = !isOperating,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, Rose500.copy(alpha = 0.4f)),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose500),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.recycle_bin_delete_btn),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            // 恢复按钮 (PrimaryTeal, min 48dp)
                                            Button(
                                                onClick = {
                                                    isOperating = true
                                                    NetworkHelper.restoreRecycleBinItem(id) { success, msg ->
                                                        scope.launch(Dispatchers.Main) {
                                                            isOperating = false
                                                            if (success) {
                                                                Toast.makeText(context, "已成功恢复", Toast.LENGTH_SHORT).show()
                                                                loadItems()
                                                            } else {
                                                                Toast.makeText(context, msg ?: "恢复失败", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                enabled = !isOperating,
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal, contentColor = Color.White),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.recycle_bin_restore_btn),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_close),
                    fontSize = 13.sp,
                    color = Slate600
                )
            }
        }
    )

    // 二次确认：单条彻底删除
    itemToDelete?.let { txId ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.recycle_bin_delete_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.recycle_bin_confirm_delete),
                    fontSize = 13.sp,
                    color = Slate700
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = txId
                        itemToDelete = null
                        isOperating = true
                        NetworkHelper.permanentDeleteRecycleBinItem(toDel) { success, msg ->
                            scope.launch(Dispatchers.Main) {
                                isOperating = false
                                if (success) {
                                    Toast.makeText(context, "已彻底删除", Toast.LENGTH_SHORT).show()
                                    loadItems()
                                } else {
                                    Toast.makeText(context, msg ?: "删除失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { itemToDelete = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }

    // 二次确认：清空回收站
    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.recycle_bin_empty_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.recycle_bin_confirm_empty),
                    fontSize = 13.sp,
                    color = Slate700
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmptyConfirm = false
                        isOperating = true
                        NetworkHelper.emptyRecycleBin { success, msg ->
                            scope.launch(Dispatchers.Main) {
                                isOperating = false
                                if (success) {
                                    Toast.makeText(context, "回收站已清空", Toast.LENGTH_SHORT).show()
                                    loadItems()
                                } else {
                                    Toast.makeText(context, msg ?: "清空失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEmptyConfirm = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}

@Composable
fun CategoryRulesCard(
    onOpenCategoryRules: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenCategoryRules() }
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFCCFBF1)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Category,
                        contentDescription = stringResource(R.string.tools_category_rules),
                        tint = Color(0xFF0F766E),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.tools_category_rules),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.tools_category_rules_desc),
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
            }
            IconButton(
                onClick = onOpenCategoryRules,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Slate400,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun CategoryRulesDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<CategoryRuleRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<CategoryRuleRecord?>(null) }
    var showApplyAllConfirm by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }

    fun loadRules() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val res = NativeLedgerApi.getCategoryRules(context, search = searchQuery.ifBlank { null })
            isLoading = false
            res.onSuccess {
                rules = it
            }.onFailure {
                errorMessage = it.message ?: "加载规则失败"
            }
        }
    }

    LaunchedEffect(searchQuery) {
        loadRules()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Category,
                        contentDescription = null,
                        tint = Color(0xFF0F766E),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.tools_category_rules),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.category_rules_add_btn),
                        tint = Color(0xFF0F766E)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 520.dp)
            ) {
                // 搜索输入框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索商户或分类...", fontSize = 13.sp, color = Slate400) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0F766E),
                        unfocusedBorderColor = Slate200
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${rules.size} 项规则",
                        fontSize = 12.sp,
                        color = Slate500,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = { showApplyAllConfirm = true },
                        enabled = !isOperating && rules.isNotEmpty(),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(
                            text = "⚡ " + stringResource(R.string.category_rules_apply_all_btn),
                            fontSize = 12.sp,
                            color = Color(0xFF0F766E),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF0F766E))
                    }
                } else if (errorMessage != null) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(text = errorMessage ?: "", color = Rose500, fontSize = 13.sp)
                    }
                } else if (rules.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = stringResource(R.string.category_rules_empty_title), fontWeight = FontWeight.Bold, color = Slate700)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = stringResource(R.string.category_rules_empty_desc), fontSize = 12.sp, color = Slate400)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rules) { r ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate50),
                                border = BorderStroke(1.dp, Slate200)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = r.merchantPattern,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate900
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val typeLabel = when (r.matchType) {
                                                "exact" -> "完全"
                                                "regex" -> "正则"
                                                else -> "包含"
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFE0E7FF), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(text = typeLabel, fontSize = 10.sp, color = Color(0xFF3730A3), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "→ ${r.category}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF0F766E)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "已命中 ${r.applyCount} 次",
                                                fontSize = 11.sp,
                                                color = Slate400
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 追溯历史按钮
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    isOperating = true
                                                    val res = NativeLedgerApi.applyCategoryRuleHistory(
                                                        context = context,
                                                        pattern = r.merchantPattern,
                                                        category = r.category,
                                                        matchType = r.matchType,
                                                        onlyUncategorized = true
                                                    )
                                                    isOperating = false
                                                    res.onSuccess { json ->
                                                        val count = json.optInt("count", 0)
                                                        Toast.makeText(context, "追溯完成，更新了 $count 笔记录", Toast.LENGTH_SHORT).show()
                                                        loadRules()
                                                    }.onFailure {
                                                        Toast.makeText(context, "追溯失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FlashOn,
                                                contentDescription = "追溯此规则",
                                                tint = Color(0xFF0F766E),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        // 删除按钮
                                        IconButton(
                                            onClick = { ruleToDelete = r },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "删除规则",
                                                tint = Rose500,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.common_close), color = Slate600)
            }
        }
    )

    // 新增规则弹窗
    if (showAddDialog) {
        var patternInput by remember { mutableStateOf("") }
        var categoryInput by remember { mutableStateOf("") }
        var matchTypeInput by remember { mutableStateOf("contains") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Color.White,
            title = { Text(text = "添加商户分类规则", fontWeight = FontWeight.Bold, color = Slate900) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = patternInput,
                        onValueChange = { patternInput = it },
                        label = { Text("商户匹配模式") },
                        placeholder = { Text("例如：GrabFood、Starbucks") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("目标分类") },
                        placeholder = { Text("例如：餐饮、交通") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("contains" to "包含关键字", "exact" to "完全匹配").forEach { (type, label) ->
                            val selected = matchTypeInput == type
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Color(0xFFCCFBF1) else Slate100)
                                    .border(1.dp, if (selected) Color(0xFF0F766E) else Slate200, RoundedCornerShape(8.dp))
                                    .clickable { matchTypeInput = type }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    color = if (selected) Color(0xFF0F766E) else Slate700,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = patternInput.trim()
                        val c = categoryInput.trim()
                        if (p.isNotEmpty() && c.isNotEmpty()) {
                            scope.launch {
                                val res = NativeLedgerApi.saveCategoryRule(
                                    context = context,
                                    pattern = p,
                                    category = c,
                                    matchType = matchTypeInput,
                                    isActive = true
                                )
                                res.onSuccess {
                                    Toast.makeText(context, "规则已保存", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                    loadRules()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("保存", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }

    // 删除确认弹窗
    ruleToDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            containerColor = Color.White,
            title = { Text(text = "删除规则", fontWeight = FontWeight.Bold, color = Slate900) },
            text = { Text("确定删除商户规则「${r.merchantPattern}」吗？", color = Slate700) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = NativeLedgerApi.deleteCategoryRule(context, r.merchantPattern)
                            ruleToDelete = null
                            res.onSuccess {
                                Toast.makeText(context, "规则已删除", Toast.LENGTH_SHORT).show()
                                loadRules()
                            }.onFailure {
                                Toast.makeText(context, "删除失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { ruleToDelete = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }

    // 全量重筛确认弹窗
    if (showApplyAllConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyAllConfirm = false },
            containerColor = Color.White,
            title = { Text(text = "⚡ 全量重筛历史", fontWeight = FontWeight.Bold, color = Slate900) },
            text = { Text("将根据所有启用的分类规则，自动将历史“未分类/其他”的交易匹配并更新到对应分类。确认执行？", color = Slate700) },
            confirmButton = {
                Button(
                    onClick = {
                        showApplyAllConfirm = false
                        scope.launch {
                            isOperating = true
                            val res = NativeLedgerApi.applyAllCategoryRulesHistory(context, onlyUncategorized = true)
                            isOperating = false
                            res.onSuccess { json ->
                                val count = json.optInt("count", 0)
                                Toast.makeText(context, "全量重筛完成，共更新 $count 笔记录", Toast.LENGTH_SHORT).show()
                                loadRules()
                            }.onFailure {
                                Toast.makeText(context, "重筛失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("确认执行", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showApplyAllConfirm = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}


// -----------------------------------------------------------------------------
// Component: 预算规划与控制中心 (Budget Management Card)
// -----------------------------------------------------------------------------
@Composable
fun BudgetManagementCard(
    onOpenBudget: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenBudget),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFE6FFFA), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Savings,
                    contentDescription = null,
                    tint = Color(0xFF0F766E),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.budget_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.budget_subtitle),
                    fontSize = 12.sp,
                    color = Slate500
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}


// -----------------------------------------------------------------------------
// Dialog: 预算规划与管理中心 (Budget Management Dialog)
// -----------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetManagementDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<BudgetOverviewRecord?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showSetOverallDialog by remember { mutableStateOf(false) }
    var editingCategoryBudget by remember { mutableStateOf<BudgetItemRecord?>(null) }
    var showAddCategoryBudgetDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun loadBudget() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val res = NativeLedgerApi.getBudgetOverview(context)
            isLoading = false
            res.onSuccess {
                overview = it
            }.onFailure {
                errorMessage = it.message ?: "加载预算概览失败"
            }
        }
    }

    LaunchedEffect(Unit) {
        loadBudget()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎯", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.budget_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Slate400,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF0F766E))
                }
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = errorMessage ?: "", color = Color(0xFFEF4444), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { loadBudget() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("重试", color = Color.White)
                    }
                }
            } else {
                val data = overview
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 月度总预算专区
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate50),
                            border = BorderStroke(1.dp, Color(0xFF5EEAD4))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.budget_overall_title),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate900
                                        )
                                        if (data != null && data.overall.isSet) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val badgeBg = when (data.overall.level) {
                                                "over" -> Color(0xFFFEE2E2)
                                                "warn", "reached" -> Color(0xFFFEF3C7)
                                                else -> Color(0xFFE6FFFA)
                                            }
                                            val badgeColor = when (data.overall.level) {
                                                "over" -> Color(0xFFDC2626)
                                                "warn", "reached" -> Color(0xFFD97706)
                                                else -> Color(0xFF0F766E)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(badgeBg, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${data.overall.pct}%",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor
                                                )
                                            }
                                        }
                                    }

                                    TextButton(
                                        onClick = { showSetOverallDialog = true },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Text(
                                            text = if (data != null && data.overall.isExplicit) stringResource(R.string.budget_edit_overall_btn)
                                                   else stringResource(R.string.budget_set_overall_btn),
                                            color = Color(0xFF0F766E),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                if (data != null && data.overall.isSet) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.budget_spent_format,
                                                String.format(Locale.getDefault(), "RM %.2f", data.overall.spent),
                                                String.format(Locale.getDefault(), "RM %.2f", data.overall.limit)
                                            ),
                                            fontSize = 12.sp,
                                            color = Slate600
                                        )
                                        if (data.overall.remaining < 0) {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_overspent_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", -data.overall.remaining)
                                                ),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_remaining_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", data.overall.remaining)
                                                ),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0F766E)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    val progressPct = ((data.overall.pct) / 100.0).toFloat().coerceIn(0f, 1f)
                                    val barColor = when (data.overall.level) {
                                        "over" -> Color(0xFFEF4444)
                                        "warn", "reached" -> Color(0xFFF59E0B)
                                        else -> Color(0xFF0F766E)
                                    }
                                    LinearProgressIndicator(
                                        progress = { progressPct },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = barColor,
                                        trackColor = Slate200
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFE6FFFA), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_daily_allowance_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", data.overall.dailyAllowance)
                                                ),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF0F766E)
                                            )
                                        }

                                        Text(
                                            text = stringResource(
                                                R.string.budget_cycle_days_format,
                                                data.billingCycle.remainingDays
                                            ),
                                            fontSize = 11.sp,
                                            color = Slate400
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.budget_no_budget) + "，设立最高开支额度可计算每日安全预算",
                                        fontSize = 12.sp,
                                        color = Slate400
                                    )
                                }
                            }
                        }
                    }

                    // 2. 分类预算列表
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "分类支出预算 (${data?.categories?.size ?: 0})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            TextButton(
                                onClick = { showAddCategoryBudgetDialog = true },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF0F766E), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加分类", color = Color(0xFF0F766E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    val catList = data?.categories ?: emptyList()
                    if (catList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无分类预算，点击「添加分类」为餐饮、购物等单独设置上限",
                                    fontSize = 12.sp,
                                    color = Slate400,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(catList) { cat ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingCategoryBudget = cat },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, Slate200)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        try {
                                                            Color(android.graphics.Color.parseColor(cat.color))
                                                        } catch (_: Exception) {
                                                            Color(0xFF0F766E)
                                                        },
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = cat.category,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate900
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${cat.pct}%",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (cat.level) {
                                                    "over" -> Color(0xFFDC2626)
                                                    "warn", "reached" -> Color(0xFFD97706)
                                                    else -> Color(0xFF0F766E)
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { editingCategoryBudget = cat },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "修改",
                                                    tint = Slate400,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "已花: RM ${String.format(Locale.getDefault(), "%.0f", cat.spent)} / 限额: RM ${String.format(Locale.getDefault(), "%.0f", cat.limit)}",
                                            fontSize = 11.sp,
                                            color = Slate500
                                        )
                                        if (cat.remaining < 0) {
                                            Text(
                                                text = "超支 RM ${String.format(Locale.getDefault(), "%.0f", -cat.remaining)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                        } else {
                                            Text(
                                                text = "剩余 RM ${String.format(Locale.getDefault(), "%.0f", cat.remaining)}",
                                                fontSize = 11.sp,
                                                color = Slate500
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    val catPct = (cat.pct / 100.0).toFloat().coerceIn(0f, 1f)
                                    val cBarColor = when (cat.level) {
                                        "over" -> Color(0xFFEF4444)
                                        "warn", "reached" -> Color(0xFFF59E0B)
                                        else -> Color(0xFF0F766E)
                                    }
                                    LinearProgressIndicator(
                                        progress = { catPct },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = cBarColor,
                                        trackColor = Slate100
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.common_close), color = Color.White)
            }
        }
    )

    // Sub-dialog 1: 设置月度总预算
    if (showSetOverallDialog) {
        var limitInput by remember {
            mutableStateOf(
                if (overview != null && overview!!.overall.isExplicit && overview!!.overall.limit > 0)
                    String.format(Locale.getDefault(), "%.2f", overview!!.overall.limit)
                else ""
            )
        }
        AlertDialog(
            onDismissRequest = { showSetOverallDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.budget_set_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column {
                    Text(
                        text = "输入每月最高支出限额。输入 0 或留空可取消总预算。",
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it },
                        label = { Text("总预算限额 (MYR)") },
                        placeholder = { Text(stringResource(R.string.budget_amount_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0F766E),
                            focusedLabelColor = Color(0xFF0F766E)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = limitInput.toDoubleOrNull() ?: 0.0
                        isSubmitting = true
                        scope.launch {
                            val res = NativeLedgerApi.saveOverallBudget(context, num)
                            isSubmitting = false
                            res.onSuccess {
                                Toast.makeText(context, "总预算设置成功", Toast.LENGTH_SHORT).show()
                                showSetOverallDialog = false
                                loadBudget()
                            }.onFailure {
                                Toast.makeText(context, "设置失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_save_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSetOverallDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }

    // Sub-dialog 2: 编辑/删除已有分类预算
    editingCategoryBudget?.let { cat ->
        var catLimitInput by remember {
            mutableStateOf(String.format(Locale.getDefault(), "%.2f", cat.limit))
        }
        AlertDialog(
            onDismissRequest = { editingCategoryBudget = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.budget_set_cat_dialog_title, cat.category),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column {
                    Text(
                        text = "输入「${cat.category}」每月的支出上限金额。",
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = catLimitInput,
                        onValueChange = { catLimitInput = it },
                        label = { Text("限额 (MYR)") },
                        placeholder = { Text(stringResource(R.string.budget_amount_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0F766E),
                            focusedLabelColor = Color(0xFF0F766E)
                        )
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.deleteCategoryBudget(context, cat.category)
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, "已清除分类预算", Toast.LENGTH_SHORT).show()
                                    editingCategoryBudget = null
                                    loadBudget()
                                }.onFailure {
                                    Toast.makeText(context, "清除失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.budget_delete_btn), color = Color(0xFFDC2626))
                    }

                    Button(
                        onClick = {
                            val num = catLimitInput.toDoubleOrNull() ?: 0.0
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveCategoryBudget(context, cat.category, num)
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, "分类预算已保存", Toast.LENGTH_SHORT).show()
                                    editingCategoryBudget = null
                                    loadBudget()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.budget_save_btn), color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingCategoryBudget = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }

    // Sub-dialog 3: 新增分类预算
    if (showAddCategoryBudgetDialog) {
        var newCatName by remember { mutableStateOf("") }
        var newCatLimit by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategoryBudgetDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = "添加分类预算",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "输入需要设定月度限额的分类名称与金额：",
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text("分类名称 (如: 餐饮)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0F766E),
                            focusedLabelColor = Color(0xFF0F766E)
                        )
                    )
                    OutlinedTextField(
                        value = newCatLimit,
                        onValueChange = { newCatLimit = it },
                        label = { Text("月度限额 (MYR)") },
                        placeholder = { Text("如: 800.00") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0F766E),
                            focusedLabelColor = Color(0xFF0F766E)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newCatName.trim()
                        val num = newCatLimit.toDoubleOrNull() ?: 0.0
                        if (name.isEmpty() || num <= 0.0) {
                            Toast.makeText(context, "请输入有效的分类名称与大于0的金额", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSubmitting = true
                        scope.launch {
                            val res = NativeLedgerApi.saveCategoryBudget(context, name, num)
                            isSubmitting = false
                            res.onSuccess {
                                Toast.makeText(context, "已为「$name」设定预算", Toast.LENGTH_SHORT).show()
                                showAddCategoryBudgetDialog = false
                                loadBudget()
                            }.onFailure {
                                Toast.makeText(context, "设定失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_save_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddCategoryBudgetDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }
}



