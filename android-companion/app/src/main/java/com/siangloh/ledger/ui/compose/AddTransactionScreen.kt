package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.CategoryRecord
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    onFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var txType by remember { mutableStateOf("expense") } // 'expense', 'income', 'transfer'
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var dateStr by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    }
    var selectedCategory by remember { mutableStateOf("餐饮") }
    var selectedAccountId by remember { mutableStateOf<Int?>(null) }
    var selectedToAccountId by remember { mutableStateOf<Int?>(null) }

    var accounts by remember { mutableStateOf<List<AccountRecord>>(emptyList()) }
    var expenseCategories by remember { mutableStateOf<List<CategoryRecord>>(emptyList()) }
    var incomeCategories by remember { mutableStateOf<List<CategoryRecord>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            NativeLedgerApi.getAccounts(context).onSuccess {
                accounts = it.accounts
                if (selectedAccountId == null && it.accounts.isNotEmpty()) {
                    selectedAccountId = it.accounts.first().id
                }
                if (selectedToAccountId == null && it.accounts.size > 1) {
                    selectedToAccountId = it.accounts[1].id
                }
            }
            NativeLedgerApi.getCategories(context).onSuccess { (exp, inc) ->
                expenseCategories = exp
                incomeCategories = inc
                if (exp.isNotEmpty() && txType == "expense") {
                    selectedCategory = exp.first().name
                }
            }
        }
    }

    LaunchedEffect(txType) {
        if (txType == "expense") {
            if (expenseCategories.isNotEmpty() && expenseCategories.none { it.name == selectedCategory }) {
                selectedCategory = expenseCategories.first().name
            }
        } else if (txType == "income") {
            if (incomeCategories.isNotEmpty() && incomeCategories.none { it.name == selectedCategory }) {
                selectedCategory = incomeCategories.first().name
            }
        } else if (txType == "transfer") {
            selectedCategory = "内部划转"
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
                Text(
                    text = "快速记账",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(10.dp))

                // 类型切换 Segmented Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate100, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val types = listOf(
                        "expense" to "支出 (Expense)",
                        "income" to "收入 (Income)",
                        "transfer" to "自转 (Transfer)"
                    )
                    types.forEach { (typeKey, label) ->
                        val isSelected = txType == typeKey
                        val bg = if (isSelected) {
                            when (typeKey) {
                                "income" -> Emerald600
                                "transfer" -> Sky500
                                else -> Indigo600
                            }
                        } else Color.Transparent

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bg,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { txType = typeKey }
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Slate600,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
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
            // 大字号金额输入卡片
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text("记账金额", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Slate400)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MYR",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate600
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = {
                                if (it.isEmpty() || it.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                                    amountText = it
                                }
                            },
                            placeholder = { Text("0.00", fontSize = 28.sp, color = Slate400) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (txType) {
                                    "income" -> Emerald600
                                    "transfer" -> Sky500
                                    else -> Indigo600
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 常用金额微调快捷胶囊
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("10", "20", "50", "100").forEach { quickAmt ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Slate100,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val current = amountText.toDoubleOrNull() ?: 0.0
                                        amountText = String.format(Locale.US, "%.2f", current + quickAmt.toDouble())
                                    }
                            ) {
                                Text(
                                    text = "+$quickAmt",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 分类选择网格 (支出与收入)
            if (txType != "transfer") {
                val currentCats = if (txType == "income") incomeCategories else expenseCategories
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 0.5.dp,
                    border = BorderStroke(1.dp, Slate100),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text("选择分类", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Spacer(modifier = Modifier.height(12.dp))

                        val displayCats = if (currentCats.isNotEmpty()) currentCats else {
                            listOf(
                                CategoryRecord(0, "餐饮", "expense"),
                                CategoryRecord(0, "交通", "expense"),
                                CategoryRecord(0, "杂货", "expense"),
                                CategoryRecord(0, "账单", "expense"),
                                CategoryRecord(0, "购物", "expense"),
                                CategoryRecord(0, "娱乐", "expense"),
                                CategoryRecord(0, "医疗", "expense"),
                                CategoryRecord(0, "其他", "expense")
                            )
                        }

                        // 分两行/多行网格展示
                        val chunked = displayCats.chunked(4)
                        chunked.forEach { rowCats ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCats.forEach { cat ->
                                    val isSelected = selectedCategory == cat.name
                                    val emoji = CategoryUiHelper.getCategoryEmoji(cat.name, txType)
                                    val tintColor = if (isSelected) Indigo600 else Slate100

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = tintColor,
                                        border = if (isSelected) BorderStroke(1.5.dp, Indigo600) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedCategory = cat.name }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(vertical = 10.dp)
                                        ) {
                                            Text(emoji, fontSize = 20.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = cat.name,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else Slate700
                                            )
                                        }
                                    }
                                }
                                // 填充空白保持网格对齐
                                if (rowCats.size < 4) {
                                    for (i in 0 until (4 - rowCats.size)) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 账户选择
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (txType == "transfer") "转出账户" else "结算账户",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (accounts.isEmpty()) {
                        Text("暂无已配置账户，将使用默认账户记录", fontSize = 12.sp, color = Slate400)
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            accounts.forEach { acc ->
                                val isSelected = selectedAccountId == acc.id
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Indigo600 else Slate100,
                                    modifier = Modifier.clickable { selectedAccountId = acc.id }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(CategoryUiHelper.getAccountTypeIcon(acc.type), fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
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
                    if (txType == "transfer") {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("转入账户", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            accounts.forEach { acc ->
                                val isSelected = selectedToAccountId == acc.id
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Sky500 else Slate100,
                                    modifier = Modifier.clickable { selectedToAccountId = acc.id }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(CategoryUiHelper.getAccountTypeIcon(acc.type), fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
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

            Spacer(modifier = Modifier.height(14.dp))

            // 备注、标签与日期
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("商户 / 备注说明") },
                        placeholder = { Text("可选，如：Starbucks、麦当劳、超市买菜") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Indigo600,
                            focusedLabelColor = Indigo600
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("标签 (Tags)") },
                        placeholder = { Text("可选，如：#出差 #聚会 #报销") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Indigo600,
                            focusedLabelColor = Indigo600
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("记账日期 (YYYY-MM-DD)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Indigo600,
                            focusedLabelColor = Indigo600
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            Button(
                onClick = {
                    val amt = amountText.toDoubleOrNull() ?: 0.0
                    if (amt <= 0) {
                        Toast.makeText(context, "请输入大于 0 的有效金额", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val res = NativeLedgerApi.addTransaction(
                            context = context,
                            type = txType,
                            amount = amt,
                            category = selectedCategory,
                            date = dateStr,
                            note = note,
                            tags = tags,
                            accountId = selectedAccountId,
                            toAccountId = if (txType == "transfer") selectedToAccountId else null
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, "🎉 记账成功！", Toast.LENGTH_SHORT).show()
                            amountText = ""
                            note = ""
                            tags = ""
                            onFinished()
                        }.onFailure { err ->
                            Toast.makeText(context, "记账失败：${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isSaving,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (txType) {
                        "income" -> Emerald600
                        "transfer" -> Sky500
                        else -> Indigo600
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (txType == "transfer") "确认转账并记录" else "保存这笔账目",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
