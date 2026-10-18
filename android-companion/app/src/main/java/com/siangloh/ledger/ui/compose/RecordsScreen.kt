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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.TransactionRecord
import com.siangloh.ledger.ui.compose.components.RecordsSkeletonList
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    onNavigateToAdd: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf("all") } // 'all', 'expense', 'income', 'transfer'
    var selectedMonth by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date()))
    }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var transactions by remember { mutableStateOf<List<TransactionRecord>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var editingTx by remember { mutableStateOf<TransactionRecord?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<TransactionRecord?>(null) }

    fun loadData(resetPage: Boolean = true) {
        if (resetPage) {
            currentPage = 1
            isLoading = true
        } else {
            isLoadingMore = true
        }
        scope.launch {
            val targetPage = if (resetPage) 1 else currentPage
            val res = NativeLedgerApi.getTransactions(
                context = context,
                type = selectedType,
                month = selectedMonth,
                search = searchQuery,
                page = targetPage,
                limit = 50
            )
            isLoading = false
            isLoadingMore = false
            res.onSuccess { page ->
                if (resetPage) {
                    transactions = page.items
                } else {
                    val existingIds = transactions.map { it.id }.toSet()
                    val newItems = page.items.filter { it.id !in existingIds }
                    transactions = transactions + newItems
                }
                totalCount = page.totalCount
                hasMore = page.hasMore
            }.onFailure { err ->
                Toast.makeText(context, "加载流水失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(selectedType, selectedMonth, searchQuery) {
        loadData(resetPage = true)
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
                // 顶部标题与快速记账
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "账本流水明细",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    IconButton(
                        onClick = onNavigateToAdd,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Indigo600, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "记一笔",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 月份切换控件
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            selectedMonth = shiftMonthString(selectedMonth, -1)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "上月",
                            tint = Slate600
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Slate100,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = formatDisplayMonth(selectedMonth),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate800,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            selectedMonth = shiftMonthString(selectedMonth, 1)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "下月",
                            tint = Slate600
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 搜索输入框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索商户、备注或标签...", fontSize = 13.sp, color = Slate400) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "清除", tint = Slate400, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Indigo600,
                        unfocusedBorderColor = Slate200,
                        focusedContainerColor = Slate50,
                        unfocusedContainerColor = Slate50
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 类型切换 Chip 栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val types = listOf(
                        "all" to "全部",
                        "expense" to "支出",
                        "income" to "收入",
                        "transfer" to "自转"
                    )
                    types.forEach { (typeKey, typeLabel) ->
                        val isSelected = selectedType == typeKey
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) Indigo600 else Slate100,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedType = typeKey }
                        ) {
                            Text(
                                text = typeLabel,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
                .background(Slate50)
        ) {
            if (isLoading && transactions.isEmpty()) {
                RecordsSkeletonList()
            } else if (transactions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "📭", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "本期暂无符合条件的流水",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate700
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "可尝试切换月份，或点击下方【记一笔】快速记账",
                        fontSize = 12.sp,
                        color = Slate400,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToAdd,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("记一笔支出/收入", fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(transactions, key = { it.id }) { tx ->
                        TransactionCard(
                            tx = tx,
                            onClick = { editingTx = tx }
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Indigo600)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            currentPage += 1
                                            loadData(resetPage = false)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("加载更多流水 (已展示 ${transactions.size} / $totalCount 条)", fontSize = 13.sp, color = Indigo600)
                                    }
                                }
                            }
                        }
                    } else if (transactions.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "已展示全部 $totalCount 笔记录",
                                    fontSize = 12.sp,
                                    color = Slate400
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
    }

    // 编辑弹窗
    editingTx?.let { tx ->
        EditTransactionBottomSheet(
            tx = tx,
            onDismiss = { editingTx = null },
            onSaveSuccess = {
                editingTx = null
                loadData()
            },
            onDeleteClick = {
                val toDelete = tx
                editingTx = null
                showDeleteConfirm = toDelete
            }
        )
    }

    // 删除二次确认弹窗
    showDeleteConfirm?.let { tx ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = Color.White,
            titleContentColor = Slate900,
            textContentColor = Slate800,
            title = { Text("确认删除此记录？", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Slate900) },
            text = { Text("删除后流水将无法找回（金额：MYR ${String.format(Locale.US, "%.2f", tx.amount)}，备注：${tx.note.ifBlank { "无备注" }}）", fontSize = 13.sp, color = Slate600) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = NativeLedgerApi.deleteTransaction(context, tx.id)
                            showDeleteConfirm = null
                            editingTx = null
                            res.onSuccess {
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                loadData()
                            }.onFailure {
                                Toast.makeText(context, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500)
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消", color = Slate600)
                }
            }
        )
    }
}

@Composable
fun TransactionCard(
    tx: TransactionRecord,
    onClick: () -> Unit
) {
    val emoji = CategoryUiHelper.getCategoryEmoji(tx.category, tx.type)
    val catColor = CategoryUiHelper.getCategoryColor(tx.category)

    val amountColor = when (tx.type) {
        "income" -> Emerald600
        "transfer" -> Sky500
        else -> Slate900
    }

    val amountPrefix = when (tx.type) {
        "income" -> "+"
        "transfer" -> ""
        else -> "-"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 0.5.dp,
        border = BorderStroke(1.dp, Slate100),
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
                // Emoji 图标背景圆圈
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(catColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx.note.ifBlank { tx.category },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 分类胶囊
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100
                        ) {
                            Text(
                                text = tx.category,
                                fontSize = 11.sp,
                                color = Slate600,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        // 账户信息
                        if (tx.accountName != "未指定") {
                            Text(
                                text = tx.accountName,
                                fontSize = 11.sp,
                                color = Slate400,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 日期
                        Text(
                            text = tx.date,
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                    if (tx.tags.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tx.tags,
                            fontSize = 10.sp,
                            color = Indigo600,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 金额显示
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$amountPrefix MYR ${String.format(Locale.US, "%.2f", tx.amount)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                if (tx.type == "transfer") {
                    Text(
                        text = "内部互转",
                        fontSize = 10.sp,
                        color = Sky500,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionBottomSheet(
    tx: TransactionRecord,
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var amountStr by remember { mutableStateOf(String.format(Locale.US, "%.2f", tx.amount)) }
    var category by remember { mutableStateOf(tx.category) }
    var dateStr by remember { mutableStateOf(tx.date) }
    var note by remember { mutableStateOf(tx.note) }
    var tags by remember { mutableStateOf(tx.tags) }
    var txType by remember { mutableStateOf(tx.type) }
    var isSaving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("编辑交易记录", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = Rose500)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 金额输入
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("金额 (MYR)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Indigo600,
                    focusedLabelColor = Indigo600
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 类别与日期
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Indigo600,
                        focusedLabelColor = Indigo600
                    )
                )
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = { dateStr = it },
                    label = { Text("日期 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Indigo600,
                        focusedLabelColor = Indigo600
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 备注
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("商户 / 备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Indigo600,
                    focusedLabelColor = Indigo600
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 标签
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("标签 (如: #food #lunch)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Indigo600,
                    focusedLabelColor = Indigo600
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 保存按钮
            Button(
                onClick = {
                    val amt = amountStr.toDoubleOrNull() ?: 0.0
                    if (amt <= 0) {
                        Toast.makeText(context, "请输入有效金额", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val res = NativeLedgerApi.updateTransaction(
                            context = context,
                            txId = tx.id,
                            type = txType,
                            amount = amt,
                            category = category,
                            date = dateStr,
                            note = note,
                            tags = tags,
                            accountId = tx.accountId
                        )
                        isSaving = false
                        res.onSuccess {
                            Toast.makeText(context, "更新成功", Toast.LENGTH_SHORT).show()
                            onSaveSuccess()
                        }.onFailure {
                            Toast.makeText(context, "更新失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo600)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text("保存修改", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

fun shiftMonthString(monthStr: String, delta: Int): String {
    return try {
        val parts = monthStr.split("-")
        var y = parts[0].toInt()
        var m = parts[1].toInt() + delta
        while (m > 12) {
            m -= 12
            y += 1
        }
        while (m < 1) {
            m += 12
            y -= 1
        }
        String.format(Locale.US, "%04d-%02d", y, m)
    } catch (_: Exception) {
        monthStr
    }
}

fun formatDisplayMonth(monthStr: String): String {
    return try {
        val parts = monthStr.split("-")
        "${parts[0]}年${parts[1].toInt()}月"
    } catch (_: Exception) {
        monthStr
    }
}
