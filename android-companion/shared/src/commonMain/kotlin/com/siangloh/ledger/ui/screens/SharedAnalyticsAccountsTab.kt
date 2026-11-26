package com.siangloh.ledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.shiftMonth
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.SharedAccountItem
import com.siangloh.ledger.data.SharedAccountsSummary
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.data.SharedOverviewStats
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.ui.components.SharedCategoryBadge
import com.siangloh.ledger.ui.components.SharedMonthSwitcher
import com.siangloh.ledger.ui.components.SharedSegmentedTabs
import com.siangloh.ledger.ui.theme.Emerald50
import com.siangloh.ledger.ui.theme.ExpenseRed
import com.siangloh.ledger.ui.theme.IncomeGreen
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Rose50
import com.siangloh.ledger.ui.theme.Slate100
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate400
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Slate700
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50
import com.siangloh.ledger.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DonutPalette = listOf(
    Color(0xFF0F766E),
    Color(0xFFDC2626),
    Color(0xFFD97706),
    Color(0xFF2563EB),
    Color(0xFF7C3AED),
    Color(0xFFDB2777),
    Color(0xFF059669),
    Color(0xFF64748B)
)

private data class CategorySlice(
    val category: String,
    val amount: Double,
    val ratio: Float,
    val color: Color
)

private data class AccountGroupDef(
    val typeKey: String,
    val label: String,
    val icon: ImageVector
)

private val AccountGroups = listOf(
    AccountGroupDef("e_wallet", "E-Wallet", Icons.Rounded.AccountBalanceWallet),
    AccountGroupDef("savings", "Bank", Icons.Rounded.AccountBalance),
    AccountGroupDef("credit_card", "Credit Card", Icons.Rounded.CreditCard),
    AccountGroupDef("cash", "Cash", Icons.Rounded.Payments),
    AccountGroupDef("other", "Other", Icons.Rounded.Savings)
)

@Composable
fun SharedAnalyticsAccountsTab(
    dataRevision: Int,
    onDataChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedSubTab by remember { mutableIntStateOf(0) }
    var selectedMonth by remember { mutableStateOf(currentMonthString()) }
    var slideDir by remember { mutableIntStateOf(0) }
    var hideBalances by remember { mutableStateOf(false) }

    var stats by remember { mutableStateOf<SharedOverviewStats?>(null) }
    var monthTxs by remember { mutableStateOf<List<LocalTransaction>>(emptyList()) }
    var accountsSummary by remember { mutableStateOf<SharedAccountsSummary?>(null) }

    var editingAccount by remember { mutableStateOf<LocalAccount?>(null) }
    var editBalanceText by remember { mutableStateOf("") }

    LaunchedEffect(selectedMonth, dataRevision) {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val db = AppDatabase.getInstance()
        stats = SharedLedgerEngine.getOverviewStats(selectedMonth, db)
        monthTxs = db.localTransactionDao().getByMonth("$selectedMonth%")
        accountsSummary = SharedLedgerEngine.getAccountsSummary(selectedMonth, db)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SharedSegmentedTabs(
            options = listOf("Cashflow", "Accounts"),
            selectedIndex = selectedSubTab,
            onSelect = { selectedSubTab = it }
        )

        if (selectedSubTab == 0) {
            CashflowSection(
                selectedMonth = selectedMonth,
                slideDir = slideDir,
                onPrevMonth = {
                    slideDir = -1
                    selectedMonth = shiftMonth(selectedMonth, -1)
                },
                onNextMonth = {
                    slideDir = 1
                    selectedMonth = shiftMonth(selectedMonth, 1)
                },
                stats = stats,
                monthTransactions = monthTxs
            )
        } else {
            AccountsSection(
                summary = accountsSummary,
                hideBalances = hideBalances,
                onToggleHide = { hideBalances = !hideBalances },
                onTapAccount = { acc ->
                    editingAccount = acc
                    editBalanceText = formatMoney(acc.balance, decimals = 2, grouping = false)
                }
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    val targetAccount = editingAccount
    if (targetAccount != null) {
        AlertDialog(
            onDismissRequest = { editingAccount = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Adjust Balance",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = targetAccount.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate600
                    )
                    OutlinedTextField(
                        value = editBalanceText,
                        onValueChange = { editBalanceText = it },
                        label = { Text("Current Balance (RM)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = Slate200,
                            focusedLabelColor = PrimaryTeal
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = editBalanceText.trim().toDoubleOrNull()
                        if (parsed == null) {
                            showPlatformToast("Please enter a valid number")
                            return@Button
                        }
                        scope.launch {
                            try {
                                AppDatabase.getInstance()
                                    .localAccountDao()
                                    .update(targetAccount.copy(balance = parsed))
                                editingAccount = null
                                showPlatformToast("Balance updated")
                                onDataChanged()
                            } catch (e: Exception) {
                                showPlatformToast("Failed to update balance")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryTeal,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAccount = null }) {
                    Text("Cancel", color = Slate600)
                }
            }
        )
    }
}

@Composable
private fun CashflowSection(
    selectedMonth: String,
    slideDir: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    stats: SharedOverviewStats?,
    monthTransactions: List<LocalTransaction>
) {
    SharedMonthSwitcher(
        yearMonth = selectedMonth,
        slideDir = slideDir,
        onPrev = onPrevMonth,
        onNext = onNextMonth
    )

    val income = stats?.income ?: 0.0
    val expense = stats?.expense ?: 0.0
    val savings = stats?.savings ?: 0.0
    val netBalance = stats?.netBalance ?: 0.0

    // Summary Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Net Balance",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate500
                )
                Text(
                    text = "RM ${formatMoney(netBalance)}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (netBalance >= 0.0) Slate900 else ExpenseRed,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }

            HorizontalDivider(color = Slate100)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CashflowMetricTile(
                    label = "Income",
                    amount = income,
                    color = IncomeGreen,
                    bg = Emerald50,
                    modifier = Modifier.weight(1f)
                )
                CashflowMetricTile(
                    label = "Expense",
                    amount = expense,
                    color = ExpenseRed,
                    bg = Rose50,
                    modifier = Modifier.weight(1f)
                )
                CashflowMetricTile(
                    label = "Savings",
                    amount = savings,
                    color = PrimaryTeal,
                    bg = Teal50,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Expense Category Breakdown & Donut Chart
    val slices = remember(monthTransactions) {
        val grouped = LinkedHashMap<String, Double>()
        for (tx in monthTransactions) {
            if (tx.type == "expense" && tx.amount > 0.0) {
                val cat = SharedLedgerEngine.cleanCategoryName(tx.category)
                grouped[cat] = (grouped[cat] ?: 0.0) + tx.amount
            }
        }
        val sorted = grouped.entries.sortedByDescending { it.value }
        val total = sorted.sumOf { it.value }
        if (total <= 0.0) {
            emptyList()
        } else {
            sorted.mapIndexed { index, entry ->
                CategorySlice(
                    category = entry.key,
                    amount = entry.value,
                    ratio = (entry.value / total).toFloat().coerceIn(0f, 1f),
                    color = DonutPalette[index % DonutPalette.size]
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Expense Breakdown",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )

            if (slices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PieChart,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "No expenses recorded this month",
                        fontSize = 13.sp,
                        color = Slate500
                    )
                }
            } else {
                val totalExpense = slices.sumOf { it.amount }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(168.dp)) {
                        val strokeWidth = 26.dp.toPx()
                        val diameter = size.minDimension - strokeWidth
                        val topLeft = Offset(
                            (size.width - diameter) / 2f,
                            (size.height - diameter) / 2f
                        )
                        val arcSize = Size(diameter, diameter)
                        var startAngle = -90f
                        slices.forEach { slice ->
                            val sweep = (slice.ratio * 360f).coerceAtLeast(1.5f)
                            drawArc(
                                color = slice.color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            startAngle += slice.ratio * 360f
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Total Expense",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate500
                        )
                        Text(
                            text = "RM ${formatMoney(totalExpense)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                }

                HorizontalDivider(color = Slate100)

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    slices.forEach { slice ->
                        val pct = (slice.ratio * 100f).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SharedCategoryBadge(
                                category = slice.category,
                                type = "expense",
                                size = 36.dp
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(slice.color)
                                        )
                                        Text(
                                            text = slice.category,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate900,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$pct%",
                                            fontSize = 12.sp,
                                            color = Slate500,
                                            style = TextStyle(fontFeatureSettings = "tnum")
                                        )
                                    }
                                    Text(
                                        text = "RM ${formatMoney(slice.amount)}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate900,
                                        style = TextStyle(fontFeatureSettings = "tnum")
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Slate100)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(slice.ratio.coerceAtLeast(0.02f))
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(slice.color)
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

@Composable
private fun CashflowMetricTile(
    label: String,
    amount: Double,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Slate600
        )
        Text(
            text = "RM ${formatMoney(amount)}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@Composable
private fun AccountsSection(
    summary: SharedAccountsSummary?,
    hideBalances: Boolean,
    onToggleHide: () -> Unit,
    onTapAccount: (LocalAccount) -> Unit
) {
    val netWorth = summary?.netWorth ?: 0.0
    val assets = summary?.totalAssets ?: 0.0
    val liabilities = summary?.totalLiabilities ?: 0.0
    val items = summary?.items ?: emptyList()

    // Crisp White Net Worth Hero Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Net Worth",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate500
                )
                IconButton(
                    onClick = onToggleHide,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (hideBalances) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (hideBalances) "Show balances" else "Hide balances",
                        tint = Slate500,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = if (hideBalances) "RM ••••••" else "RM ${formatMoney(netWorth)}",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (!hideBalances && netWorth < 0.0) ExpenseRed else Slate900,
                style = TextStyle(fontFeatureSettings = "tnum")
            )

            HorizontalDivider(color = Slate100)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total Assets",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate500
                    )
                    Text(
                        text = if (hideBalances) "RM ••••" else "RM ${formatMoney(assets)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = IncomeGreen,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total Liabilities",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate500
                    )
                    Text(
                        text = if (hideBalances) "RM ••••" else "RM ${formatMoney(liabilities)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (liabilities > 0.0) ExpenseRed else Slate700,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }

    val knownKeys = setOf("e_wallet", "savings", "credit_card", "cash")
    AccountGroups.forEach { group ->
        val groupItems = if (group.typeKey == "other") {
            items.filter { it.account.type !in knownKeys }
        } else {
            items.filter { it.account.type == group.typeKey }
        }
        if (groupItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = group.icon,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = group.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate700
                    )
                }

                groupItems.forEach { item ->
                    AccountItemCard(
                        item = item,
                        icon = group.icon,
                        hideBalances = hideBalances,
                        onClick = { onTapAccount(item.account) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountItemCard(
    item: SharedAccountItem,
    icon: ImageVector,
    hideBalances: Boolean,
    onClick: () -> Unit
) {
    val acc = item.account
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Teal50),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = acc.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = Slate400,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "Tap to adjust",
                                fontSize = 11.sp,
                                color = Slate400
                            )
                        }
                    }
                }

                Text(
                    text = if (hideBalances) "RM ••••" else "RM ${formatMoney(acc.balance)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (!hideBalances && acc.balance < 0.0) ExpenseRed else Slate900,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Emerald50
                ) {
                    Text(
                        text = if (hideBalances) {
                            "In ••••"
                        } else {
                            "In +RM ${formatMoney(item.monthlyIn)}"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IncomeGreen,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Rose50
                ) {
                    Text(
                        text = if (hideBalances) {
                            "Out ••••"
                        } else {
                            "Out -RM ${formatMoney(item.monthlyOut)}"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ExpenseRed,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (acc.type == "credit_card" && acc.creditLimit > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate100
                    ) {
                        Text(
                            text = if (hideBalances) {
                                "Limit ••••"
                            } else {
                                "Limit RM ${formatMoney(acc.creditLimit, decimals = 0)}"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WarningAmber,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
