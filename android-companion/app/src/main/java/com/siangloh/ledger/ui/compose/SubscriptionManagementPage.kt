package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Sync
import com.siangloh.ledger.R
import androidx.compose.foundation.ExperimentalFoundationApi
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.FixedIncomeRecord
import com.siangloh.ledger.data.LocalLedgerRepository
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.SubscriptionRecord
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SubscriptionManagementPage(
    subscriptions: List<SubscriptionRecord>,
    fixedIncomes: List<FixedIncomeRecord> = emptyList(),
    userCategories: List<String>,
    accounts: List<AccountRecord> = emptyList(),
    onBack: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    var localAccounts by remember { mutableStateOf(accounts) }
    var isSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty()) {
            localAccounts = accounts
        } else {
            LocalLedgerRepository.getAccountsSummary(context).onSuccess {
                localAccounts = it.accounts
            }
        }
    }

    var editingSub by remember { mutableStateOf<SubscriptionRecord?>(null) }
    var showAddSubDialog by remember { mutableStateOf(false) }

    var editingIncome by remember { mutableStateOf<FixedIncomeRecord?>(null) }
    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = if (pagerState.currentPage == 0) "固定支出与订阅" else "固定收入计划",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Slate800
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isSyncing = true
                                    val syncRes = NativeLedgerApi.syncToolsFromOnline(context)
                                    isSyncing = false
                                    syncRes.onSuccess {
                                        Toast.makeText(context, "已从在线数据库同步固定支出与收入", Toast.LENGTH_SHORT).show()
                                        LocalLedgerRepository.getAccountsSummary(context).onSuccess { localAccounts = it.accounts }
                                        onRefresh()
                                    }.onFailure {
                                        Toast.makeText(context, "同步失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isSyncing
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "同步在线数据",
                                tint = if (pagerState.currentPage == 0) PrimaryTeal else IncomeGreen
                            )
                        }
                        IconButton(onClick = {
                            if (pagerState.currentPage == 0) showAddSubDialog = true else showAddIncomeDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "添加",
                                tint = if (pagerState.currentPage == 0) PrimaryTeal else IncomeGreen
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )

                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.White,
                    contentColor = if (pagerState.currentPage == 0) PrimaryTeal else IncomeGreen,
                    divider = { HorizontalDivider(color = Slate200) }
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("固定支出 (${subscriptions.size})", fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("固定收入 (${fixedIncomes.size})", fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                }
            }
        },
        containerColor = Slate50
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { page ->
            if (page == 0) {
                // 固定支出列表
                val totalCost = subscriptions.sumOf { it.cost }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "月度固定支出概览", fontSize = 13.sp, color = Slate600)
                                Text(
                                    text = "共 ${subscriptions.size} 项周期性订阅",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Text(
                                text = String.format(Locale.getDefault(), "RM %.2f / 月", totalCost),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryTeal
                            )
                        }
                    }
                }

                if (subscriptions.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryTealSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Repeat,
                                    contentDescription = null,
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无活跃订阅项目",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "添加 Netflix、Spotify、iCloud、宽带或房租等每月固定支出",
                                fontSize = 12.sp,
                                color = Slate500,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddSubDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("添加首个订阅")
                            }
                        }
                    }
                    items(subscriptions) { sub ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingSub = sub },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Slate200)
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = sub.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "分类: ${sub.category} · 扣款: ${sub.nextBillingDate}",
                                            fontSize = 11.sp,
                                            color = Slate500
                                        )
                                    }
                                    Text(
                                        text = String.format(Locale.getDefault(), "RM %.2f", sub.cost),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate900
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = Slate100)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (!sub.accountName.isNullOrBlank()) Teal50 else Slate100
                                    ) {
                                        val accText = sub.accountName ?: "未绑定账户"
                                        val icon = when {
                                            accText.contains("Touch") || accText.contains("TNG") -> "📱 "
                                            accText.contains("卡") || accText.contains("Credit") -> "💳 "
                                            accText.contains("现金") -> "💵 "
                                            else -> "🏦 "
                                        }
                                        Text(
                                            text = if (!sub.accountName.isNullOrBlank()) "$icon$accText" else "未绑定账户",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (!sub.accountName.isNullOrBlank()) PrimaryTeal else Slate500,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val res = NativeLedgerApi.recordSubscriptionAsTransaction(context, sub)
                                                    res.onSuccess {
                                                        Toast.makeText(context, "已成功将「${sub.name}」记入本月支出", Toast.LENGTH_SHORT).show()
                                                        onRefresh()
                                                    }.onFailure {
                                                        Toast.makeText(context, "记账失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("一键记入", fontSize = 11.sp, color = PrimaryTeal, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(onClick = { editingSub = sub }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Slate400, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } else {
                // 固定收入列表
                val totalIncome = fixedIncomes.filter { it.isActive }.sumOf { it.amount }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "月度固定收入计划", fontSize = 13.sp, color = Slate600)
                                Text(
                                    text = "共 ${fixedIncomes.size} 项计划入账",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Text(
                                text = String.format(Locale.getDefault(), "+RM %.2f / 月", totalIncome),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = IncomeGreen
                            )
                        }
                    }
                }

                if (fixedIncomes.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(IncomeGreen.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = IncomeGreen,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无固定收入计划",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "添加每月全职薪资、副业、租金收入或固定理财分红",
                                fontSize = 12.sp,
                                color = Slate500,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddIncomeDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = IncomeGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("添加首个固定收入")
                            }
                        }
                    }
                } else {
                    items(fixedIncomes) { income ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingIncome = income },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Slate200)
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(IncomeGreen.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccountBalanceWallet,
                                                contentDescription = null,
                                                tint = IncomeGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = income.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate900
                                            )
                                            Text(
                                                text = "周期: ${income.arrivalDay} · ${income.category}",
                                                fontSize = 11.sp,
                                                color = Slate500
                                            )
                                        }
                                    }

                                    Text(
                                        text = String.format(Locale.getDefault(), "+RM %.2f", income.amount),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = IncomeGreen
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = Slate100)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (income.isActive) Teal50 else Slate100
                                    ) {
                                        Text(
                                            text = if (income.isActive) "✓ 生效中" else "已暂停",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (income.isActive) PrimaryTeal else Slate500,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val res = NativeLedgerApi.recordFixedIncomeAsTransaction(context, income)
                                                    res.onSuccess {
                                                        Toast.makeText(context, "已成功将「${income.name}」记入本月流水", Toast.LENGTH_SHORT).show()
                                                        onRefresh()
                                                    }.onFailure {
                                                        Toast.makeText(context, "入账记账失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, IncomeGreen.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = IncomeGreen, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("一键记入", fontSize = 11.sp, color = IncomeGreen, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(onClick = { editingIncome = income }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Slate400, modifier = Modifier.size(16.dp))
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
}

    // 新增/编辑订阅子弹窗
    val activeSub = editingSub
    if (showAddSubDialog || activeSub != null) {
        var nameInput by remember { mutableStateOf(activeSub?.name ?: "") }
        var costInput by remember { mutableStateOf(if (activeSub != null) String.format(Locale.getDefault(), "%.2f", activeSub.cost) else "") }
        var categoryInput by remember { mutableStateOf(activeSub?.category ?: if (userCategories.isNotEmpty()) userCategories.first() else "日常") }
        var dateInput by remember { mutableStateOf(activeSub?.nextBillingDate ?: "每月1号") }
        var selectedAccountId by remember { mutableStateOf(activeSub?.accountId) }
        var selectedAccountName by remember { mutableStateOf(activeSub?.accountName) }
        var showDayPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showAddSubDialog = false
                editingSub = null
            },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = if (activeSub != null) "编辑订阅项目" else "添加新的订阅/固定支出",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("项目名称 (如: Netflix, iCloud, 宽带)", color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = costInput,
                        onValueChange = { costInput = it },
                        label = { Text("周期扣费金额 (MYR)", color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("记账分类 (如: 娱乐, 账单)", color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    // 扣款/支付账户选择
                    Column {
                        Text("扣款支付账户:", fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val noneSelected = selectedAccountId == null && selectedAccountName == null
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (noneSelected) PrimaryTeal else Slate100,
                                modifier = Modifier.clickable {
                                    selectedAccountId = null
                                    selectedAccountName = null
                                }
                            ) {
                                Text(
                                    text = "未绑定账户",
                                    fontSize = 11.sp,
                                    fontWeight = if (noneSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (noneSelected) Color.White else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            localAccounts.forEach { acc ->
                                val sel = selectedAccountId == acc.id || (selectedAccountId == null && selectedAccountName == acc.name)
                                val icon = when {
                                    acc.name.contains("Touch") || acc.name.contains("TNG") -> "📱 "
                                    acc.name.contains("卡") || acc.name.contains("Credit") -> "💳 "
                                    acc.name.contains("现金") -> "💵 "
                                    else -> "🏦 "
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) PrimaryTeal else Slate100,
                                    modifier = Modifier.clickable {
                                        selectedAccountId = acc.id
                                        selectedAccountName = acc.name
                                    }
                                ) {
                                    Text(
                                        text = "$icon${acc.name}",
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dateInput,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("下次扣款周期 (点击选择日期)", color = Slate600) },
                            trailingIcon = {
                                IconButton(onClick = { showDayPicker = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = "选择扣款日",
                                        tint = PrimaryTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDayPicker = true }
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeSub != null) {
                        TextButton(
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    NativeLedgerApi.deleteSubscription(context, activeSub.id)
                                    isSubmitting = false
                                    editingSub = null
                                    Toast.makeText(context, "订阅已删除", Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            },
                            enabled = !isSubmitting
                        ) {
                            Text("删除", color = ExpenseRed)
                        }
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val cost = costInput.toDoubleOrNull() ?: 0.0
                            if (name.isEmpty() || cost <= 0.0) {
                                Toast.makeText(context, "请输入有效的名称与大于0的金额", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveSubscription(
                                    context = context,
                                    id = activeSub?.id,
                                    name = name,
                                    category = categoryInput.trim().ifEmpty { "日常" },
                                    cost = cost,
                                    currency = "MYR",
                                    nextBillingDate = dateInput.trim().ifEmpty { "每月1号" },
                                    autoRenew = true,
                                    accountId = selectedAccountId,
                                    accountName = selectedAccountName
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    showAddSubDialog = false
                                    editingSub = null
                                    Toast.makeText(context, "订阅已保存", Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSubmitting
                    ) {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddSubDialog = false
                        editingSub = null
                    }
                ) {
                    Text("取消", color = Slate600)
                }
            }
        )

        if (showDayPicker) {
            MonthlyDayPickerDialog(
                title = "选择每月扣款日",
                currentValue = dateInput,
                accentColor = PrimaryTeal,
                onDaySelected = { selected ->
                    dateInput = selected
                    showDayPicker = false
                },
                onDismissRequest = { showDayPicker = false }
            )
        }
    }

    // 新增/编辑固定收入子弹窗
    val activeIncome = editingIncome
    if (showAddIncomeDialog || activeIncome != null) {
        var nameInput by remember { mutableStateOf(activeIncome?.name ?: "") }
        var amountInput by remember { mutableStateOf(if (activeIncome != null) String.format(Locale.getDefault(), "%.2f", activeIncome.amount) else "") }
        var categoryInput by remember { mutableStateOf(activeIncome?.category ?: "工资") }
        var dayInput by remember { mutableStateOf(activeIncome?.arrivalDay ?: "每月25号") }
        var isActiveInput by remember { mutableStateOf(activeIncome?.isActive ?: true) }
        var selectedAccountId by remember { mutableStateOf(activeIncome?.accountId) }
        var selectedAccountName by remember { mutableStateOf(activeIncome?.accountName) }
        var showDayPicker by remember { mutableStateOf(false) }

        val incomeCategoryOptions = listOf("工资", "兼职", "租金", "投资", "奖金", "报销", "其他")

        AlertDialog(
            onDismissRequest = {
                showAddIncomeDialog = false
                editingIncome = null
            },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = if (activeIncome != null) "编辑固定收入项目" else "添加新的固定收入",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("收入来源名称 (如: 全职月薪、副业、租金)", color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("每月预计入账金额 (MYR)", color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    // 分类快捷标签
                    Column {
                        Text("收入分类:", fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            incomeCategoryOptions.forEach { opt ->
                                val sel = categoryInput == opt
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) IncomeGreen else Slate100,
                                    modifier = Modifier.clickable { categoryInput = opt }
                                ) {
                                    Text(
                                        text = opt,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 入账/收款账户选择
                    Column {
                        Text("入账收款账户:", fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val noneSelected = selectedAccountId == null && selectedAccountName == null
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (noneSelected) IncomeGreen else Slate100,
                                modifier = Modifier.clickable {
                                    selectedAccountId = null
                                    selectedAccountName = null
                                }
                            ) {
                                Text(
                                    text = "未绑定账户",
                                    fontSize = 11.sp,
                                    fontWeight = if (noneSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (noneSelected) Color.White else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            localAccounts.forEach { acc ->
                                val sel = selectedAccountId == acc.id || (selectedAccountId == null && selectedAccountName == acc.name)
                                val icon = when {
                                    acc.name.contains("Touch") || acc.name.contains("TNG") -> "📱 "
                                    acc.name.contains("卡") || acc.name.contains("Credit") -> "💳 "
                                    acc.name.contains("现金") -> "💵 "
                                    else -> "🏦 "
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) IncomeGreen else Slate100,
                                    modifier = Modifier.clickable {
                                        selectedAccountId = acc.id
                                        selectedAccountName = acc.name
                                    }
                                ) {
                                    Text(
                                        text = "$icon${acc.name}",
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dayInput,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("预计入账周期 (点击选择日期)", color = Slate600) },
                            trailingIcon = {
                                IconButton(onClick = { showDayPicker = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = "选择入账日",
                                        tint = IncomeGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDayPicker = true }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isActiveInput,
                            onCheckedChange = { isActiveInput = it },
                            colors = CheckboxDefaults.colors(checkedColor = IncomeGreen)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("计划生效中", fontSize = 13.sp, color = Slate800)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeIncome != null) {
                        TextButton(
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    NativeLedgerApi.deleteFixedIncome(context, activeIncome.id)
                                    isSubmitting = false
                                    editingIncome = null
                                    Toast.makeText(context, "固定收入已删除", Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            },
                            enabled = !isSubmitting
                        ) {
                            Text("删除", color = ExpenseRed)
                        }
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val amount = amountInput.toDoubleOrNull() ?: 0.0
                            if (name.isEmpty() || amount <= 0.0) {
                                Toast.makeText(context, "请输入有效的收入名称与大于0的金额", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveFixedIncome(
                                    context = context,
                                    id = activeIncome?.id,
                                    name = name,
                                    category = categoryInput.trim().ifEmpty { "工资" },
                                    amount = amount,
                                    currency = "MYR",
                                    arrivalDay = dayInput.trim().ifEmpty { "每月25号" },
                                    isActive = isActiveInput,
                                    accountId = selectedAccountId,
                                    accountName = selectedAccountName
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    showAddIncomeDialog = false
                                    editingIncome = null
                                    Toast.makeText(context, "固定收入已保存", Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IncomeGreen),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSubmitting
                    ) {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddIncomeDialog = false
                        editingIncome = null
                    }
                ) {
                    Text("取消", color = Slate600)
                }
            }
        )

        if (showDayPicker) {
            MonthlyDayPickerDialog(
                title = "选择每月入账日",
                currentValue = dayInput,
                accentColor = IncomeGreen,
                onDaySelected = { selected ->
                    dayInput = selected
                    showDayPicker = false
                },
                onDismissRequest = { showDayPicker = false }
            )
        }
    }
}

/**
 * 优雅的纯白无偏色每月周期日期选择弹窗 (1 ~ 31 号 + 月末)
 */
@Composable
fun MonthlyDayPickerDialog(
    title: String = "选择每月周期日期",
    currentValue: String,
    accentColor: Color = PrimaryTeal,
    onDaySelected: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val initialDayNum = remember(currentValue) {
        if (currentValue.contains("底") || currentValue.contains("末")) {
            -1
        } else {
            Regex("\\d+").find(currentValue)?.value?.toIntOrNull() ?: 1
        }
    }

    var selectedDay by remember { mutableStateOf(initialDayNum) }

    val quickOptions = listOf(
        1 to "1号",
        5 to "5号",
        10 to "10号",
        15 to "15号",
        20 to "20号",
        25 to "25号",
        -1 to "月底"
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "点击数字直接选定每月的固定执行日期",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 快捷推荐标签
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "快捷推荐",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate700
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickOptions.forEach { (day, label) ->
                            val isSel = selectedDay == day
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) accentColor else Slate100,
                                modifier = Modifier.clickable {
                                    selectedDay = day
                                    val formatted = if (day == -1) "每月最后一天(月底)" else "每月${day}号"
                                    onDaySelected(formatted)
                                }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else Slate700,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Slate200, thickness = 0.8.dp)

                // 1 ~ 31 号 日历网格
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全部日期 (1 ~ 31 号)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate700
                        )
                        Text(
                            text = if (selectedDay == -1) "当前: 月底" else "当前: 每月${selectedDay}号",
                            fontSize = 11.sp,
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 7 列日历网格
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in 0..4) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (col in 0..6) {
                                    val day = row * 7 + col + 1
                                    if (day <= 31) {
                                        val isSel = selectedDay == day
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) accentColor else Slate100)
                                                .clickable {
                                                    selectedDay = day
                                                    onDaySelected("每月${day}号")
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$day",
                                                fontSize = 13.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSel) Color.White else Slate800
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.size(36.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val formatted = if (selectedDay == -1) "每月最后一天(月底)" else "每月${selectedDay}号"
                    onDaySelected(formatted)
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消", color = Slate600)
            }
        }
    )
}

