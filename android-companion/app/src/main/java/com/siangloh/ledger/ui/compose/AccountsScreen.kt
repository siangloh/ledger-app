package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.AccountsSummary
import com.siangloh.ledger.data.LocalDataStore
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.ui.compose.components.AccountsSkeletonScreen
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen() {
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
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }

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
                    Text(
                        text = stringResource(R.string.accounts_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    IconButton(
                        onClick = { loadAccounts() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
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
            // 净资产全景卡片 (中性卡片，清晰呈现资产负债结构)
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Slate200),
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.net_assets),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate500
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val netWorth = summary?.netWorth ?: 0.0
                        Text(
                            text = "MYR ${String.format(Locale.US, "%,.2f", netWorth)}",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(stringResource(R.string.total_assets), fontSize = 11.sp, color = Slate500)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "MYR ${String.format(Locale.US, "%,.2f", summary?.totalAssets ?: 0.0)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IncomeGreen
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.total_liabilities), fontSize = 11.sp, color = Slate500)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "MYR ${String.format(Locale.US, "%,.2f", summary?.totalLiabilities ?: 0.0)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ExpenseRed
                                )
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!canTransfer) {
                                Toast.makeText(context, "请先添加至少两个可用账户再进行互转", Toast.LENGTH_SHORT).show()
                            } else {
                                showTransferDialog = true
                            }
                        },
                        enabled = canTransfer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryTeal,
                            disabledContainerColor = Slate200
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (canTransfer) Color.White else Slate400
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.transfer_btn),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canTransfer) Color.White else Slate400
                        )
                    }

                    OutlinedButton(
                        onClick = { showAddAccountDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Slate200),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Slate800, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_account_btn), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate800)
                    }
                }
            }

            // 账户分类列表
            val accountsList = summary?.accounts ?: emptyList()
            if (accountsList.isEmpty() && !isLoading) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text("🏦", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(stringResource(R.string.no_accounts_configured), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.no_accounts_help), fontSize = 12.sp, color = Slate400, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.my_accounts_list, accountsList.size),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(accountsList, key = { it.id }) { acc ->
                    AccountCard(account = acc)
                }
            }

            item {
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }
}

    // 新增账户弹窗
    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onSuccess = {
                showAddAccountDialog = false
                loadAccounts()
            }
        )
    }

    // 账户互转弹窗
    if (showTransferDialog) {
        val accs = summary?.accounts ?: emptyList()
        TransferFundsDialog(
            accounts = accs,
            onDismiss = { showTransferDialog = false },
            onSuccess = {
                showTransferDialog = false
                loadAccounts()
            }
        )
    }
}

@Composable
fun AccountCard(account: AccountRecord) {
    val context = LocalContext.current
    val icon = CategoryUiHelper.getAccountTypeIcon(account.type)
    val typeName = CategoryUiHelper.getAccountTypeName(context, account.type)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 0.5.dp,
        border = BorderStroke(1.dp, Slate100),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Slate100, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = account.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = typeName,
                        fontSize = 11.sp,
                        color = Slate400
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${account.currency} ${String.format(Locale.US, "%,.2f", account.balance)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (account.type == "credit_card") Rose500 else Slate900
                )
                if (account.type == "credit_card" && account.creditLimit > 0) {
                    Text(
                        text = "${stringResource(R.string.credit_limit_label)}: ${String.format(Locale.US, "%.0f", account.creditLimit)}",
                        fontSize = 10.sp,
                        color = Slate400
                    )
                }
            }
        }
    }
}

@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("savings") }
    var creditLimitText by remember { mutableStateOf("") }
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
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = { Text(stringResource(R.string.add_account_btn), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.account_name_label)) },
                    placeholder = { Text("Maybank, TNG, Cash...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                        label = { Text(stringResource(R.string.credit_limit_label)) },
                        placeholder = { Text("0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                            name = name,
                            type = selectedType,
                            creditLimit = limit
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, "账户添加成功！", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }.onFailure {
                            Toast.makeText(context, "添加失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
            ) {
                Text("添加", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Slate600)
            }
        }
    )
}

@Composable
fun TransferFundsDialog(
    accounts: List<AccountRecord>,
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

    var fromId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 0) }
    var toId by remember { mutableStateOf(accounts.getOrNull(1)?.id ?: 0) }
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { fromId = acc.id }
                        ) {
                            Text(
                                text = acc.name,
                                fontSize = 11.sp,
                                color = if (isSelected) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { toId = acc.id }
                        ) {
                            Text(
                                text = acc.name,
                                fontSize = 11.sp,
                                color = if (isSelected) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text(stringResource(R.string.amount_label) + " (MYR)") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.add_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
