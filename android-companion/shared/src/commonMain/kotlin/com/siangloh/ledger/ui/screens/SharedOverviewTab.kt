package com.siangloh.ledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.shiftMonth
import com.siangloh.ledger.data.SharedAccountsSummary
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.data.SharedOverviewStats
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.ui.components.SharedCategoryBadge
import com.siangloh.ledger.ui.components.SharedMonthSwitcher
import com.siangloh.ledger.ui.theme.ExpenseRed
import com.siangloh.ledger.ui.theme.IncomeGreen
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Slate100
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate400
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50

@Composable
fun SharedOverviewTab(
    dataRevision: Int,
    onNavigateToAdd: () -> Unit,
    onNavigateToRecords: () -> Unit
) {
    var selectedMonth by remember { mutableStateOf(currentMonthString()) }
    var slideDir by remember { mutableStateOf(0) }
    var overviewStats by remember {
        mutableStateOf(
            SharedOverviewStats(
                period = "month",
                yearMonth = selectedMonth,
                income = 0.0,
                expense = 0.0,
                savings = 0.0,
                netBalance = 0.0,
                recentTransactions = emptyList()
            )
        )
    }
    var accountsSummary by remember {
        mutableStateOf(
            SharedAccountsSummary(
                totalAssets = 0.0,
                totalLiabilities = 0.0,
                netWorth = 0.0,
                items = emptyList()
            )
        )
    }

    LaunchedEffect(selectedMonth, dataRevision) {
        SharedLedgerEngine.initDefaultsIfNeeded()
        val loadedStats = SharedLedgerEngine.getOverviewStats(selectedMonth)
        val loadedAccounts = SharedLedgerEngine.getAccountsSummary(selectedMonth)
        overviewStats = loadedStats
        accountsSummary = loadedAccounts
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Overview",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate900
                )

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Slate200)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Net Worth RM ${formatMoney(accountsSummary.netWorth)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            SharedMonthSwitcher(
                yearMonth = selectedMonth,
                slideDir = slideDir,
                onPrev = {
                    slideDir = -1
                    selectedMonth = shiftMonth(selectedMonth, -1)
                },
                onNext = {
                    slideDir = 1
                    selectedMonth = shiftMonth(selectedMonth, 1)
                }
            )
        }

        item {
            OverviewHeroCard(stats = overviewStats)
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Recent Transactions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onNavigateToRecords) {
                        Text(
                            text = "View All",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryTeal
                        )
                    }

                    Surface(
                        onClick = onNavigateToAdd,
                        shape = CircleShape,
                        color = PrimaryTeal,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add transaction",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (overviewStats.recentTransactions.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Slate200)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Teal50,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.ReceiptLong,
                                    contentDescription = null,
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No transactions yet",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Record your first entry for $selectedMonth.",
                            fontSize = 13.sp,
                            color = Slate500
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToAdd,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryTeal,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Add Transaction",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        } else {
            items(
                items = overviewStats.recentTransactions,
                key = { it.id }
            ) { tx ->
                OverviewTransactionRow(tx = tx)
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OverviewHeroCard(stats: SharedOverviewStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Net Balance",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Slate500
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "RM ${formatMoney(stats.netBalance)}",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (stats.netBalance < 0.0) ExpenseRed else Slate900,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = Slate100, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeroMetricItem(
                    label = "Income",
                    amount = stats.income,
                    valueColor = IncomeGreen,
                    modifier = Modifier.weight(1f)
                )
                HeroMetricItem(
                    label = "Expense",
                    amount = stats.expense,
                    valueColor = ExpenseRed,
                    modifier = Modifier.weight(1f)
                )
                HeroMetricItem(
                    label = "Savings",
                    amount = stats.savings,
                    valueColor = PrimaryTeal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroMetricItem(
    label: String,
    amount: Double,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Slate500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "RM ${formatMoney(amount)}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OverviewTransactionRow(tx: LocalTransaction) {
    val cleanCategory = remember(tx.category) { SharedLedgerEngine.cleanCategoryName(tx.category) }
    val amountPrefix = when (tx.type) {
        "income" -> "+"
        "expense" -> "-"
        else -> ""
    }
    val amountColor = when (tx.type) {
        "income" -> IncomeGreen
        "expense" -> ExpenseRed
        "savings" -> PrimaryTeal
        else -> Slate600
    }
    val subtitle = buildString {
        if (tx.note.isNotBlank()) {
            append(tx.note.trim())
            append(" · ")
        }
        append(tx.date)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SharedCategoryBadge(
                category = cleanCategory,
                type = tx.type,
                size = 40.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cleanCategory,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Slate400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = "${amountPrefix}RM ${formatMoney(tx.amount)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                maxLines = 1
            )
        }
    }
}
