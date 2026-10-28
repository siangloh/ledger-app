package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.LocalLedgerRepository
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var itemToDelete by remember { mutableStateOf<Long?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun loadItems() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val localItems = LocalLedgerRepository.getRecycleBinItems(context)
            items = localItems
            totalCount = localItems.size
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadItems()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.tools_recycle_bin),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        if (totalCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = Slate100
                            ) {
                                Text(
                                    text = "$totalCount",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate600,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Slate800
                        )
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(
                            onClick = { showEmptyConfirm = true },
                            enabled = !isOperating,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.recycle_bin_empty_btn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Rose500
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Slate50
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = PrimaryTeal,
                            strokeWidth = 3.dp
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = errorMessage ?: stringResource(R.string.recycle_bin_load_error),
                            fontSize = 13.sp,
                            color = ExpenseRed,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = { loadItems() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(stringResource(R.string.common_retry), color = Color.White)
                        }
                    }
                }
                items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Slate100),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = Slate400,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.recycle_bin_empty_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.recycle_bin_empty_desc),
                            fontSize = 12.sp,
                            color = Slate500,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items) { item ->
                            val id = item.optLong("id")
                            val date = item.optString("date")
                            val type = item.optString("type")
                            val cat = item.optString("category")
                            val note = item.optString("note")
                            val amount = item.optDouble("amount")
                            val accName = item.optString("account_name", stringResource(R.string.recycle_bin_default_account))
                            val isExpense = type == "expense"

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Slate200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = note.ifBlank { cat.ifBlank { stringResource(R.string.recycle_bin_deleted_record) } },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Slate800,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "$date · $cat · $accName",
                                                fontSize = 11.sp,
                                                color = Slate500
                                            )
                                        }
                                        Text(
                                            text = String.format(Locale.getDefault(), if (isExpense) "-RM %.2f" else "+RM %.2f", amount),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isExpense) ExpenseRed else IncomeGreen
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 彻底删除按钮
                                        OutlinedButton(
                                            onClick = { itemToDelete = id },
                                            enabled = !isOperating,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Rose500.copy(alpha = 0.4f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose500),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.recycle_bin_delete_btn),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // 恢复按钮
                                        Button(
                                            onClick = {
                                                isOperating = true
                                                scope.launch {
                                                    val res = LocalLedgerRepository.restoreRecycleBinItem(context, id)
                                                    isOperating = false
                                                    res.onSuccess {
                                                        Toast.makeText(context, context.getString(R.string.recycle_bin_restore_success), Toast.LENGTH_SHORT).show()
                                                        loadItems()
                                                    }.onFailure {
                                                        Toast.makeText(context, it.message ?: context.getString(R.string.recycle_bin_restore_failed), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            enabled = !isOperating,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal, contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.recycle_bin_restore_btn),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
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
    }

    // 二次确认：单条彻底删除
    itemToDelete?.let { txId ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.recycle_bin_delete_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.recycle_bin_confirm_delete),
                    fontSize = 13.sp,
                    color = Slate700
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = txId
                        itemToDelete = null
                        isOperating = true
                        scope.launch {
                            val res = LocalLedgerRepository.permanentDeleteRecycleBinItem(context, toDel)
                            isOperating = false
                            res.onSuccess {
                                Toast.makeText(context, context.getString(R.string.recycle_bin_delete_success), Toast.LENGTH_SHORT).show()
                                loadItems()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: context.getString(R.string.recycle_bin_delete_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { itemToDelete = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }

    // 二次确认：清空回收站
    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.recycle_bin_empty_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.recycle_bin_confirm_empty),
                    fontSize = 13.sp,
                    color = Slate700
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmptyConfirm = false
                        isOperating = true
                        scope.launch {
                            val res = LocalLedgerRepository.emptyRecycleBin(context)
                            isOperating = false
                            res.onSuccess {
                                Toast.makeText(context, context.getString(R.string.recycle_bin_empty_success), Toast.LENGTH_SHORT).show()
                                loadItems()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: context.getString(R.string.recycle_bin_empty_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEmptyConfirm = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}
