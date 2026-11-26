package com.siangloh.ledger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.todayDateString
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.platform.LocaleStateHolder
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.ui.components.SharedCategoryBadge
import com.siangloh.ledger.ui.components.SharedSegmentedTabs
import com.siangloh.ledger.ui.i18n.SharedStrings
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
import com.siangloh.ledger.ui.theme.Slate800
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private val TypeKeys = listOf("expense", "income", "savings", "transfer")
private val QuickAddAmounts = listOf(10, 50, 100)

@Composable
fun SharedAddTab(
    dataRevision: Int,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val currentLang by LocaleStateHolder.language.collectAsState()
    val s = remember(currentLang) { SharedStrings.forLanguage(currentLang) }
    val typeLabels = remember(s) { listOf(s.expense, s.income, s.savings, s.transfer) }

    var selectedTypeIndex by remember { mutableIntStateOf(0) }
    val txType = TypeKeys[selectedTypeIndex]

    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(todayDateString()) }
    var noteText by remember { mutableStateOf("") }

    var accounts by remember { mutableStateOf<List<LocalAccount>>(emptyList()) }
    var categories by remember { mutableStateOf<List<LocalCategory>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<Int?>(null) }
    var selectedToAccountId by remember { mutableStateOf<Int?>(null) }
    var isAmountFocused by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(dataRevision) {
        try {
            SharedLedgerEngine.initDefaultsIfNeeded()
            val loadedAccounts = AppDatabase.getInstance().localAccountDao().getAll()
            accounts = loadedAccounts
            if (selectedAccountId == null || loadedAccounts.none { it.id == selectedAccountId }) {
                selectedAccountId = loadedAccounts.firstOrNull()?.id
            }
            if (selectedToAccountId == null || loadedAccounts.none { it.id == selectedToAccountId }) {
                selectedToAccountId = loadedAccounts.getOrNull(1)?.id ?: loadedAccounts.firstOrNull()?.id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            println("SharedAddTab accounts load error: ${err.message}")
        }
    }

    LaunchedEffect(txType, dataRevision) {
        try {
            if (txType == "transfer") {
                categories = emptyList()
                selectedCategory = "内部转账"
            } else {
                SharedLedgerEngine.initDefaultsIfNeeded()
                val loaded = AppDatabase.getInstance().localCategoryDao().getByType(txType)
                categories = loaded
                if (loaded.none { it.name == selectedCategory }) {
                    selectedCategory = loaded.firstOrNull()?.name ?: ""
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            println("SharedAddTab categories load error: ${err.message}")
        }
    }

    val accentColor by animateColorAsState(
        targetValue = when (txType) {
            "expense" -> ExpenseRed
            "income" -> IncomeGreen
            "savings" -> PrimaryTeal
            else -> Slate800
        },
        animationSpec = tween(220),
        label = "addAccentColor"
    )
    val accentSoftBg by animateColorAsState(
        targetValue = when (txType) {
            "expense" -> Rose50
            "income" -> Emerald50
            "savings" -> Teal50
            else -> Slate100
        },
        animationSpec = tween(220),
        label = "addAccentSoftBg"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("add_root")
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Text(
            text = s.newTransactionTitle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // 4-way spring-sliding type selector
        SharedSegmentedTabs(
            options = typeLabels,
            selectedIndex = selectedTypeIndex,
            onSelect = { selectedTypeIndex = it },
            activePillColor = accentColor,
            activeTextColor = Color.White,
            itemTestTagPrefix = "add_type"
        )

        // Amount input card + quick-add chips
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = s.amountLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate500
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RM",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (amountText.isEmpty()) {
                            Text(
                                text = "0.00",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate400,
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                        }
                        BasicTextField(
                            value = amountText,
                            onValueChange = { raw ->
                                val sanitized = sanitizeDecimalInput(raw)
                                if (sanitized != null) {
                                    amountText = sanitized
                                }
                            },
                            singleLine = true,
                            cursorBrush = SolidColor(accentColor),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            textStyle = TextStyle(
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900,
                                fontFeatureSettings = "tnum"
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isAmountFocused = it.isFocused }
                        )
                    }
                    if (amountText.isNotEmpty()) {
                        IconButton(
                            onClick = { amountText = "" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear amount",
                                tint = Slate400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Quick-add chips + keyboard-dismiss Done chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickAddAmounts.forEach { increment ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = accentSoftBg,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("add_quick_$increment")
                                .clickable {
                                    val current = amountText.toDoubleOrNull() ?: 0.0
                                    val updated = current + increment
                                    amountText = formatMoney(updated, decimals = 2, grouping = false)
                                }
                        ) {
                            Text(
                                text = "+$increment",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                    if (isAmountFocused) {
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Slate100,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { focusManager.clearFocus() }
                        ) {
                            Text(
                                text = s.done,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate700,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }

        // Category grid (hidden for internal transfers)
        if (txType != "transfer") {
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
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = s.categoryLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate500
                    )

                    val rows = categories.chunked(4)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { cat ->
                                    val isSelected = cat.name == selectedCategory
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (isSelected) accentSoftBg else Slate50)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) accentColor else Slate200,
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .clickable { selectedCategory = cat.name }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SharedCategoryBadge(
                                            category = cat.name,
                                            type = txType,
                                            size = 38.dp
                                        )
                                        Text(
                                            text = SharedStrings.localizeCategory(cat.name, currentLang),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) accentColor else Slate700,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                repeat(4 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Slate100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = Slate700,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = s.internalTransferTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        )
                        Text(
                            text = s.internalTransferDesc,
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }
            }
        }

        // Account selector card
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
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val primaryAccountLabel = when (txType) {
                    "transfer", "savings" -> s.fromAccountLabel
                    "income" -> s.depositToLabel
                    else -> s.paidWithLabel
                }
                Text(
                    text = primaryAccountLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate500,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                AccountChipRow(
                    accounts = accounts,
                    selectedId = selectedAccountId,
                    lang = currentLang,
                    accentColor = accentColor,
                    accentSoftBg = accentSoftBg,
                    onSelect = { selectedAccountId = it }
                )

                if (txType == "transfer" || txType == "savings") {
                    Text(
                        text = if (txType == "transfer") s.toAccountLabel else s.destinationAccountOptional,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate500,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    AccountChipRow(
                        accounts = accounts,
                        selectedId = selectedToAccountId,
                        lang = currentLang,
                        accentColor = accentColor,
                        accentSoftBg = accentSoftBg,
                        onSelect = { id ->
                            selectedToAccountId = if (txType == "savings" && selectedToAccountId == id) null else id
                        }
                    )
                }
            }
        }

        // Date & Note card
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { if (it.length <= 10) dateText = it },
                        label = { Text(s.dateLabel) },
                        placeholder = { Text("YYYY-MM-DD") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CalendarToday,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Slate200,
                            focusedLabelColor = accentColor
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Slate100,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { dateText = todayDateString() }
                    ) {
                        Text(
                            text = s.todayLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate700,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(s.noteLabel) },
                    placeholder = { Text(s.notePlaceholder) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = Slate500,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Slate200,
                        focusedLabelColor = accentColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Save button
        Button(
            onClick = {
                focusManager.clearFocus()
                val parsedAmount = amountText.toDoubleOrNull()
                if (parsedAmount == null || parsedAmount <= 0.0) {
                    showPlatformToast(s.enterAmountGtZero)
                    return@Button
                }
                if (txType == "transfer") {
                    if (selectedAccountId == null || selectedToAccountId == null) {
                        showPlatformToast(s.selectBothAccounts)
                        return@Button
                    }
                    if (selectedAccountId == selectedToAccountId) {
                        showPlatformToast(s.accountsMustDiffer)
                        return@Button
                    }
                }
                val cleanDate = dateText.trim().ifBlank { todayDateString() }
                if (runCatching { LocalDate.parse(cleanDate) }.isFailure) {
                    showPlatformToast(s.invalidDateFormat)
                    return@Button
                }
                val effectiveCategory = if (txType == "transfer") {
                    "内部转账"
                } else {
                    selectedCategory.ifBlank { categories.firstOrNull()?.name ?: "其他" }
                }

                isSaving = true
                scope.launch {
                    try {
                        SharedLedgerEngine.recordTransaction(
                            date = cleanDate,
                            type = txType,
                            category = effectiveCategory,
                            amount = parsedAmount,
                            note = noteText.trim(),
                            accountId = selectedAccountId,
                            toAccountId = if (txType == "transfer" || txType == "savings") selectedToAccountId else null,
                            source = "manual"
                        )
                        amountText = ""
                        noteText = ""
                        showPlatformToast(SharedStrings.format1(s.savedAmountFmt, formatMoney(parsedAmount)))
                        onSaved()
                    } catch (e: Exception) {
                        println("SharedAddTab save error: ${e.message}")
                        showPlatformToast(s.saveFailed)
                    } finally {
                        isSaving = false
                    }
                }
            },
            enabled = !isSaving,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = Color.White,
                disabledContainerColor = Slate200,
                disabledContentColor = Slate500
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("add_btn_save")
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = SharedStrings.format1(s.saveTypeFmt, typeLabels[selectedTypeIndex]),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AccountChipRow(
    accounts: List<LocalAccount>,
    selectedId: Int?,
    lang: String,
    accentColor: Color,
    accentSoftBg: Color,
    onSelect: (Int) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(accounts, key = { it.id }) { acc ->
            val isSelected = acc.id == selectedId
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) accentSoftBg else Slate50,
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) accentColor else Slate200
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(acc.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = accountTypeIcon(acc.type),
                        contentDescription = null,
                        tint = if (isSelected) accentColor else Slate500,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = SharedStrings.localizeAccountName(acc.name, lang),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) accentColor else Slate700,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun accountTypeIcon(type: String): ImageVector = when (type) {
    "e_wallet" -> Icons.Rounded.AccountBalanceWallet
    "savings" -> Icons.Rounded.AccountBalance
    "credit_card" -> Icons.Rounded.CreditCard
    "cash" -> Icons.Rounded.Payments
    else -> Icons.Rounded.Savings
}

private fun sanitizeDecimalInput(raw: String): String? {
    if (raw.isEmpty()) return ""
    var dotSeen = false
    var fracCount = 0
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        when {
            ch.isDigit() -> {
                if (dotSeen) {
                    if (fracCount >= 2) return null
                    fracCount++
                }
                sb.append(ch)
            }
            ch == '.' -> {
                if (dotSeen) return null
                dotSeen = true
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
            }
            else -> return null
        }
    }
    return sb.toString()
}
