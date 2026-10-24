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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
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
import com.siangloh.ledger.data.InstallmentRecord
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentManagementPage(
    installments: List<InstallmentRecord>,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingInst by remember { mutableStateOf<InstallmentRecord?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "分期与债务追踪",
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
                            tint = Color(0xFFD97706)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Slate50
    ) { paddingValues ->
        val totalRemaining = installments.sumOf { it.remainingAmount }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 总览 Card
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
                            Text(text = "累计剩余待还总额", fontSize = 13.sp, color = Slate600)
                            Text(
                                text = "共 ${installments.size} 个还款计划",
                                fontSize = 11.sp,
                                color = Slate400
                            )
                        }
                        Text(
                            text = String.format(Locale.getDefault(), "RM %.2f", totalRemaining),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ExpenseRed
                        )
                    }
                }
            }

            if (installments.isEmpty()) {
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
                                .background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无活跃分期计划",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "添加信用卡分期、贷款或大额购买分期计划",
                            fontSize = 12.sp,
                            color = Slate500,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加首个分期计划")
                        }
                    }
                }
            } else {
                items(installments) { inst ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingInst = inst },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Slate200)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = inst.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                                Text(
                                    text = String.format(Locale.getDefault(), "RM %.2f", inst.remainingAmount),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ExpenseRed
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "进度: ${inst.paidPeriods}/${inst.tenureMonths} 期 · 月供 RM ${String.format(Locale.getDefault(), "%.2f", inst.monthlyAmount)}",
                                    fontSize = 11.sp,
                                    color = Slate500
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                isSubmitting = true
                                                val res = NativeLedgerApi.payInstallmentPeriod(context, inst.id)
                                                isSubmitting = false
                                                res.onSuccess {
                                                    Toast.makeText(context, "已成功打卡本期还款", Toast.LENGTH_SHORT).show()
                                                    onRefresh()
                                                }.onFailure {
                                                    Toast.makeText(context, it.message ?: "打卡失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = inst.paidPeriods < inst.tenureMonths && !isSubmitting
                                    ) {
                                        Text("打卡本期", fontSize = 12.sp, color = PrimaryTeal, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(onClick = { editingInst = inst }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Slate400, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val progressPct = if (inst.tenureMonths > 0) (inst.paidPeriods.toFloat() / inst.tenureMonths.toFloat()).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(
                                progress = { progressPct },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (progressPct >= 1f) IncomeGreen else Color(0xFFD97706),
                                trackColor = Slate100
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 新增/编辑分期子弹窗
    val activeInst = editingInst
    if (showAddDialog || activeInst != null) {
        var titleInput by remember { mutableStateOf(activeInst?.title ?: "") }
        var totalAmountInput by remember { mutableStateOf(if (activeInst != null) String.format(Locale.getDefault(), "%.2f", activeInst.totalAmount) else "") }
        var tenureInput by remember { mutableStateOf(activeInst?.tenureMonths?.toString() ?: "12") }
        var paidInput by remember { mutableStateOf(activeInst?.paidPeriods?.toString() ?: "0") }
        var monthlyInput by remember { mutableStateOf(if (activeInst != null) String.format(Locale.getDefault(), "%.2f", activeInst.monthlyAmount) else "") }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingInst = null
            },
            containerColor = Color.White,
            title = {
                Text(
                    text = if (activeInst != null) "编辑分期项目" else "添加新的分期/借款",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("分期/债务名称 (如: iPhone 免息分期)", color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = totalAmountInput,
                        onValueChange = { totalAmountInput = it },
                        label = { Text("总欠款金额 (MYR)", color = Slate600) },
                        placeholder = { Text("0.00", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tenureInput,
                            onValueChange = { tenureInput = it },
                            label = { Text("总期数", color = Slate600) },
                            placeholder = { Text("12", color = Slate400) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors()
                        )
                        OutlinedTextField(
                            value = paidInput,
                            onValueChange = { paidInput = it },
                            label = { Text("已还期数", color = Slate600) },
                            placeholder = { Text("0", color = Slate400) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(color = Slate900),
                            colors = ledgerInputColors()
                        )
                    }
                    OutlinedTextField(
                        value = monthlyInput,
                        onValueChange = { monthlyInput = it },
                        label = { Text("月供金额 (留空按期数自动计算)", color = Slate600) },
                        placeholder = { Text("可选", color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeInst != null) {
                        TextButton(
                            onClick = {
                                isSubmitting = true
                                scope.launch {
                                    val res = NativeLedgerApi.deleteInstallment(context, activeInst.id)
                                    isSubmitting = false
                                    res.onSuccess {
                                        Toast.makeText(context, "已删除分期记录", Toast.LENGTH_SHORT).show()
                                        editingInst = null
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
                            val t = titleInput.trim()
                            val tot = totalAmountInput.toDoubleOrNull() ?: 0.0
                            val ten = tenureInput.toIntOrNull() ?: 12
                            val pd = paidInput.toIntOrNull() ?: 0
                            val mo = monthlyInput.toDoubleOrNull()
                            if (t.isEmpty() || tot <= 0.0 || ten <= 0) {
                                Toast.makeText(context, "请输入有效的分期名称、总金额及期数", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveInstallment(
                                    context = context,
                                    id = activeInst?.id,
                                    title = t,
                                    totalAmount = tot,
                                    tenureMonths = ten,
                                    paidPeriods = pd,
                                    monthlyAmount = mo
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, "分期记录已保存", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                    editingInst = null
                                    onRefresh()
                                }.onFailure {
                                    Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
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
                        editingInst = null
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}
