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
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.foundation.verticalScroll
import com.siangloh.ledger.R
import androidx.compose.foundation.ExperimentalFoundationApi
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.FixedIncomeRecord
import com.siangloh.ledger.data.FixedSavingsRecord
import com.siangloh.ledger.data.LocalLedgerRepository
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.SubscriptionRecord
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

private val SavingsBlue = Color(0xFF0284C7)
private val SavingsBg = Color(0xFFF0F9FF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SubscriptionManagementPage(
    subscriptions: List<SubscriptionRecord>,
    fixedSavings: List<FixedSavingsRecord> = emptyList(),
    fixedIncomes: List<FixedIncomeRecord> = emptyList(),
    userCategories: List<String> = emptyList(),
    userIncomeCategories: List<String> = emptyList(),
    accounts: List<AccountRecord> = emptyList(),
    onBack: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var localAccounts by remember { mutableStateOf(accounts) }
    var isSyncing by remember { mutableStateOf(false) }

    var localExpenseCategories by remember {
        mutableStateOf(userCategories.ifEmpty { listOf("餐饮", "交通", "杂货", "账单", "房租", "购物", "娱乐", "医疗", "其他") })
    }
    var localIncomeCategories by remember {
        mutableStateOf(userIncomeCategories.ifEmpty { listOf("工资", "兼职", "副业", "租金", "投资", "奖金", "分红", "报销", "其他") })
    }

    LaunchedEffect(userCategories, userIncomeCategories) {
        if (userCategories.isNotEmpty()) localExpenseCategories = userCategories
        if (userIncomeCategories.isNotEmpty()) localIncomeCategories = userIncomeCategories
        NativeLedgerApi.getCategories(context).onSuccess { res ->
            val expNames = res.expenses.map { it.name }.filter { it.isNotBlank() }
            if (expNames.isNotEmpty()) localExpenseCategories = expNames
            val incNames = res.incomes.map { it.name }.filter { it.isNotBlank() }
            if (incNames.isNotEmpty()) localIncomeCategories = incNames
        }
    }

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

    var editingSavings by remember { mutableStateOf<FixedSavingsRecord?>(null) }
    var showAddSavingsDialog by remember { mutableStateOf(false) }

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
                            text = when (pagerState.currentPage) {
                                0 -> stringResource(R.string.sub_tab_expense)
                                1 -> stringResource(R.string.sub_tab_savings)
                                else -> stringResource(R.string.sub_tab_income)
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
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
                                        Toast.makeText(context, context.getString(R.string.sub_sync_success), Toast.LENGTH_SHORT).show()
                                        LocalLedgerRepository.getAccountsSummary(context).onSuccess { localAccounts = it.accounts }
                                        onRefresh()
                                    }.onFailure {
                                        Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isSyncing
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = stringResource(R.string.sub_sync_btn_desc),
                                tint = when (pagerState.currentPage) {
                                    0 -> PrimaryTeal
                                    1 -> SavingsBlue
                                    else -> IncomeGreen
                                }
                            )
                        }
                        IconButton(onClick = {
                            when (pagerState.currentPage) {
                                0 -> showAddSubDialog = true
                                1 -> showAddSavingsDialog = true
                                else -> showAddIncomeDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.common_add),
                                tint = when (pagerState.currentPage) {
                                    0 -> PrimaryTeal
                                    1 -> SavingsBlue
                                    else -> IncomeGreen
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )

                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.White,
                    contentColor = when (pagerState.currentPage) {
                        0 -> PrimaryTeal
                        1 -> SavingsBlue
                        else -> IncomeGreen
                    },
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
                                Text(stringResource(R.string.sub_tab_expense_count_fmt, subscriptions.size), fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal)
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
                                Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.sub_tab_savings_count_fmt, fixedSavings.size), fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(2)
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.sub_tab_income_count_fmt, fixedIncomes.size), fontWeight = if (pagerState.currentPage == 2) FontWeight.Bold else FontWeight.Normal)
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
                                Text(text = stringResource(R.string.sub_expense_overview), fontSize = 13.sp, color = Slate600)
                                Text(
                                    text = stringResource(R.string.sub_expense_count_fmt, subscriptions.size),
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Text(
                                text = stringResource(R.string.sub_amount_monthly_fmt, String.format(Locale.getDefault(), "%.2f", totalCost)),
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
                                text = stringResource(R.string.sub_empty_expense_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.sub_empty_expense_desc),
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
                                Text(stringResource(R.string.sub_add_first_expense))
                            }
                        }
                    }
                } else {
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
                                            text = stringResource(R.string.sub_item_cat_date_fmt, CategoryUiHelper.getLocalizedCategoryName(context, sub.category), CategoryUiHelper.formatBillingCycle(context, sub.nextBillingDate)),
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
                                        val accText = sub.accountName ?: stringResource(R.string.sub_unbound_account)
                                        val icon = when {
                                            accText.contains("Touch") || accText.contains("TNG") -> "📱 "
                                            accText.contains("卡") || accText.contains("Credit", ignoreCase = true) || accText.contains("Debit", ignoreCase = true) || accText.contains("Card", ignoreCase = true) || accText.contains("Kad", ignoreCase = true) -> "💳 "
                                            accText.contains("现金") || accText.contains("Cash", ignoreCase = true) || accText.contains("Tunai", ignoreCase = true) -> "💵 "
                                            else -> "🏦 "
                                        }
                                        Text(
                                            text = if (!sub.accountName.isNullOrBlank()) "$icon$accText" else stringResource(R.string.sub_unbound_account),
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
                                                        Toast.makeText(context, context.getString(R.string.sub_quick_record_success_fmt, sub.name), Toast.LENGTH_SHORT).show()
                                                        onRefresh()
                                                    }.onFailure {
                                                        Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
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
                                            Text(stringResource(R.string.sub_quick_record_btn), fontSize = 11.sp, color = PrimaryTeal, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(onClick = { editingSub = sub }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit), tint = Slate400, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } else if (page == 1) {
                // 固定储蓄列表
                val totalSavings = fixedSavings.filter { it.isActive }.sumOf { it.amount }
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
                                Text(text = stringResource(R.string.sub_savings_overview), fontSize = 13.sp, color = Slate600)
                                Text(
                                    text = stringResource(R.string.sub_savings_count_fmt, fixedSavings.size),
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Text(
                                text = stringResource(R.string.sub_amount_monthly_fmt, String.format(Locale.getDefault(), "%.2f", totalSavings)),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SavingsBlue
                            )
                        }
                    }
                }

                if (fixedSavings.isEmpty()) {
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
                                    .background(SavingsBlue.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Savings,
                                    contentDescription = null,
                                    tint = SavingsBlue,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.sub_empty_savings_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate700
                            )
                            Text(
                                text = stringResource(R.string.sub_empty_savings_desc),
                                fontSize = 12.sp,
                                color = Slate400,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddSavingsDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = SavingsBlue),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.sub_add_first_savings), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    items(fixedSavings) { saving ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
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
                                                .background(SavingsBlue.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Savings,
                                                contentDescription = null,
                                                tint = SavingsBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = saving.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate900
                                            )
                                            Text(
                                                text = stringResource(R.string.sub_saving_item_fmt, CategoryUiHelper.formatBillingCycle(context, saving.arrivalDay), CategoryUiHelper.getLocalizedCategoryName(context, saving.category)),
                                                fontSize = 11.sp,
                                                color = Slate500
                                            )
                                        }
                                    }

                                    Text(
                                        text = String.format(Locale.getDefault(), "RM %.2f", saving.amount),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SavingsBlue
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
                                    val accText = saving.accountName ?: stringResource(R.string.sub_unbound_account)
                                    val icon = when {
                                        accText.contains("Touch") || accText.contains("TNG") -> "📱 "
                                        accText.contains("卡") || accText.contains("Credit", ignoreCase = true) || accText.contains("Debit", ignoreCase = true) || accText.contains("Card", ignoreCase = true) || accText.contains("Kad", ignoreCase = true) -> "💳 "
                                        accText.contains("现金") || accText.contains("Cash", ignoreCase = true) || accText.contains("Tunai", ignoreCase = true) -> "💵 "
                                        else -> "🏦 "
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (!saving.accountName.isNullOrBlank()) SavingsBg else Slate100
                                    ) {
                                        Text(
                                            text = if (!saving.accountName.isNullOrBlank()) "$icon$accText" else stringResource(R.string.sub_unbound_account),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (!saving.accountName.isNullOrBlank()) SavingsBlue else Slate500,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (saving.isActive) SavingsBg else Slate100
                                        ) {
                                            Text(
                                                text = if (saving.isActive) stringResource(R.string.sub_status_active) else stringResource(R.string.sub_status_paused),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (saving.isActive) SavingsBlue else Slate500,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val res = NativeLedgerApi.recordFixedSavingsAsTransaction(context, saving)
                                                    res.onSuccess {
                                                        Toast.makeText(context, context.getString(R.string.sub_quick_deposit_success_fmt, saving.name), Toast.LENGTH_SHORT).show()
                                                        onRefresh()
                                                    }.onFailure {
                                                        Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, SavingsBlue.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = SavingsBlue, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.sub_quick_deposit_btn), fontSize = 11.sp, color = SavingsBlue, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(onClick = { editingSavings = saving }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit), tint = Slate400, modifier = Modifier.size(16.dp))
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
                                Text(text = stringResource(R.string.sub_income_overview), fontSize = 13.sp, color = Slate600)
                                Text(
                                    text = stringResource(R.string.sub_income_count_fmt, fixedIncomes.size),
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Text(
                                text = stringResource(R.string.sub_income_monthly_fmt, String.format(Locale.getDefault(), "%.2f", totalIncome)),
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
                                text = stringResource(R.string.sub_empty_income_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.sub_empty_income_desc),
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
                                Text(stringResource(R.string.sub_add_first_income))
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
                                                text = stringResource(R.string.sub_saving_item_fmt, CategoryUiHelper.formatBillingCycle(context, income.arrivalDay), CategoryUiHelper.getLocalizedCategoryName(context, income.category)),
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
                                            text = if (income.isActive) stringResource(R.string.sub_status_active) else stringResource(R.string.sub_status_paused),
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
                                                        Toast.makeText(context, context.getString(R.string.sub_quick_income_success_fmt, income.name), Toast.LENGTH_SHORT).show()
                                                        onRefresh()
                                                    }.onFailure {
                                                        Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
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
                                            Text(stringResource(R.string.sub_quick_record_btn), fontSize = 11.sp, color = IncomeGreen, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(onClick = { editingIncome = income }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit), tint = Slate400, modifier = Modifier.size(16.dp))
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
                    text = if (activeSub != null) stringResource(R.string.sub_dialog_edit_expense) else stringResource(R.string.sub_dialog_add_expense),
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
                        label = { Text(stringResource(R.string.sub_name_expense_hint), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = costInput,
                        onValueChange = { costInput = it },
                        label = { Text(stringResource(R.string.sub_amount_expense_label), color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text(stringResource(R.string.sub_cat_label), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    // 支出分类快捷点选
                    Column {
                        Text(stringResource(R.string.sub_quick_cat_label), fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val options = (localExpenseCategories + listOf("订阅", "房租", "账单", "通讯", "其他")).distinct()
                            options.forEach { opt ->
                                val sel = categoryInput == opt
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) PrimaryTeal else Slate100,
                                    modifier = Modifier.clickable { categoryInput = opt }
                                ) {
                                    Text(
                                        text = CategoryUiHelper.getLocalizedCategoryName(context, opt),
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 扣款/支付账户选择
                    Column {
                        Text(stringResource(R.string.sub_account_label), fontSize = 12.sp, color = Slate600)
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
                                    text = stringResource(R.string.sub_unbound_account),
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
                                    acc.name.contains("卡") || acc.name.contains("Credit", ignoreCase = true) || acc.name.contains("Debit", ignoreCase = true) || acc.name.contains("Card", ignoreCase = true) || acc.name.contains("Kad", ignoreCase = true) -> "💳 "
                                    acc.name.contains("现金") || acc.name.contains("Cash", ignoreCase = true) || acc.name.contains("Tunai", ignoreCase = true) -> "💵 "
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
                            label = { Text(stringResource(R.string.sub_billing_cycle_label), color = Slate600) },
                            trailingIcon = {
                                IconButton(onClick = { showDayPicker = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = stringResource(R.string.sub_pick_billing_date_desc),
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
                                    Toast.makeText(context, context.getString(R.string.sub_deleted_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            },
                            enabled = !isSubmitting
                        ) {
                            Text(stringResource(R.string.common_delete), color = ExpenseRed)
                        }
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val cost = costInput.toDoubleOrNull() ?: 0.0
                            if (name.isEmpty() || cost <= 0.0) {
                                Toast.makeText(context, context.getString(R.string.sub_input_valid_name_amount), Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, context.getString(R.string.sub_saved_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSubmitting
                    ) {
                        Text(stringResource(R.string.common_save))
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
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )

        if (showDayPicker) {
            MonthlyDayPickerDialog(
                title = stringResource(R.string.sub_pick_monthly_day_title),
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


        AlertDialog(
            onDismissRequest = {
                showAddIncomeDialog = false
                editingIncome = null
            },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = if (activeIncome != null) stringResource(R.string.sub_dialog_edit_income) else stringResource(R.string.sub_dialog_add_income),
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
                        label = { Text(stringResource(R.string.sub_income_name_hint), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text(stringResource(R.string.sub_income_amount_label), color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text(stringResource(R.string.sub_income_cat_label), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    // 收入分类快捷点选
                    Column {
                        Text(stringResource(R.string.sub_income_quick_cat), fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val options = (localIncomeCategories + listOf("工资", "兼职", "副业", "租金", "投资", "奖金", "分红", "报销", "其他")).distinct()
                            options.forEach { opt ->
                                val sel = categoryInput == opt
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) IncomeGreen else Slate100,
                                    modifier = Modifier.clickable { categoryInput = opt }
                                ) {
                                    Text(
                                        text = CategoryUiHelper.getLocalizedCategoryName(context, opt),
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
                        Text(stringResource(R.string.sub_income_account_label), fontSize = 12.sp, color = Slate600)
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
                                    text = stringResource(R.string.sub_unbound_account),
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
                                    acc.name.contains("卡") || acc.name.contains("Credit", ignoreCase = true) || acc.name.contains("Debit", ignoreCase = true) || acc.name.contains("Card", ignoreCase = true) || acc.name.contains("Kad", ignoreCase = true) -> "💳 "
                                    acc.name.contains("现金") || acc.name.contains("Cash", ignoreCase = true) || acc.name.contains("Tunai", ignoreCase = true) -> "💵 "
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
                            label = { Text(stringResource(R.string.sub_income_cycle_label), color = Slate600) },
                            trailingIcon = {
                                IconButton(onClick = { showDayPicker = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = stringResource(R.string.sub_income_pick_date_desc),
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
                        Text(stringResource(R.string.sub_plan_active_switch), fontSize = 13.sp, color = Slate800)
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
                                    Toast.makeText(context, context.getString(R.string.sub_income_deleted_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            },
                            enabled = !isSubmitting
                        ) {
                            Text(stringResource(R.string.common_delete), color = ExpenseRed)
                        }
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val amount = amountInput.toDoubleOrNull() ?: 0.0
                            if (name.isEmpty() || amount <= 0.0) {
                                Toast.makeText(context, context.getString(R.string.sub_input_valid_name_amount), Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, context.getString(R.string.sub_income_saved_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IncomeGreen),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSubmitting
                    ) {
                        Text(stringResource(R.string.common_save))
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
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )

        if (showDayPicker) {
            MonthlyDayPickerDialog(
                title = stringResource(R.string.sub_pick_monthly_day_income_title),
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

    // 新增/编辑固定储蓄子弹窗
    val activeSaving = editingSavings
    if (showAddSavingsDialog || activeSaving != null) {
        var nameInput by remember { mutableStateOf(activeSaving?.name ?: "") }
        var amountInput by remember { mutableStateOf(if (activeSaving != null) String.format(Locale.getDefault(), "%.2f", activeSaving.amount) else "") }
        var categoryInput by remember { mutableStateOf(activeSaving?.category ?: "定期存款") }
        var dayInput by remember { mutableStateOf(activeSaving?.arrivalDay ?: "每月1号") }
        var selectedAccountId by remember { mutableStateOf(activeSaving?.accountId) }
        var selectedAccountName by remember { mutableStateOf(activeSaving?.accountName) }
        var isActiveInput by remember { mutableStateOf(activeSaving?.isActive ?: true) }
        var showDayPicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showAddSavingsDialog = false
                editingSavings = null
            },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = if (activeSaving != null) stringResource(R.string.sub_dialog_edit_savings) else stringResource(R.string.sub_dialog_add_savings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(stringResource(R.string.sub_savings_name_hint), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text(stringResource(R.string.sub_savings_amount_label), color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text(stringResource(R.string.sub_savings_cat_label), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )

                    // 储蓄分类快捷点选
                    Column {
                        Text(stringResource(R.string.sub_savings_quick_cat), fontSize = 12.sp, color = Slate600)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val options = listOf("定期存款", "应急基金", "投资理财", "心愿基金", "买房基金", "教育金", "退休金", "黄金储蓄", "其他")
                            options.forEach { opt ->
                                val sel = categoryInput == opt
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) SavingsBlue else Slate100,
                                    modifier = Modifier.clickable { categoryInput = opt }
                                ) {
                                    Text(
                                        text = CategoryUiHelper.getLocalizedCategoryName(context, opt),
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 存入账户选择
                    Column {
                        Text(stringResource(R.string.sub_savings_account_label), fontSize = 12.sp, color = Slate600)
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
                                color = if (noneSelected) SavingsBlue else Slate100,
                                modifier = Modifier.clickable {
                                    selectedAccountId = null
                                    selectedAccountName = null
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.sub_unbound_account),
                                    fontSize = 11.sp,
                                    fontWeight = if (noneSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (noneSelected) Color.White else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            localAccounts.forEach { acc ->
                                val sel = selectedAccountId == acc.id || (selectedAccountId == null && selectedAccountName == acc.name)
                                val icon = when {
                                    acc.type.equals("savings", ignoreCase = true) -> "🏦 "
                                    acc.type.equals("investment", ignoreCase = true) -> "📈 "
                                    acc.name.contains("Touch") || acc.name.contains("TNG") -> "📱 "
                                    else -> "💳 "
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (sel) SavingsBlue else Slate100,
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
                            label = { Text(stringResource(R.string.sub_savings_cycle_label), color = Slate600) },
                            trailingIcon = {
                                IconButton(onClick = { showDayPicker = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = stringResource(R.string.sub_savings_pick_date_desc),
                                        tint = SavingsBlue,
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
                            colors = CheckboxDefaults.colors(checkedColor = SavingsBlue)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.sub_plan_active_switch), fontSize = 13.sp, color = Slate800)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeSaving != null) {
                        TextButton(
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    NativeLedgerApi.deleteFixedSavings(context, activeSaving.id)
                                    isSubmitting = false
                                    editingSavings = null
                                    Toast.makeText(context, context.getString(R.string.sub_savings_deleted_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            },
                            enabled = !isSubmitting
                        ) {
                            Text(stringResource(R.string.common_delete), color = ExpenseRed)
                        }
                    }
                    Button(
                        onClick = {
                            val name = nameInput.trim()
                            val amount = amountInput.toDoubleOrNull() ?: 0.0
                            if (name.isEmpty() || amount <= 0.0) {
                                Toast.makeText(context, context.getString(R.string.sub_input_valid_name_amount), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveFixedSavings(
                                    context = context,
                                    id = activeSaving?.id,
                                    name = name,
                                    category = categoryInput.trim().ifEmpty { "定期存款" },
                                    amount = amount,
                                    currency = "MYR",
                                    arrivalDay = dayInput.trim().ifEmpty { "每月1号" },
                                    isActive = isActiveInput,
                                    accountId = selectedAccountId,
                                    accountName = selectedAccountName
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    showAddSavingsDialog = false
                                    editingSavings = null
                                    Toast.makeText(context, context.getString(R.string.sub_savings_saved_toast), Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SavingsBlue),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSubmitting
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddSavingsDialog = false
                        editingSavings = null
                    }
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )

        if (showDayPicker) {
            MonthlyDayPickerDialog(
                title = stringResource(R.string.sub_pick_monthly_day_savings_title),
                currentValue = dayInput,
                accentColor = SavingsBlue,
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
    title: String = stringResource(R.string.sub_cycle_dialog_default_title),
    currentValue: String,
    accentColor: Color = PrimaryTeal,
    onDaySelected: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val initialDayNum = remember(currentValue) {
        if (currentValue.contains("底") || currentValue.contains("末") || currentValue.contains("end", ignoreCase = true) || currentValue.contains("akhir", ignoreCase = true)) {
            -1
        } else {
            Regex("\\d+").find(currentValue)?.value?.toIntOrNull() ?: 1
        }
    }

    var selectedDay by remember { mutableStateOf(initialDayNum) }

    val quickOptions = listOf(
        1 to stringResource(R.string.sub_day_fmt, 1),
        5 to stringResource(R.string.sub_day_fmt, 5),
        10 to stringResource(R.string.sub_day_fmt, 10),
        15 to stringResource(R.string.sub_day_fmt, 15),
        20 to stringResource(R.string.sub_day_fmt, 20),
        25 to stringResource(R.string.sub_day_fmt, 25),
        -1 to stringResource(R.string.sub_day_end_of_month)
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
                        text = stringResource(R.string.sub_cycle_dialog_hint),
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
                        text = stringResource(R.string.sub_quick_recommendation),
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
                                    val formatted = if (day == -1) context.getString(R.string.sub_day_end_of_month) else context.getString(R.string.sub_day_fmt, day)
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
                            text = stringResource(R.string.sub_all_days_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate700
                        )
                        Text(
                            text = if (selectedDay == -1) stringResource(R.string.sub_current_day_end_of_month) else stringResource(R.string.sub_current_day_fmt, selectedDay),
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
                                                    onDaySelected(context.getString(R.string.sub_day_fmt, day))
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
                    val formatted = if (selectedDay == -1) context.getString(R.string.sub_day_end_of_month) else context.getString(R.string.sub_day_fmt, selectedDay)
                    onDaySelected(formatted)
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_cancel), color = Slate600)
            }
        }
    )
}

