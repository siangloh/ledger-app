package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.AccountsSummary
import com.siangloh.ledger.data.LocalDataStore
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.ui.compose.components.AccountsSkeletonScreen
import com.siangloh.ledger.ui.compose.components.BrandIcon
import com.siangloh.ledger.ui.compose.components.SourceBrandBadgeHelper
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: (() -> Unit)? = null) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var summary by remember {
        mutableStateOf<AccountsSummary?>(
            LocalDataStore.getAccounts(context)?.let {
                try {
                    NativeLedgerApi.parseAccountsSummaryJson(JSONObject(it))
                } catch (_: Exception) { null }
            }
        )
    }
    var isLoading by remember { mutableStateOf(false) }
    var hideBalances by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<AccountRecord?>(null) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var transferInitialFromId by remember { mutableStateOf<Int?>(null) }
    var selectedDetailAccount by remember { mutableStateOf<AccountRecord?>(null) }
    var adjustingAccount by remember { mutableStateOf<AccountRecord?>(null) }
    var reconcilingAccount by remember { mutableStateOf<AccountRecord?>(null) }

    fun loadAccounts() {
        if (summary == null) isLoading = true
        scope.launch {
            val res = NativeLedgerApi.getAccounts(context)
            isLoading = false
            res.onSuccess {
                summary = it
            }.onFailure {
                if (summary == null) {
                    Toast.makeText(context, "获取账户失败：${it.message}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "网络受阻，已呈现本地离线快照", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAccounts()
    }

    // 实时监听流水与数据变动广播 (DATA_SYNCED)，静默更新账户余额与总资产，数字就地平滑滚动动画
    DisposableEffect(Unit) {
        val filter = android.content.IntentFilter().apply {
            addAction("com.siangloh.ledger.DATA_SYNCED")
            addAction("com.siangloh.ledger.SYNC_COMPLETED")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                loadAccounts()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cf_back),
                                    tint = Slate900
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = stringResource(R.string.accounts_title),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                    }
                    IconButton(
                        onClick = { loadAccounts() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                            tint = Slate600
                        )
                    }
                }
            }
        }
    ) { paddingVals ->
        if (isLoading && summary == null) {
            AccountsSkeletonScreen(modifier = Modifier.padding(paddingVals))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingVals)
                    .background(Slate50),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // 净资产全景卡片 (现代卡片 + 隐私遮罩开关 + 资产/负债双指标)
            item {
                val netWorth = summary?.netWorth ?: 0.0
                val totalAssets = summary?.totalAssets ?: 0.0
                val totalLiabilities = summary?.totalLiabilities ?: 0.0
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Slate200),
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.net_assets),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate500
                            )
                            IconButton(
                                onClick = { hideBalances = !hideBalances },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (hideBalances) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = Slate400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hideBalances) "MYR ••••••" else "MYR ${String.format(Locale.US, "%,.2f", netWorth)}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Slate900,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Slate50,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(IncomeGreen))
                                        Text(stringResource(R.string.total_assets), fontSize = 11.sp, color = Slate500)
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (hideBalances) "••••" else "MYR ${String.format(Locale.US, "%,.2f", totalAssets)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = IncomeGreen,
                                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(ExpenseRed))
                                        Text(stringResource(R.string.total_liabilities), fontSize = 11.sp, color = Slate500)
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (hideBalances) "••••" else "MYR ${String.format(Locale.US, "%,.2f", totalLiabilities)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ExpenseRed,
                                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 快捷功能按键行 (账户不足 2 个时禁用转账并给予清晰指引)
            item {
                val accountsList = summary?.accounts ?: emptyList()
                val canTransfer = accountsList.size >= 2

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (!canTransfer) {
                                Toast.makeText(context, context.getString(R.string.accounts_min_transfer_toast), Toast.LENGTH_SHORT).show()
                            } else {
                                transferInitialFromId = null
                                showTransferDialog = true
                            }
                        },
                        enabled = canTransfer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryTeal,
                            disabledContainerColor = Slate200
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .defaultMinSize(minHeight = 46.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (canTransfer) Color.White else Slate400
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.transfer_btn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canTransfer) Color.White else Slate400,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            editingAccount = null
                            showAddAccountDialog = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, PrimaryTeal.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Teal50,
                            contentColor = PrimaryTeal
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .defaultMinSize(minHeight = 46.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.add_account_btn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryTeal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 账户分组列表 (电子钱包 / 银行账户 / 信用卡 / 现金 / 投资与其他)
            val accountsList = summary?.accounts ?: emptyList()
            if (accountsList.isEmpty() && !isLoading) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Teal50,
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountBalance,
                                        contentDescription = null,
                                        tint = PrimaryTeal,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(stringResource(R.string.no_accounts_configured), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.no_accounts_help), fontSize = 12.sp, color = Slate400, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                val groupedSections = listOf(
                    Triple(
                        context.getString(R.string.accounts_group_ewallet),
                        Icons.Rounded.AccountBalanceWallet,
                        accountsList.filter { it.type.equals("e_wallet", ignoreCase = true) }
                    ),
                    Triple(
                        context.getString(R.string.accounts_group_bank),
                        Icons.Rounded.AccountBalance,
                        accountsList.filter { it.type.equals("savings", ignoreCase = true) || it.type.equals("current", ignoreCase = true) }
                    ),
                    Triple(
                        context.getString(R.string.accounts_group_credit),
                        Icons.Rounded.CreditCard,
                        accountsList.filter { it.type.equals("credit_card", ignoreCase = true) }
                    ),
                    Triple(
                        context.getString(R.string.accounts_group_cash),
                        Icons.Rounded.Payments,
                        accountsList.filter { it.type.equals("cash", ignoreCase = true) }
                    ),
                    Triple(
                        context.getString(R.string.accounts_group_invest),
                        Icons.Rounded.Savings,
                        accountsList.filter {
                            val t = it.type.lowercase(Locale.ROOT)
                            t !in listOf("e_wallet", "savings", "current", "credit_card", "cash")
                        }
                    )
                ).filter { it.third.isNotEmpty() }

                groupedSections.forEach { (groupTitle, groupIcon, groupAccounts) ->
                    val groupTotal = groupAccounts.sumOf { it.balance }
                    item(key = "header_$groupTitle") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = groupIcon,
                                    contentDescription = null,
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = groupTitle,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate800
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Slate200.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = "${groupAccounts.size}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate600,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (hideBalances) "••••" else "MYR ${String.format(Locale.US, "%,.2f", groupTotal)}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate600,
                                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                            )
                        }
                    }

                    items(groupAccounts, key = { it.id }) { acc ->
                        AccountCard(
                            account = acc,
                            hideBalances = hideBalances,
                            onClick = { selectedDetailAccount = acc }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }
}

    // 单卡详情底部抽屉 (近期流水 + 一键调余额 + 转账 + 对账 + 编辑)
    selectedDetailAccount?.let { detailAcc ->
        val latestAcc = summary?.accounts?.firstOrNull { it.id == detailAcc.id } ?: detailAcc
        AccountDetailSheet(
            account = latestAcc,
            canTransfer = (summary?.accounts?.size ?: 0) >= 2,
            onDismiss = { selectedDetailAccount = null },
            onAdjustBalance = {
                selectedDetailAccount = null
                adjustingAccount = latestAcc
            },
            onTransfer = {
                selectedDetailAccount = null
                transferInitialFromId = latestAcc.id
                showTransferDialog = true
            },
            onReconcile = {
                selectedDetailAccount = null
                reconcilingAccount = latestAcc
            },
            onEdit = {
                selectedDetailAccount = null
                editingAccount = latestAcc
                showAddAccountDialog = true
            }
        )
    }

    // 快速校准余额弹窗
    adjustingAccount?.let { acc ->
        QuickAdjustBalanceDialog(
            account = acc,
            onDismiss = { adjustingAccount = null },
            onSuccess = {
                adjustingAccount = null
                loadAccounts()
            }
        )
    }

    // 新增 / 编辑账户弹窗
    if (showAddAccountDialog) {
        AddAccountDialog(
            editingAccount = editingAccount,
            onDismiss = {
                showAddAccountDialog = false
                editingAccount = null
            },
            onSuccess = {
                showAddAccountDialog = false
                editingAccount = null
                loadAccounts()
            }
        )
    }

    // 账户互转弹窗
    if (showTransferDialog) {
        val accs = summary?.accounts ?: emptyList()
        TransferFundsDialog(
            accounts = accs,
            initialFromAccountId = transferInitialFromId,
            onDismiss = {
                showTransferDialog = false
                transferInitialFromId = null
            },
            onSuccess = {
                showTransferDialog = false
                transferInitialFromId = null
                loadAccounts()
            }
        )
    }

    // 账户对账与余额校准弹窗
    if (reconcilingAccount != null) {
        AccountReconcileDialog(
            account = reconcilingAccount!!,
            onDismiss = { reconcilingAccount = null },
            onSuccess = {
                reconcilingAccount = null
                loadAccounts()
            }
        )
    }
}

@Composable
fun AccountCard(
    account: AccountRecord,
    hideBalances: Boolean = false,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val accountIconSpec = CategoryUiHelper.getAccountTypeIconSpec(account.type)
    val typeName = CategoryUiHelper.getAccountTypeName(context, account.type)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 0.5.dp,
        border = BorderStroke(1.dp, Slate200.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(account.name, null)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(if (badgeCfg.iconRes != null) Color.Transparent else accountIconSpec.bg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (badgeCfg.iconRes != null) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(id = badgeCfg.iconRes),
                                contentDescription = account.name,
                                modifier = Modifier.size(38.dp)
                            )
                        } else {
                            Icon(
                                imageVector = accountIconSpec.icon,
                                contentDescription = account.name,
                                tint = accountIconSpec.tint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = typeName,
                                fontSize = 11.sp,
                                color = Slate500
                            )
                            if (account.lastReconciledAt != null) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = IncomeGreen.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        text = "✓ " + stringResource(R.string.reconciled_badge),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = IncomeGreen,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (hideBalances) "${account.currency} ••••" else "${account.currency} ${String.format(Locale.US, "%,.2f", account.balance)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (account.type == "credit_card" && account.balance > 0) Rose500 else Slate900,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                        if (account.type == "credit_card" && account.creditLimit > 0) {
                            Text(
                                text = "${stringResource(R.string.credit_limit_label)}: ${String.format(Locale.US, "%,.0f", account.creditLimit)}",
                                fontSize = 10.sp,
                                color = Slate400
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (account.type == "credit_card" && account.creditLimit > 0) {
                val usedRatio = (kotlin.math.abs(account.balance) / account.creditLimit).toFloat().coerceIn(0f, 1f)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (usedRatio >= 0.7f) ExpenseRed else if (usedRatio >= 0.4f) Amber500 else PrimaryTeal,
                    trackColor = Slate100
                )
            }

            // 本月平台出入账统计
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Slate100, thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.accounts_stat_in_out),
                    fontSize = 11.sp,
                    color = Slate400
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.accounts_inflow_prefix), fontSize = 11.sp, color = Slate400)
                        Text(
                            text = if (hideBalances) "•••" else "+${account.currency} ${String.format(Locale.US, "%,.2f", account.monthlyInflow)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = IncomeGreen,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.accounts_outflow_prefix), fontSize = 11.sp, color = Slate400)
                        Text(
                            text = if (hideBalances) "•••" else "-${account.currency} ${String.format(Locale.US, "%,.2f", account.monthlyOutflow)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (account.monthlyOutflow > 0) ExpenseRed else Slate600,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailSheet(
    account: AccountRecord,
    canTransfer: Boolean,
    onDismiss: () -> Unit,
    onAdjustBalance: () -> Unit,
    onTransfer: () -> Unit,
    onReconcile: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(account.name, null)
    val iconSpec = CategoryUiHelper.getAccountTypeIconSpec(account.type)
    val typeName = CategoryUiHelper.getAccountTypeName(context, account.type)

    var recentTxs by remember(account.id) { mutableStateOf<List<com.siangloh.ledger.data.ReconcileTxItem>>(emptyList()) }
    var isLoadingTxs by remember(account.id) { mutableStateOf(true) }

    LaunchedEffect(account.id) {
        isLoadingTxs = true
        NativeLedgerApi.getAccountReconciliation(context, account.id).onSuccess { data ->
            recentTxs = data.transactions.take(15)
        }
        isLoadingTxs = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(if (badgeCfg.iconRes != null) Color.Transparent else iconSpec.bg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (badgeCfg.iconRes != null) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(id = badgeCfg.iconRes),
                                contentDescription = account.name,
                                modifier = Modifier.size(40.dp)
                            )
                        } else {
                            Icon(iconSpec.icon, contentDescription = null, tint = iconSpec.tint, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Slate900)
                        Text(typeName, fontSize = 12.sp, color = Slate500)
                    }
                }
                Text(
                    text = "${account.currency} ${String.format(Locale.US, "%,.2f", account.balance)}",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (account.type == "credit_card" && account.balance > 0) Rose500 else PrimaryTeal,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                )
            }

            Spacer(Modifier.height(18.dp))

            // 4 Quick Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccountQuickActionButton(
                    icon = Icons.Rounded.Tune,
                    label = stringResource(R.string.accounts_action_adjust),
                    modifier = Modifier.weight(1f),
                    onClick = onAdjustBalance
                )
                AccountQuickActionButton(
                    icon = Icons.Rounded.SwapHoriz,
                    label = stringResource(R.string.transfer_btn),
                    enabled = canTransfer,
                    modifier = Modifier.weight(1f),
                    onClick = onTransfer
                )
                AccountQuickActionButton(
                    icon = Icons.Rounded.Balance,
                    label = stringResource(R.string.reconcile_btn),
                    modifier = Modifier.weight(1f),
                    onClick = onReconcile
                )
                AccountQuickActionButton(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.accounts_action_edit),
                    modifier = Modifier.weight(1f),
                    onClick = onEdit
                )
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Slate100)
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.accounts_recent_tx),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )
            Spacer(Modifier.height(8.dp))

            if (isLoadingTxs) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PrimaryTeal, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else if (recentTxs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.accounts_recent_empty), fontSize = 13.sp, color = Slate400)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(recentTxs, key = { it.id }) { tx ->
                        val isInc = tx.type == "income"
                        val isTrf = tx.type == "transfer"
                        val locCat = CategoryUiHelper.getLocalizedCategoryName(context, tx.category)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    CategoryIconBadge(category = tx.category, type = tx.type, size = 34.dp, iconSize = 18.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tx.note.ifBlank { locCat },
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate900,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$locCat · ${tx.date}",
                                            fontSize = 11.sp,
                                            color = Slate500
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = (if (isInc) "+" else if (isTrf) "" else "-") + "${account.currency} ${String.format(Locale.US, "%,.2f", tx.amount)}",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInc) IncomeGreen else if (isTrf) Slate700 else Slate900,
                                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountQuickActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) Teal50 else Slate100,
        border = BorderStroke(1.dp, if (enabled) PrimaryTeal.copy(alpha = 0.25f) else Slate200),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) PrimaryTeal else Slate400,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) PrimaryTeal else Slate400,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickAdjustBalanceDialog(
    account: AccountRecord,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actualText by remember(account.id) { mutableStateOf(String.format(Locale.US, "%.2f", account.balance)) }
    var isSaving by remember { mutableStateOf(false) }

    val parsedActual = actualText.toDoubleOrNull()
    val diff = if (parsedActual != null) parsedActual - account.balance else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = "${stringResource(R.string.accounts_adjust_title)} · ${account.name}",
                fontSize = 16.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Slate900
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.accounts_adjust_hint),
                    fontSize = 12.sp,
                    color = Slate500,
                    lineHeight = 17.sp
                )
                OutlinedTextField(
                    value = actualText,
                    onValueChange = { actualText = it },
                    label = { Text(stringResource(R.string.accounts_adjust_input_label), fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ledgerInputColors(containerColor = Slate50)
                )
                if (parsedActual != null && kotlin.math.abs(diff) > 0.005) {
                    val sign = if (diff > 0) "+" else ""
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (diff > 0) Emerald50 else Rose50,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${account.currency} ${String.format(Locale.US, "%,.2f", account.balance)} → ${account.currency} ${String.format(Locale.US, "%,.2f", parsedActual)} ($sign${String.format(Locale.US, "%,.2f", diff)})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (diff > 0) IncomeGreen else ExpenseRed,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = actualText.toDoubleOrNull()
                    if (target == null) {
                        Toast.makeText(context, context.getString(R.string.common_failed), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val res = NativeLedgerApi.reconcileAccount(
                            context = context,
                            accountId = account.id,
                            statementBalance = target,
                            autoAdjust = true,
                            note = "快速校准余额"
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, context.getString(R.string.common_success), Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }.onFailure {
                            Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.common_confirm), color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Slate600)
            }
        }
    )
}

@Composable
fun AddAccountDialog(
    editingAccount: AccountRecord? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(editingAccount?.id) { mutableStateOf(editingAccount?.name ?: "") }
    var selectedType by remember(editingAccount?.id) { mutableStateOf(editingAccount?.type ?: "savings") }
    var creditLimitText by remember(editingAccount?.id) {
        mutableStateOf(
            if (editingAccount != null && editingAccount.creditLimit > 0) String.format(Locale.US, "%.0f", editingAccount.creditLimit) else ""
        )
    }
    var isSaving by remember { mutableStateOf(false) }

    val accountTypes = listOf(
        "savings" to stringResource(R.string.account_type_savings),
        "e_wallet" to stringResource(R.string.account_type_e_wallet),
        "cash" to stringResource(R.string.account_type_cash),
        "credit_card" to stringResource(R.string.account_type_credit_card),
        "investment" to stringResource(R.string.account_type_investment)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = {
            Text(
                text = if (editingAccount != null) stringResource(R.string.accounts_edit_title) else stringResource(R.string.add_account_btn),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Slate900
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.account_name_label), color = Slate600) },
                    placeholder = { Text("Maybank, TNG, Cash...", color = Slate400) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(color = Slate900),
                    colors = ledgerInputColors()
                )

                Text(stringResource(R.string.account_type_label), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate700)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accountTypes.forEach { (typeKey, label) ->
                        val isSelected = selectedType == typeKey
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { selectedType = typeKey }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (selectedType == "credit_card") {
                    OutlinedTextField(
                        value = creditLimitText,
                        onValueChange = { creditLimitText = it },
                        label = { Text(stringResource(R.string.credit_limit_label), color = Slate600) },
                        placeholder = { Text("0", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        Toast.makeText(context, "请输入账户名称", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val limit = creditLimitText.toDoubleOrNull() ?: 0.0
                        val res = NativeLedgerApi.addOrUpdateAccount(
                            context = context,
                            id = editingAccount?.id,
                            name = name,
                            type = selectedType,
                            creditLimit = limit
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, context.getString(R.string.common_success), Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }.onFailure {
                            Toast.makeText(context, "${context.getString(R.string.common_failed)}：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
            ) {
                Text(stringResource(R.string.accounts_btn_add), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Slate600)
            }
        }
    )
}

@Composable
fun TransferFundsDialog(
    accounts: List<AccountRecord>,
    initialFromAccountId: Int? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (accounts.size < 2) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color.White,
            title = { Text(stringResource(R.string.transfer_funds_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900) },
            text = { Text(stringResource(R.string.cannot_transfer_msg), color = Slate700) },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
                ) {
                    Text(stringResource(R.string.common_confirm), color = Color.White)
                }
            }
        )
        return
    }

    val defaultFrom = initialFromAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: (accounts.firstOrNull()?.id ?: 0)
    val defaultTo = accounts.firstOrNull { it.id != defaultFrom }?.id ?: (accounts.getOrNull(1)?.id ?: 0)
    var fromId by remember(defaultFrom) { mutableStateOf(defaultFrom) }
    var toId by remember(defaultTo) { mutableStateOf(defaultTo) }
    var amountStr by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("日常账户调拨") }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = { Text(stringResource(R.string.transfer_funds_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.from_account_label), fontSize = 12.sp, color = Slate600)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accounts.forEach { acc ->
                        val isSelected = fromId == acc.id
                        val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(acc.name, null)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { fromId = acc.id }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                BrandIcon(config = badgeCfg, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = acc.name,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.White else Slate700
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.to_account_label), fontSize = 12.sp, color = Slate600)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accounts.forEach { acc ->
                        val isSelected = toId == acc.id
                        val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(acc.name, null)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { toId = acc.id }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                BrandIcon(config = badgeCfg, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = acc.name,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.White else Slate700
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text(stringResource(R.string.amount_label) + " (MYR)", color = Slate600) },
                    placeholder = { Text("0.00", color = Slate400) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(color = Slate900),
                    colors = ledgerInputColors()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.add_note), color = Slate600) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(color = Slate900),
                    colors = ledgerInputColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountStr.toDoubleOrNull() ?: 0.0
                    if (amt <= 0) {
                        Toast.makeText(context, "请输入有效转账金额", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (fromId == toId) {
                        Toast.makeText(context, "转出账户与转入账户不能相同", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val dateNow = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                        val res = NativeLedgerApi.addTransaction(
                            context = context,
                            type = "transfer",
                            amount = amt,
                            category = "内部划转",
                            date = dateNow,
                            note = note,
                            tags = "#transfer",
                            accountId = fromId,
                            toAccountId = toId
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, "调拨完成并已记录！", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }.onFailure {
                            Toast.makeText(context, "转账失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
            ) {
                Text(stringResource(R.string.add_save_transfer), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Slate500)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountReconcileDialog(
    account: AccountRecord,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statementBalanceStr by remember { mutableStateOf(String.format(Locale.US, "%.2f", account.balance)) }
    var autoAdjust by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    var reconciliationData by remember { mutableStateOf<com.siangloh.ledger.data.AccountReconciliationData?>(null) }
    var isLoadingData by remember { mutableStateOf(true) }

    fun loadData() {
        scope.launch {
            val res = NativeLedgerApi.getAccountReconciliation(context, account.id)
            isLoadingData = false
            res.onSuccess {
                reconciliationData = it
            }
        }
    }

    LaunchedEffect(account.id) {
        loadData()
    }

    val systemBal = account.balance
    val targetVal = statementBalanceStr.toDoubleOrNull() ?: systemBal
    val diff = kotlin.math.round((targetVal - systemBal) * 100.0) / 100.0
    val isBalanced = kotlin.math.abs(diff) < 0.005

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "⚖️ " + stringResource(R.string.reconcile_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${account.name} (${CategoryUiHelper.getAccountTypeName(context, account.type)})",
                    fontSize = 13.sp,
                    color = Slate500
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Balance comparison card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.accounts_title) + " " + (if (account.type == "credit_card") stringResource(R.string.accounts_reconcile_debt) else stringResource(R.string.accounts_reconcile_balance)),
                                fontSize = 11.sp,
                                color = Slate500
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${account.currency} ${String.format(Locale.US, "%,.2f", systemBal)}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryTeal
                            )
                        }
                        Text("⟷", fontSize = 18.sp, color = Slate400)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.statement_balance_label),
                                fontSize = 11.sp,
                                color = Slate500
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${account.currency} ${String.format(Locale.US, "%,.2f", targetVal)}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                        }
                    }
                }

                // Statement Balance Input
                OutlinedTextField(
                    value = statementBalanceStr,
                    onValueChange = { statementBalanceStr = it },
                    label = { Text(stringResource(R.string.statement_balance_label), color = Slate600) },
                    placeholder = { Text(stringResource(R.string.statement_balance_hint), color = Slate400) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Slate900),
                    colors = ledgerInputColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Discrepancy indicator card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isBalanced) IncomeGreen.copy(alpha = 0.08f) else Amber500.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, if (isBalanced) IncomeGreen.copy(alpha = 0.3f) else Amber500.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.discrepancy_label),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isBalanced) IncomeGreen else Amber700
                            )
                            Text(
                                text = (if (diff > 0) "+" else "") + String.format(Locale.US, "%.2f", diff) + " " + account.currency,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBalanced) IncomeGreen else Amber700
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBalanced) stringResource(R.string.balanced_status)
                                   else stringResource(R.string.accounts_reconcile_diff_hint),
                            fontSize = 11.sp,
                            color = if (isBalanced) IncomeGreen else Amber700
                        )
                    }
                }

                // Auto-adjust toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { autoAdjust = !autoAdjust }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoAdjust,
                        onCheckedChange = { autoAdjust = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryTeal)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.auto_adjust_label),
                        fontSize = 12.sp,
                        color = Slate800
                    )
                }

                // Note input
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.reconcile_note_hint), color = Slate600) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Slate900),
                    colors = ledgerInputColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Unreconciled transactions preview
                if (isLoadingData) {
                    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryTeal, strokeWidth = 2.dp)
                    }
                } else if (reconciliationData != null && reconciliationData!!.transactions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.accounts_reconcile_tx_check, reconciliationData!!.transactions.size),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    reconciliationData!!.transactions.take(15).forEach { tx ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .border(1.dp, Slate100, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(
                                    checked = tx.isReconciled,
                                    onCheckedChange = { isChecked ->
                                        scope.launch {
                                            NativeLedgerApi.toggleTransactionReconciled(context, tx.id, isChecked)
                                            loadData()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = PrimaryTeal),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${CategoryUiHelper.getLocalizedCategoryName(context, tx.category)} ${if (tx.note.isNotBlank()) "- " + tx.note else ""}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Slate800,
                                        maxLines = 1
                                    )
                                    Text(text = tx.date, fontSize = 10.sp, color = Slate400)
                                }
                            }
                            Text(
                                text = (if (tx.type == "expense") "-" else "+") + String.format(Locale.US, "%.2f", tx.amount),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (tx.type == "expense") ExpenseRed else IncomeGreen
                            )
                        }
                    }
                } else if (!isLoadingData) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.accounts_reconcile_no_tx),
                            fontSize = 12.sp,
                            color = Slate400,
                            modifier = Modifier.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetBal = statementBalanceStr.toDoubleOrNull()
                    if (targetBal == null) {
                        Toast.makeText(context, "请输入有效的对账单金额", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSubmitting = true
                    scope.launch {
                        val res = NativeLedgerApi.reconcileAccount(
                            context = context,
                            accountId = account.id,
                            statementBalance = targetBal,
                            autoAdjust = autoAdjust,
                            note = note
                        )
                        isSubmitting = false
                        res.onSuccess {
                            val finalBal = it.optDouble("final_balance", targetBal)
                            Toast.makeText(
                                context,
                                context.getString(R.string.reconcile_success, "${account.currency} ${String.format(Locale.US, "%.2f", finalBal)}"),
                                Toast.LENGTH_SHORT
                            ).show()
                            onSuccess()
                        }.onFailure {
                            Toast.makeText(context, "对账失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.confirm_reconcile), fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.common_cancel), color = Slate500)
            }
        }
    )
}
