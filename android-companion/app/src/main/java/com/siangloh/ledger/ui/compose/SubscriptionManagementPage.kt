package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import com.siangloh.ledger.R
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.SubscriptionRecord
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionManagementPage(
    subscriptions: List<SubscriptionRecord>,
    userCategories: List<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingSub by remember { mutableStateOf<SubscriptionRecord?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "订阅与周期记账",
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加",
                            tint = PrimaryTeal
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Slate50
    ) { paddingValues ->
        val totalCost = subscriptions.sumOf { it.cost }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 月度总览 Card
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
                            onClick = { showAddDialog = true },
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = String.format(Locale.getDefault(), "RM %.2f", sub.cost),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = { editingSub = sub }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Slate400, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 新增/编辑订阅子弹窗
    val activeSub = editingSub
    if (showAddDialog || activeSub != null) {
        var nameInput by remember { mutableStateOf(activeSub?.name ?: "") }
        var costInput by remember { mutableStateOf(if (activeSub != null) String.format(Locale.getDefault(), "%.2f", activeSub.cost) else "") }
        var categoryInput by remember { mutableStateOf(activeSub?.category ?: if (userCategories.isNotEmpty()) userCategories.first() else "日常") }
        var dateInput by remember { mutableStateOf(activeSub?.nextBillingDate ?: "每月1号") }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingSub = null
            },
            containerColor = Color.White,
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
                    OutlinedTextField(
                        value = dateInput,
                        onValueChange = { dateInput = it },
                        label = { Text("下次扣款周期 (如: 每月15号)", color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeSub != null) {
                        TextButton(
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    val res = NativeLedgerApi.deleteSubscription(context, activeSub.id)
                                    isSubmitting = false
                                    res.onSuccess {
                                        Toast.makeText(context, "已删除订阅", Toast.LENGTH_SHORT).show()
                                        editingSub = null
                                        onRefresh()
                                    }.onFailure {
                                        Toast.makeText(context, "删除失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("删除", color = ExpenseRed)
                        }
                    }

                    Button(
                        onClick = {
                            val n = nameInput.trim()
                            val c = costInput.toDoubleOrNull() ?: 0.0
                            val cat = categoryInput.trim().ifEmpty { "账单" }
                            val dt = dateInput.trim().ifEmpty { "每月1号" }
                            if (n.isEmpty() || c <= 0.0) {
                                Toast.makeText(context, "请输入有效的名称和扣费金额", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveSubscription(
                                    context = context,
                                    id = activeSub?.id,
                                    name = n,
                                    category = cat,
                                    cost = c,
                                    currency = "MYR",
                                    nextBillingDate = dt,
                                    autoRenew = true
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, "订阅已保存", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                    editingSub = null
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("保存", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        editingSub = null
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}
