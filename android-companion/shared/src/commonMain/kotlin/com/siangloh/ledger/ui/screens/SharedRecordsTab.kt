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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.ui.components.SharedCategoryBadge
import com.siangloh.ledger.ui.components.SharedMonthSwitcher
import com.siangloh.ledger.ui.components.SharedSegmentedTabs
import com.siangloh.ledger.ui.theme.ExpenseRed
import com.siangloh.ledger.ui.theme.IncomeGreen
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate400
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RecordFilterOptions = listOf("All", "Expense", "Income", "Transfer")

@Composable
fun SharedRecordsTab(
    dataRevision: Int,
    onDataChanged: () -> Unit,
    onNavigateToAdd: () -> Unit
) {
    var selectedMonth by remember { mutableStateOf(currentMonthString()) }
    var slideDir by remember { mutableStateOf(0) }
    var selectedFilterIndex by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var monthTransactions by remember { mutableStateOf<List<LocalTransaction>>(emptyList()) }
    var pendingDeleteTx by remember { mutableStateOf<LocalTransaction?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedMonth, dataRevision) {
        val loaded = withContext(Dispatchers.IO) {
            AppDatabase.getInstance().localTransactionDao().getByMonth("$selectedMonth%")
        }
        monthTransactions = loaded
    }

    val filteredTransactions = remember(monthTransactions, selectedFilterIndex, searchQuery) {
        val query = searchQuery.trim().lowercase()
        monthTransactions.filter { tx ->
            val matchesType = when (selectedFilterIndex) {
                1 -> tx.type == "expense"
                2 -> tx.type == "income"
                3 -> tx.type == "transfer"
                else -> true
            }
            if (!matchesType) return@filter false
            if (query.isEmpty()) return@filter true

            val cleanCat = SharedLedgerEngine.cleanCategoryName(tx.category).lowercase()
            cleanCat.contains(query) ||
                tx.note.lowercase().contains(query) ||
                tx.tags.lowercase().contains(query) ||
                tx.date.lowercase().contains(query) ||
                formatMoney(tx.amount).lowercase().contains(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Records",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate900
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Teal50
                ) {
                    Text(
                        text = "${filteredTransactions.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryTeal,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            Surface(
                onClick = onNavigateToAdd,
                shape = CircleShape,
                color = PrimaryTeal,
                modifier = Modifier.size(36.dp)
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

        SharedSegmentedTabs(
            options = RecordFilterOptions,
            selectedIndex = selectedFilterIndex,
            onSelect = { selectedFilterIndex = it }
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Search category, note, or amount",
                    fontSize = 14.sp,
                    color = Slate400
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Slate400,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = Slate500,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = PrimaryTeal,
                unfocusedBorderColor = Slate200,
                focusedTextColor = Slate900,
                unfocusedTextColor = Slate900,
                cursorColor = PrimaryTeal
            )
        )

        if (filteredTransactions.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate200)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Teal50,
                        modifier = Modifier.size(52.dp)
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
                        text = "No matching records",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "Try a different keyword or filter."
                        } else {
                            "No transactions recorded for $selectedMonth."
                        },
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    if (searchQuery.isBlank() && selectedFilterIndex == 0) {
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
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredTransactions,
                    key = { it.id }
                ) { tx ->
                    RecordTransactionCard(
                        tx = tx,
                        onRequestDelete = { pendingDeleteTx = tx }
                    )
                }
            }
        }
    }

    val txToDelete = pendingDeleteTx
    if (txToDelete != null) {
        val cleanCat = SharedLedgerEngine.cleanCategoryName(txToDelete.category)
        AlertDialog(
            onDismissRequest = { pendingDeleteTx = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Delete Transaction",
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = "Delete $cleanCat (RM ${formatMoney(txToDelete.amount)}) on ${txToDelete.date}? Account balances will be updated accordingly.",
                    color = Slate600,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetId = txToDelete.id
                        pendingDeleteTx = null
                        scope.launch {
                            val deleted = SharedLedgerEngine.deleteTransaction(targetId)
                            if (deleted) {
                                showPlatformToast("Transaction deleted")
                                onDataChanged()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExpenseRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTx = null }) {
                    Text("Cancel", color = Slate600)
                }
            }
        )
    }
}

@Composable
private fun RecordTransactionCard(
    tx: LocalTransaction,
    onRequestDelete: () -> Unit
) {
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
    val dateLabel = if (tx.time.length >= 5) "${tx.date} ${tx.time.take(5)}" else tx.date
    val subtitle = if (tx.note.isNotBlank()) "${tx.note.trim()} · $dateLabel" else dateLabel

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
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

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${amountPrefix}RM ${formatMoney(tx.amount)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                maxLines = 1
            )

            IconButton(onClick = onRequestDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete transaction",
                    tint = Slate400,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
