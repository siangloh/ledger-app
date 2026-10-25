package com.siangloh.ledger.ui.compose

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.CategoryRecord
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.ServerValidationException
import com.siangloh.ledger.data.entities.PendingTransaction
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.compose.components.BrandIcon
import com.siangloh.ledger.ui.compose.components.SourceBrandBadgeHelper
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    onFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 采用 TransactionDraftHolder 保持跨 Tab、查账返回与屏幕旋转时的草稿稳定
    val draft = TransactionDraftHolder

    var accounts by remember { mutableStateOf<List<AccountRecord>>(emptyList()) }
    var expenseCategories by remember { mutableStateOf<List<CategoryRecord>>(emptyList()) }
    var incomeCategories by remember { mutableStateOf<List<CategoryRecord>>(emptyList()) }
    var savingsCategories by remember { mutableStateOf<List<CategoryRecord>>(emptyList()) }
    var showAllCategories by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        draft.ensureInitialized(context)
        scope.launch {
            NativeLedgerApi.getAccounts(context).onSuccess {
                accounts = it.accounts
                val defId = it.defaultAccountId
                if (draft.selectedAccountId == null && it.accounts.isNotEmpty()) {
                    draft.selectedAccountId = if (defId != null && it.accounts.any { a -> a.id == defId }) {
                        defId
                    } else {
                        it.accounts.first().id
                    }
                }
                if (it.defaultGroup != null && draft.incomeGroup == "main") {
                    draft.incomeGroup = it.defaultGroup
                }
                if (draft.selectedToAccountId == null && it.accounts.size > 1) {
                    val remaining = it.accounts.filter { a -> a.id != draft.selectedAccountId }
                    if (remaining.isNotEmpty()) {
                        draft.selectedToAccountId = remaining.first().id
                    }
                }
            }

            NativeLedgerApi.getCategories(context).onSuccess { catGroup ->
                expenseCategories = catGroup.expenses
                incomeCategories = catGroup.incomes
                savingsCategories = catGroup.savings
                if (draft.txType == "expense" && catGroup.expenses.isNotEmpty() && catGroup.expenses.none { it.name == draft.selectedCategory }) {
                    draft.selectedCategory = catGroup.expenses.first().name
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            draft.persist(context)
        }
    }

    LaunchedEffect(draft.txType) {
        when (draft.txType) {
            "expense" -> {
                if (expenseCategories.isNotEmpty() && expenseCategories.none { it.name == draft.selectedCategory }) {
                    draft.selectedCategory = expenseCategories.first().name
                }
            }
            "income" -> {
                if (incomeCategories.isNotEmpty() && incomeCategories.none { it.name == draft.selectedCategory }) {
                    draft.selectedCategory = incomeCategories.first().name
                }
            }
            "savings" -> {
                if (savingsCategories.isNotEmpty() && savingsCategories.none { it.name == draft.selectedCategory }) {
                    draft.selectedCategory = savingsCategories.first().name
                } else if (savingsCategories.isEmpty()) {
                    draft.selectedCategory = "定期存款"
                }
            }
            "transfer" -> {
                draft.selectedCategory = "内部划转"
            }
        }
    }

    // 日期选择器逻辑：点击日期卡片即弹出原生日历弹窗
    fun showDatePicker() {
        val cal = Calendar.getInstance()
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(draft.dateStr)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {}

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val chosenCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                draft.dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(chosenCal.time)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
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
                        text = stringResource(R.string.add_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    if (draft.amountText.isNotBlank() || draft.note.isNotBlank() || draft.tags.isNotBlank()) {
                        TextButton(
                            onClick = { draft.clear(context) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.clear_draft), fontSize = 12.sp, color = Slate500)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 四向分段器 (支出 / 收入 / 储蓄 / 内部划转)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate100, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val types = listOf(
                        "expense" to stringResource(R.string.add_expense),
                        "income" to stringResource(R.string.add_income),
                        "savings" to stringResource(R.string.add_savings),
                        "transfer" to stringResource(R.string.add_transfer)
                    )
                    types.forEach { (typeKey, label) ->
                        val isSelected = draft.txType == typeKey
                        val bg = if (isSelected) PrimaryTeal else Color.Transparent

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bg,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { draft.txType = typeKey }
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Slate700,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // 底部常驻保存操作区：键盘弹出时自动跟随，绝对不遮挡最后一个输入字段
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .imePadding()
                    .navigationBarsPadding(),
                shadowElevation = 8.dp,
                color = Color.White
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Button(
                        onClick = {
                            val amt = draft.amountText.toDoubleOrNull() ?: 0.0
                            if (amt <= 0) {
                                Toast.makeText(context, "请输入大于 0 的有效金额", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (draft.txType == "transfer" && draft.selectedAccountId != null && draft.selectedAccountId == draft.selectedToAccountId) {
                                Toast.makeText(context, "转出账户与转入账户不能相同", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isSaving = true
                            scope.launch {
                                val canUploadOnline = NetworkHelper.canSyncNow(context)
                                if (!canUploadOnline) {
                                    // 处于无网络或等待 WiFi 的离线状态：零延迟（0ms）直接存入本地 Room，不阻塞等待网络超时
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val db = AppDatabase.getInstance(context)
                                            val rawNote = draft.note.trim().ifBlank {
                                                if (draft.txType == "transfer") "内部转账" else draft.selectedCategory
                                            }
                                            db.pendingTransactionDao().insert(
                                                PendingTransaction(
                                                    date = draft.dateStr,
                                                    type = draft.txType,
                                                    group_name = if (draft.txType == "income") draft.incomeGroup else "personal",
                                                    category = draft.selectedCategory,
                                                    amount = amt,
                                                    note = rawNote,
                                                    source = "offline_manual",
                                                    sync_status = "pending",
                                                    account_id = draft.selectedAccountId?.toLong(),
                                                    to_account_id = draft.selectedToAccountId?.toLong(),
                                                    tags = draft.tags.trim()
                                                )
                                            )
                                        }
                                        SyncWorker.enqueueSync(context)
                                        val wifiOnly = NetworkHelper.isWifiOnlySync(context)
                                        val tip = if (wifiOnly) {
                                            "💾 已保存至本地！连上 WiFi 后将自动上传同步到云端。"
                                        } else {
                                            "💾 当前离线，已保存至本地！连上网络后将自动上传同步到云端。"
                                        }
                                        Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                                        draft.clear(context)
                                        isSaving = false
                                        onFinished()
                                    } catch (localErr: Exception) {
                                        isSaving = false
                                        Toast.makeText(context, "本地保存失败：${localErr.message}", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }

                                // 允许在线提交状态：直接提交至云端数据库
                                val res = NativeLedgerApi.addTransaction(
                                    context = context,
                                    type = draft.txType,
                                    amount = amt,
                                    category = draft.selectedCategory,
                                    date = draft.dateStr,
                                    note = draft.note,
                                    tags = draft.tags,
                                    accountId = draft.selectedAccountId,
                                    toAccountId = if (draft.txType == "transfer") draft.selectedToAccountId else null,
                                    groupName = if (draft.txType == "income") draft.incomeGroup else null
                                )
                                isSaving = false
                                res.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.add_success), Toast.LENGTH_SHORT).show()
                                    draft.clear(context)
                                    onFinished()
                                }.onFailure { err ->
                                    if (err is ServerValidationException) {
                                        // 服务端业务拒绝（如账户无效、余额不足、未登录等），明确提示用户，不伪称断网
                                        Toast.makeText(context, "⚠️ 提交未通过：${err.message}", Toast.LENGTH_LONG).show()
                                    } else if (!NetworkHelper.isOnline(context) || err is IOException || err.message?.contains("Unable to resolve host") == true) {
                                        // 真实的离线状态或网络突发波动：安全存入本地 Room 离线表，等待联网自动同步
                                        try {
                                            withContext(Dispatchers.IO) {
                                                val db = AppDatabase.getInstance(context)
                                                val rawNote = draft.note.trim().ifBlank {
                                                    if (draft.txType == "transfer") "内部转账" else draft.selectedCategory
                                                }
                                                db.pendingTransactionDao().insert(
                                                    PendingTransaction(
                                                        date = draft.dateStr,
                                                        type = draft.txType,
                                                        group_name = if (draft.txType == "income") draft.incomeGroup else "personal",
                                                        category = draft.selectedCategory,
                                                        amount = amt,
                                                        note = rawNote,
                                                        source = "offline_manual",
                                                        sync_status = "pending",
                                                        account_id = draft.selectedAccountId?.toLong(),
                                                        to_account_id = draft.selectedToAccountId?.toLong(),
                                                        tags = draft.tags.trim()
                                                    )
                                                )
                                            }
                                            SyncWorker.enqueueSync(context)
                                            Toast.makeText(context, context.getString(R.string.add_offline_saved), Toast.LENGTH_LONG).show()
                                            draft.clear(context)
                                            onFinished()
                                        } catch (localDbErr: Exception) {
                                            Toast.makeText(context, "离线保存失败：${localDbErr.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "记账失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (draft.txType) {
                                "income" -> IncomeGreen
                                "transfer" -> Slate700
                                "savings" -> PrimaryTeal
                                else -> PrimaryTeal
                            },
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (draft.txType == "transfer") stringResource(R.string.add_save_transfer) else stringResource(R.string.add_save),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingVals ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
                .background(Slate50)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. 金额录入紧凑卡片 (Tabular numbers, 48px高度输入，无多余占屏空隙)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(stringResource(R.string.amount_label), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Slate500)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MYR",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate600
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = draft.amountText,
                            onValueChange = {
                                if (it.isEmpty() || it.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                                    draft.amountText = it
                                }
                            },
                            placeholder = { Text("0.00", fontSize = 24.sp, color = Slate400) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (draft.txType) {
                                    "income" -> IncomeGreen
                                    "transfer" -> Slate700
                                    else -> Slate900
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = when (draft.txType) {
                                    "income" -> IncomeGreen
                                    "transfer" -> Slate700
                                    else -> Slate900
                                },
                                unfocusedTextColor = when (draft.txType) {
                                    "income" -> IncomeGreen
                                    "transfer" -> Slate700
                                    else -> Slate900
                                },
                                cursorColor = PrimaryTeal,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 常用金额微调胶囊
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("10", "20", "50", "100").forEach { quickAmt ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Slate100,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val current = draft.amountText.toDoubleOrNull() ?: 0.0
                                        draft.amountText = String.format(Locale.US, "%.2f", current + quickAmt.toDouble())
                                    }
                            ) {
                                Text(
                                    text = "+$quickAmt",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 分类选择 (只在非 transfer 时显示；支持常用收起，避免数十个全量平铺挡住保存)
            if (draft.txType != "transfer") {
                val currentCats = when (draft.txType) {
                    "income" -> incomeCategories
                    "savings" -> savingsCategories
                    else -> expenseCategories
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    shadowElevation = 0.5.dp,
                    border = BorderStroke(1.dp, Slate200),
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
                            Text(stringResource(R.string.add_category), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            if (currentCats.size > 8) {
                                Text(
                                    text = if (showAllCategories) stringResource(R.string.collapse_categories) else stringResource(R.string.add_more_categories_fmt, currentCats.size),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PrimaryTeal,
                                    modifier = Modifier
                                        .clickable { showAllCategories = !showAllCategories }
                                        .padding(4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        val fallbackCats = when (draft.txType) {
                            "income" -> listOf("工资", "兼职", "奖金", "投资", "其他")
                            "savings" -> listOf("定期存款", "应急基金", "投资理财", "心愿基金")
                            else -> listOf("餐饮", "交通", "杂货", "账单", "购物", "娱乐", "医疗", "其他")
                        }.map { CategoryRecord(0, it, draft.txType) }

                        val allCats = if (currentCats.isNotEmpty()) currentCats else fallbackCats
                        val displayCats = if (showAllCategories) allCats else allCats.take(8)

                        val chunked = displayCats.chunked(4)
                        chunked.forEach { rowCats ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowCats.forEach { cat ->
                                    val isSelected = draft.selectedCategory == cat.name
                                    val emoji = CategoryUiHelper.getCategoryEmoji(cat.name, draft.txType)
                                    val tintBg = if (isSelected) PrimaryTealSubtle else Slate100
                                    val borderStroke = if (isSelected) BorderStroke(1.5.dp, PrimaryTeal) else null

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = tintBg,
                                        border = borderStroke,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { draft.selectedCategory = cat.name }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                                        ) {
                                            Text(emoji, fontSize = 18.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = CategoryUiHelper.getLocalizedCategoryName(context, cat.name),
                                                fontSize = 11.sp,
                                                lineHeight = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) PrimaryTeal else Slate700,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                                if (rowCats.size < 4) {
                                    for (i in 0 until (4 - rowCats.size)) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        // 收入专属分组 (主业 / 副业)
                        if (draft.txType == "income") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("收入分组", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate700)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val groups = listOf("main" to "💼 主业收入", "side" to "📈 副业/理财")
                                groups.forEach { (grpKey, grpLabel) ->
                                    val isGrpSelected = draft.incomeGroup == grpKey
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isGrpSelected) PrimaryTealSubtle else Slate100,
                                        border = if (isGrpSelected) BorderStroke(1.dp, PrimaryTeal) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { draft.incomeGroup = grpKey }
                                    ) {
                                        Text(
                                            text = grpLabel,
                                            fontSize = 11.sp,
                                            fontWeight = if (isGrpSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isGrpSelected) PrimaryTeal else Slate700,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 3. 结算账户选择卡片
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (draft.txType == "transfer") stringResource(R.string.add_account_from) else stringResource(R.string.add_account_settlement),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (accounts.isEmpty()) {
                        Text(stringResource(R.string.add_no_accounts_configured), fontSize = 12.sp, color = Slate400)
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            accounts.forEach { acc ->
                                val isSelected = draft.selectedAccountId == acc.id
                                val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(acc.name, null)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) PrimaryTeal else Slate100,
                                    modifier = Modifier.clickable { draft.selectedAccountId = acc.id }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        BrandIcon(config = badgeCfg, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = acc.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else Slate700
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 如果是账户互转，选择转入账户
                    if (draft.txType == "transfer") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.add_account_to), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            accounts.forEach { acc ->
                                val isSelected = draft.selectedToAccountId == acc.id
                                val badgeCfg = SourceBrandBadgeHelper.getAccountBrandConfig(acc.name, null)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) PrimaryTeal else Slate100,
                                    modifier = Modifier.clickable { draft.selectedToAccountId = acc.id }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        BrandIcon(config = badgeCfg, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = acc.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else Slate700
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 补充信息（折叠式区域，降低主路径滚动负担，日期支持原生弹窗挑选）
            val hasDetails = draft.note.isNotBlank() || draft.tags.isNotBlank() || draft.isDetailsExpanded
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draft.isDetailsExpanded = !draft.isDetailsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.add_extra_details_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Text(
                            text = if (hasDetails) stringResource(R.string.collapse_categories) else stringResource(R.string.expand_details),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryTeal
                        )
                    }

                    if (hasDetails) {
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = draft.note,
                            onValueChange = { draft.note = it },
                            label = { Text("商户 / 备注说明", fontSize = 12.sp, color = Slate600) },
                            placeholder = { Text("如：Starbucks、麦当劳、超市买菜", fontSize = 12.sp, color = Slate400) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors(containerColor = Slate50)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = draft.tags,
                            onValueChange = { draft.tags = it },
                            label = { Text("标签 (Tags)", fontSize = 12.sp, color = Slate600) },
                            placeholder = { Text("如：#出差 #聚会 #报销", fontSize = 12.sp, color = Slate400) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors(containerColor = Slate50)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 日期选择卡片：展示日期并附带日历图标，点击唤出原生日历
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Slate200),
                            color = Slate50,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDatePicker() }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("记账日期", fontSize = 10.sp, color = Slate500)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(draft.dateStr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                                }
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = "选择日期",
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
