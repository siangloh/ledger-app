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
import com.siangloh.ledger.data.BudgetItemRecord
import com.siangloh.ledger.data.BudgetOverviewRecord
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetManagementPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<BudgetOverviewRecord?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showSetOverallDialog by remember { mutableStateOf(false) }
    var editingCategoryBudget by remember { mutableStateOf<BudgetItemRecord?>(null) }
    var showAddCategoryBudgetDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    fun loadBudget() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val res = NativeLedgerApi.getBudgetOverview(context)
            isLoading = false
            res.onSuccess {
                overview = it
            }.onFailure {
                errorMessage = it.message ?: context.getString(R.string.budget_load_error)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadBudget()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.budget_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
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
                    IconButton(onClick = { showAddCategoryBudgetDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.budget_add_category_btn),
                            tint = PrimaryTeal
                        )
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
            if (isLoading) {
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
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = errorMessage ?: "", color = ExpenseRed, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { loadBudget() },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.common_retry), color = Color.White)
                    }
                }
            } else {
                val data = overview
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 月度总预算专区
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f, fill = false),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.budget_overall_title),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate900,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (data != null && data.overall.isSet) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val badgeBg = when (data.overall.level) {
                                                "over" -> Color(0xFFFEE2E2)
                                                "warn", "reached" -> Color(0xFFFEF3C7)
                                                else -> PrimaryTealSubtle
                                            }
                                            val badgeColor = when (data.overall.level) {
                                                "over" -> Color(0xFFDC2626)
                                                "warn", "reached" -> Color(0xFFD97706)
                                                else -> PrimaryTeal
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(badgeBg, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${data.overall.pct}%",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    TextButton(
                                        onClick = { showSetOverallDialog = true },
                                        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Text(
                                            text = if (data != null && data.overall.isExplicit) stringResource(R.string.budget_edit_overall_btn)
                                                   else stringResource(R.string.budget_set_overall_btn),
                                            color = PrimaryTeal,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                }

                                if (data != null && data.overall.isSet) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.budget_spent_format,
                                                String.format(Locale.getDefault(), "RM %.2f", data.overall.spent),
                                                String.format(Locale.getDefault(), "RM %.2f", data.overall.limit)
                                            ),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Slate600
                                        )
                                        if (data.overall.remaining < 0) {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_overspent_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", -data.overall.remaining)
                                                ),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_remaining_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", data.overall.remaining)
                                                ),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PrimaryTeal
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    val progressPct = ((data.overall.pct) / 100.0).toFloat().coerceIn(0f, 1f)
                                    val barColor = when (data.overall.level) {
                                        "over" -> Color(0xFFEF4444)
                                        "warn", "reached" -> Color(0xFFF59E0B)
                                        else -> PrimaryTeal
                                    }
                                    LinearProgressIndicator(
                                        progress = { progressPct },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = barColor,
                                        trackColor = Slate200
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(PrimaryTealSubtle, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    R.string.budget_daily_allowance_format,
                                                    String.format(Locale.getDefault(), "RM %.2f", data.overall.dailyAllowance)
                                                ),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PrimaryTeal
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Text(
                                            text = stringResource(
                                                R.string.budget_cycle_days_format,
                                                data.billingCycle.remainingDays
                                            ),
                                            fontSize = 11.sp,
                                            color = Slate400
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.budget_no_budget) + stringResource(R.string.budget_no_budget_desc),
                                        fontSize = 12.sp,
                                        color = Slate400
                                    )
                                }
                            }
                        }
                    }

                    // 2. 分类预算列表
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.budget_categories_title_fmt, data?.categories?.size ?: 0),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            TextButton(
                                onClick = { showAddCategoryBudgetDialog = true },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.budget_add_category_btn), color = PrimaryTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    val catList = data?.categories ?: emptyList()
                    if (catList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.budget_no_categories_hint),
                                    fontSize = 12.sp,
                                    color = Slate400,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(catList) { cat ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingCategoryBudget = cat },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, Slate200)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        try {
                                                            Color(android.graphics.Color.parseColor(cat.color))
                                                        } catch (_: Exception) {
                                                            PrimaryTeal
                                                        },
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = CategoryUiHelper.getLocalizedCategoryName(context, cat.category),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Slate800
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${cat.pct}%",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (cat.level) {
                                                    "over" -> Color(0xFFDC2626)
                                                    "warn", "reached" -> Color(0xFFD97706)
                                                    else -> PrimaryTeal
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { editingCategoryBudget = cat },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = stringResource(R.string.common_edit),
                                                    tint = Slate400,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.budget_spent_limit_fmt, String.format(Locale.getDefault(), "%.0f", cat.spent), String.format(Locale.getDefault(), "%.0f", cat.limit)),
                                            fontSize = 11.sp,
                                            color = Slate500
                                        )
                                        if (cat.remaining < 0) {
                                            Text(
                                                text = stringResource(R.string.budget_overspent_format, String.format(Locale.getDefault(), "%.0f", -cat.remaining)),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.budget_remaining_format, String.format(Locale.getDefault(), "%.0f", cat.remaining)),
                                                fontSize = 11.sp,
                                                color = Slate500
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    val catPct = (cat.pct / 100.0).toFloat().coerceIn(0f, 1f)
                                    val cBarColor = when (cat.level) {
                                        "over" -> Color(0xFFEF4444)
                                        "warn", "reached" -> Color(0xFFF59E0B)
                                        else -> PrimaryTeal
                                    }
                                    LinearProgressIndicator(
                                        progress = { catPct },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = cBarColor,
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
        }
    }

    // Sub-dialog 1: 设置月度总预算
    if (showSetOverallDialog) {
        var limitInput by remember {
            mutableStateOf(
                if (overview != null && overview!!.overall.isExplicit && overview!!.overall.limit > 0)
                    String.format(Locale.getDefault(), "%.2f", overview!!.overall.limit)
                else ""
            )
        }
        AlertDialog(
            onDismissRequest = { showSetOverallDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.budget_set_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.budget_overall_dialog_tip),
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it },
                        label = { Text(stringResource(R.string.budget_overall_limit_label), color = Slate600) },
                        placeholder = { Text(stringResource(R.string.budget_amount_hint), color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = limitInput.toDoubleOrNull() ?: 0.0
                        isSubmitting = true
                        scope.launch {
                            val res = NativeLedgerApi.saveOverallBudget(context, num)
                            isSubmitting = false
                            res.onSuccess {
                                Toast.makeText(context, context.getString(R.string.budget_overall_set_success), Toast.LENGTH_SHORT).show()
                                showSetOverallDialog = false
                                loadBudget()
                            }.onFailure {
                                Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_save_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSetOverallDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }

    // Sub-dialog 2: 编辑/删除已有分类预算
    editingCategoryBudget?.let { cat ->
        var catLimitInput by remember {
            mutableStateOf(String.format(Locale.getDefault(), "%.2f", cat.limit))
        }
        AlertDialog(
            onDismissRequest = { editingCategoryBudget = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.budget_set_cat_dialog_title, cat.category),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.budget_cat_dialog_tip_fmt, CategoryUiHelper.getLocalizedCategoryName(context, cat.category)),
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = catLimitInput,
                        onValueChange = { catLimitInput = it },
                        label = { Text(stringResource(R.string.budget_limit_myr_label), color = Slate600) },
                        placeholder = { Text(stringResource(R.string.budget_amount_hint), color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.deleteCategoryBudget(context, cat.category)
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.budget_cat_cleared_toast), Toast.LENGTH_SHORT).show()
                                    editingCategoryBudget = null
                                    loadBudget()
                                }.onFailure {
                                    Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.budget_delete_btn), color = Color(0xFFDC2626))
                    }

                    Button(
                        onClick = {
                            val num = catLimitInput.toDoubleOrNull() ?: 0.0
                            isSubmitting = true
                            scope.launch {
                                val res = NativeLedgerApi.saveCategoryBudget(context, cat.category, num)
                                isSubmitting = false
                                res.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.budget_cat_saved_toast), Toast.LENGTH_SHORT).show()
                                    editingCategoryBudget = null
                                    loadBudget()
                                }.onFailure {
                                    Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.budget_save_btn), color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingCategoryBudget = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }

    // Sub-dialog 3: 新增分类预算
    if (showAddCategoryBudgetDialog) {
        var newCatName by remember { mutableStateOf("") }
        var newCatLimit by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategoryBudgetDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.budget_add_category_btn),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.budget_cat_dialog_hint),
                        fontSize = 13.sp,
                        color = Slate500
                    )
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text(stringResource(R.string.budget_cat_name_label), color = Slate600) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                    OutlinedTextField(
                        value = newCatLimit,
                        onValueChange = { newCatLimit = it },
                        label = { Text(stringResource(R.string.budget_monthly_limit_label), color = Slate600) },
                        placeholder = { Text(stringResource(R.string.budget_monthly_limit_placeholder), color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Slate900),
                        colors = ledgerInputColors()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newCatName.trim()
                        val num = newCatLimit.toDoubleOrNull() ?: 0.0
                        if (name.isEmpty() || num <= 0.0) {
                            Toast.makeText(context, context.getString(R.string.budget_cat_input_invalid), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSubmitting = true
                        scope.launch {
                            val res = NativeLedgerApi.saveCategoryBudget(context, name, num)
                            isSubmitting = false
                            res.onSuccess {
                                Toast.makeText(context, context.getString(R.string.budget_cat_set_success_fmt, CategoryUiHelper.getLocalizedCategoryName(context, name)), Toast.LENGTH_SHORT).show()
                                showAddCategoryBudgetDialog = false
                                loadBudget()
                            }.onFailure {
                                Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_save_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddCategoryBudgetDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.budget_cancel_btn), color = Slate600)
                }
            }
        )
    }
}
