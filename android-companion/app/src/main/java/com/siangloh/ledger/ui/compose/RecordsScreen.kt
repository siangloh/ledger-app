package com.siangloh.ledger.ui.compose

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.LocalDataStore
import com.siangloh.ledger.data.NativeLedgerApi
import com.siangloh.ledger.data.TransactionRecord
import com.siangloh.ledger.data.SavedFilterRecord
import com.siangloh.ledger.data.AccountRecord
import com.siangloh.ledger.data.entities.PendingTransaction
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.compose.components.RecordsSkeletonList
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var transactions by remember {
        mutableStateOf<List<TransactionRecord>>(
            LocalDataStore.getRecords(context, selectedMonth, selectedType, searchQuery, 1)?.let {
                try {
                    NativeLedgerApi.parseTransactionsPageJson(JSONObject(it)).items
                } catch (_: Exception) { emptyList() }
            } ?: emptyList()
        )
    }
    var pendingTransactions by remember { mutableStateOf<List<PendingTransaction>>(emptyList()) }
    var isSyncingPending by remember { mutableStateOf(false) }
    var totalCount by remember { mutableStateOf(0) }
    var editingTx by remember { mutableStateOf<TransactionRecord?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<TransactionRecord?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    // 筛选预设与多维度高级筛选状态
    var savedFilters by remember { mutableStateOf<List<SavedFilterRecord>>(emptyList()) }
    var activeFilterId by remember { mutableStateOf<Int?>(null) }
    var filterCategory by remember { mutableStateOf<String?>(null) }
    var filterAccountId by remember { mutableStateOf<Int?>(null) }
    var filterMinAmount by remember { mutableStateOf<Double?>(null) }
    var filterMaxAmount by remember { mutableStateOf<Double?>(null) }
    var filterStartDate by remember { mutableStateOf<String?>(null) }
    var filterEndDate by remember { mutableStateOf<String?>(null) }
    var filterSource by remember { mutableStateOf<String?>(null) }
    var filterChannel by remember { mutableStateOf<String?>(null) }
    var showAdvancedFilterSheet by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var availableCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableAccounts by remember { mutableStateOf<List<AccountRecord>>(emptyList()) }

    fun loadFilterOptions() {
        scope.launch {
            NativeLedgerApi.getSavedFilters(context).onSuccess {
                savedFilters = it
            }
            NativeLedgerApi.getCategories(context).onSuccess { catRes ->
                val all = (catRes.expenses.map { it.name } + catRes.incomes.map { it.name } + catRes.savings.map { it.name }).distinct()
                availableCategories = all
            }
            NativeLedgerApi.getAccounts(context).onSuccess { accRes ->
                availableAccounts = accRes.accounts
            }
        }
    }

    LaunchedEffect(Unit) {
        loadFilterOptions()
    }

    // 搜索 300ms 防抖，空内容即时响应，避免每次击键都向服务器发起高频无效请求
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    fun loadPending() {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val list = withContext(Dispatchers.IO) {
                    db.pendingTransactionDao().getAllLocalTransactions()
                }
                pendingTransactions = list
            } catch (_: Exception) {}
        }
    }


    fun prefetchAdjacentMonths(monthStr: String, type: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val prevMonth = shiftMonthString(monthStr, -1)
                val nextMonth = shiftMonthString(monthStr, 1)
                for (targetMonth in listOf(prevMonth, nextMonth)) {
                    val existingCache = LocalDataStore.getRecords(context, targetMonth, type, "", 1)
                    if (existingCache.isNullOrBlank()) {
                        NativeLedgerApi.getTransactions(
                            context = context,
                            type = type,
                            month = targetMonth,
                            page = 1,
                            limit = 50
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadData(resetPage: Boolean = true, forceRefresh: Boolean = false) {
        val targetPage = if (resetPage) 1 else (currentPage + 1)
        val effMonth = if (filterStartDate.isNullOrBlank() && filterEndDate.isNullOrBlank()) selectedMonth else null
        val todayMonthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val isFutureMonth = effMonth != null && effMonth > todayMonthStr

        // 1. 未来空月份：0ms 秒开呈现空流水占位，绝不请求网络（除非用户强制刷新）
        if (isFutureMonth) {
            transactions = emptyList()
            totalCount = 0
            hasMore = false
            isLoading = false
            loadPending()
            if (!forceRefresh) return
        }

        if (resetPage) {
            val isSimpleMonthQuery = targetPage == 1 && effMonth != null && filterCategory == null && filterAccountId == null && filterMinAmount == null && filterMaxAmount == null && filterSource == null && filterChannel == null
            val cachedJson = if (isSimpleMonthQuery) {
                LocalDataStore.getRecords(context, effMonth, selectedType, debouncedSearchQuery, 1)
            } else null

            if (!cachedJson.isNullOrBlank()) {
                try {
                    val pageObj = NativeLedgerApi.parseTransactionsPageJson(JSONObject(cachedJson))
                    transactions = pageObj.items
                    totalCount = pageObj.totalCount
                    hasMore = pageObj.hasMore
                    isLoading = false
                    loadPending()
                    // 核心：若非强制刷新，只要本地有快照（或在 5 分钟新鲜期内），直接消费本地存储，严禁发起网络请求！
                    if (!forceRefresh) {
                        return
                    }
                } catch (_: Exception) {
                    transactions = emptyList()
                    isLoading = true
                }
            } else {
                transactions = emptyList()
                isLoading = true
            }
            loadPending()
        } else {
            isLoadingMore = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val res = NativeLedgerApi.getTransactions(
                context = context,
                type = selectedType,
                month = effMonth,
                category = filterCategory,
                search = debouncedSearchQuery,
                accountId = filterAccountId,
                startDate = filterStartDate,
                endDate = filterEndDate,
                minAmount = filterMinAmount,
                maxAmount = filterMaxAmount,
                source = filterSource,
                channel = filterChannel,
                page = targetPage,
                limit = 50,
                forceRefresh = forceRefresh
            )
            isLoading = false
            isLoadingMore = false
            res.onSuccess { page ->
                currentPage = targetPage
                if (resetPage) {
                    transactions = page.items
                    if (targetPage == 1 && effMonth != null && filterStartDate.isNullOrBlank() && filterEndDate.isNullOrBlank()) {
                        prefetchAdjacentMonths(effMonth, selectedType)
                    }
                } else {
                    val existingIds = transactions.map { it.id }.toSet()
                    val newItems = page.items.filter { it.id !in existingIds }
                    transactions = transactions + newItems
                }
                totalCount = page.totalCount
                hasMore = page.hasMore
            }.onFailure { err ->
                if (err !is kotlinx.coroutines.CancellationException) {
                    if (!isFutureMonth) {
                        Toast.makeText(context, "加载流水失败：${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 监听后台定期全量同步广播 (DATA_SYNCED) 与同步队列完成广播 (SYNC_COMPLETED)，实时静默刷新离线队列与流水明细
    DisposableEffect(selectedMonth, selectedType, debouncedSearchQuery) {
        val filter = android.content.IntentFilter().apply {
            addAction("com.siangloh.ledger.SYNC_COMPLETED")
            addAction("com.siangloh.ledger.DATA_SYNCED")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                loadPending()
                val effMonth = if (filterStartDate.isNullOrBlank() && filterEndDate.isNullOrBlank()) selectedMonth else null
                val cachedJson = LocalDataStore.getRecords(context, effMonth, selectedType, debouncedSearchQuery, 1)
                if (!cachedJson.isNullOrBlank()) {
                    try {
                        val pageObj = NativeLedgerApi.parseTransactionsPageJson(JSONObject(cachedJson))
                        transactions = pageObj.items
                        totalCount = pageObj.totalCount
                        hasMore = pageObj.hasMore
                        isLoading = false
                    } catch (_: Exception) {}
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun applySavedFilter(sf: SavedFilterRecord) {
        try {
            val json = JSONObject(sf.criteriaJson)
            activeFilterId = sf.id
            if (json.has("type")) {
                selectedType = json.optString("type", "all")
            }
            if (json.has("category")) {
                val cat = json.optString("category")
                filterCategory = if (cat.isNotBlank() && cat != "all" && cat != "全部") cat else null
            } else {
                filterCategory = null
            }
            if (json.has("account_id")) {
                val aid = json.optInt("account_id")
                filterAccountId = if (aid > 0) aid else null
            } else {
                filterAccountId = null
            }
            if (json.has("min_amount")) {
                val min = json.optDouble("min_amount")
                filterMinAmount = if (!min.isNaN() && min >= 0) min else null
            } else {
                filterMinAmount = null
            }
            if (json.has("max_amount")) {
                val max = json.optDouble("max_amount")
                filterMaxAmount = if (!max.isNaN() && max > 0) max else null
            } else {
                filterMaxAmount = null
            }
            if (json.has("source")) {
                val src = json.optString("source")
                filterSource = if (src.isNotBlank() && src != "all") src else null
            } else {
                filterSource = null
            }
            if (json.has("channel")) {
                val ch = json.optString("channel")
                filterChannel = if (ch.isNotBlank() && ch != "all") ch else null
            } else {
                filterChannel = null
            }
            if (json.has("q")) {
                searchQuery = json.optString("q")
            } else if (json.has("search")) {
                searchQuery = json.optString("search")
            }
            if (json.has("date_range")) {
                val dr = json.optString("date_range")
                if (dr == "this_month") {
                    selectedMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                    filterStartDate = null
                    filterEndDate = null
                } else if (dr == "last_month") {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.MONTH, -1)
                    selectedMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)
                    filterStartDate = null
                    filterEndDate = null
                }
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(selectedType, selectedMonth, debouncedSearchQuery, filterCategory, filterAccountId, filterMinAmount, filterMaxAmount, filterStartDate, filterEndDate, filterSource, filterChannel) {
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
                            .background(PrimaryTeal, CircleShape)
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
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.prev_month),
                            tint = Slate600
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Slate100,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = formatDisplayMonth(selectedMonth, context),
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
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.next_month),
                            tint = Slate600
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 搜索输入框与高级筛选按钮
                val activeFilterCount = (if (filterCategory != null) 1 else 0) +
                        (if (filterAccountId != null) 1 else 0) +
                        (if (filterMinAmount != null || filterMaxAmount != null) 1 else 0) +
                        (if (filterStartDate != null || filterEndDate != null) 1 else 0) +
                        (if (filterSource != null) 1 else 0) +
                        (if (filterChannel != null) 1 else 0)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_records_placeholder), fontSize = 13.sp, color = Slate400) },
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
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Slate50,
                            unfocusedContainerColor = Slate50
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )

                    Box {
                        IconButton(
                            onClick = { showAdvancedFilterSheet = true },
                            modifier = Modifier
                                .size(48.dp)
                                .background(if (activeFilterCount > 0) PrimaryTeal else Slate100, RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.filter_btn),
                                tint = if (activeFilterCount > 0) Color.White else Slate700,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        if (activeFilterCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = Rose500,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .size(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = activeFilterCount.toString(),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 快捷预设芯片滚动栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAllPreset = activeFilterId == null && activeFilterCount == 0 && selectedType == "all" && debouncedSearchQuery.isEmpty()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAllPreset) PrimaryTeal else Slate100,
                        modifier = Modifier
                            .height(30.dp)
                            .clickable {
                                activeFilterId = null
                                filterCategory = null
                                filterAccountId = null
                                filterMinAmount = null
                                filterMaxAmount = null
                                filterStartDate = null
                                filterEndDate = null
                                filterSource = null
                                filterChannel = null
                                selectedType = "all"
                                searchQuery = ""
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = stringResource(R.string.filter_all),
                                fontSize = 12.sp,
                                fontWeight = if (isAllPreset) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAllPreset) Color.White else Slate700
                            )
                        }
                    }

                    savedFilters.filter { it.isPinned }.forEach { sf ->
                        val isSelected = activeFilterId == sf.id
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) PrimaryTeal else Slate100,
                            modifier = Modifier
                                .height(30.dp)
                                .clickable {
                                    if (isSelected) {
                                        activeFilterId = null
                                        filterCategory = null
                                        filterAccountId = null
                                        filterMinAmount = null
                                        filterMaxAmount = null
                                        filterStartDate = null
                                        filterEndDate = null
                                        filterSource = null
                                        filterChannel = null
                                        selectedType = "all"
                                    } else {
                                        applySavedFilter(sf)
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text("📌", fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = sf.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else Slate700,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // 存为预设按钮
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Slate100,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .height(30.dp)
                            .clickable { showSavePresetDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.filter_save_as_preset),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryTeal
                            )
                        }
                    }
                }

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
                            color = if (isSelected) PrimaryTeal else Slate100,
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

                // 激活的过滤项微标签 (可一键移除)
                if (filterCategory != null || filterAccountId != null || filterMinAmount != null || filterMaxAmount != null || filterSource != null || filterChannel != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        filterSource?.let { src ->
                            val srcLabel = when (src) {
                                "notification" -> "📱 手机通知"
                                "manual" -> "✍️ 手动记账"
                                "import" -> "📥 文件导入"
                                "api" -> "⚡ API接入"
                                "nlp" -> "🤖 智能记账"
                                "recurring" -> "🔄 固定收支"
                                "split_bill" -> "👥 AA分账"
                                "reconciliation" -> "⚖️ 平账"
                                "unknown" -> "❓ 未知/历史"
                                else -> src
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PrimaryTealSubtle,
                                modifier = Modifier.clickable {
                                    filterSource = null
                                    filterChannel = null
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("来源: $srcLabel", fontSize = 11.sp, color = PrimaryTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        filterChannel?.let { ch ->
                            val chLabel = when (ch) {
                                "maybank" -> "Maybank"
                                "tng" -> "Touch 'n Go"
                                "grab" -> "Grab"
                                "public_bank" -> "Public Bank"
                                "shopee" -> "ShopeePay"
                                "bank_card" -> "银行短信"
                                "generic" -> "其他通知"
                                else -> ch
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PrimaryTealSubtle,
                                modifier = Modifier.clickable { filterChannel = null }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("渠道: $chLabel", fontSize = 11.sp, color = PrimaryTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        filterCategory?.let { cat ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PrimaryTealSubtle,
                                modifier = Modifier.clickable { filterCategory = null }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("分类: $cat", fontSize = 11.sp, color = PrimaryTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        filterAccountId?.let { aid ->
                            val accName = availableAccounts.find { it.id == aid }?.name ?: "账户#$aid"
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PrimaryTealSubtle,
                                modifier = Modifier.clickable { filterAccountId = null }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("账户: $accName", fontSize = 11.sp, color = PrimaryTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        if (filterMinAmount != null || filterMaxAmount != null) {
                            val amtLabel = "金额: " + (filterMinAmount?.let { "≥$it" } ?: "") +
                                    (if (filterMinAmount != null && filterMaxAmount != null) " ~ " else "") +
                                    (filterMaxAmount?.let { "≤$it" } ?: "")
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PrimaryTealSubtle,
                                modifier = Modifier.clickable {
                                    filterMinAmount = null
                                    filterMaxAmount = null
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(amtLabel, fontSize = 11.sp, color = PrimaryTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Close, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(12.dp))
                                }
                            }
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
            if (isLoading && transactions.isEmpty() && pendingTransactions.isEmpty()) {
                RecordsSkeletonList()
            } else if (transactions.isEmpty() && pendingTransactions.isEmpty()) {
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
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
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
                    if (pendingTransactions.isNotEmpty()) {
                        item(key = "pending_header") {
                            val failedCount = pendingTransactions.count { it.sync_status == "failed" }
                            val pendingCount = pendingTransactions.count { it.sync_status == "pending" }
                            val localCount = pendingTransactions.count { it.sync_status == "local_only" }
                            val hasFailures = failedCount > 0

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (hasFailures) Rose50 else Amber50,
                                border = BorderStroke(1.dp, if (hasFailures) Rose200 else Amber200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(text = if (hasFailures) "⚠️" else "💾", fontSize = 18.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = if (hasFailures) {
                                                        "同步异常 ($failedCount 笔失败 / $pendingCount 笔待同步)"
                                                    } else {
                                                        "离线存储队列 ($pendingCount 笔待同步${if (localCount > 0) " · $localCount 笔仅本机" else ""})"
                                                    },
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (hasFailures) Rose600 else Amber700
                                                )
                                                Text(
                                                    text = if (hasFailures) "检测到网络或校验错误，支持手动安全重试" else "账目已持久保存在本机，连网后将安全同步",
                                                    fontSize = 11.sp,
                                                    color = Slate500
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                isSyncingPending = true
                                                scope.launch {
                                                    val db = AppDatabase.getInstance(context)
                                                    withContext(Dispatchers.IO) {
                                                        db.pendingTransactionDao().resetAllFailedToPending()
                                                    }
                                                    val syncable = withContext(Dispatchers.IO) {
                                                        db.pendingTransactionDao().getSyncableTransactions()
                                                    }
                                                    NetworkHelper.syncPendingTransactionsDetailed(syncable, context) { success, syncedIds, failedErrors ->
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                if (syncedIds.isNotEmpty()) {
                                                                    db.pendingTransactionDao().markAsSynced(syncedIds)
                                                                    db.pendingTransactionDao().deleteSynced()
                                                                }
                                                                failedErrors.forEach { (fid, ferr) ->
                                                                    db.pendingTransactionDao().markAsFailed(fid, ferr)
                                                                }
                                                            }
                                                            isSyncingPending = false
                                                            loadPending()
                                                            loadData(resetPage = true)
                                                        }
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (hasFailures) Rose500 else Amber600),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(48.dp),
                                            enabled = !isSyncingPending
                                        ) {
                                            if (isSyncingPending) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                            } else {
                                                Text(
                                                    text = if (hasFailures) stringResource(R.string.sync_retry_btn) else "立即同步",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    pendingTransactions.forEach { pt ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.White,
                                            border = BorderStroke(1.dp, if (pt.sync_status == "failed") Rose200 else Slate200),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            val statusBadgeBg = when (pt.sync_status) {
                                                                "failed" -> Rose100
                                                                "local_only" -> Slate100
                                                                else -> Amber100
                                                            }
                                                            val statusBadgeFg = when (pt.sync_status) {
                                                                "failed" -> Rose600
                                                                "local_only" -> Slate600
                                                                else -> Amber700
                                                            }
                                                            val statusBadgeText = when (pt.sync_status) {
                                                                "failed" -> stringResource(R.string.sync_status_failed)
                                                                "local_only" -> stringResource(R.string.sync_status_local_only)
                                                                else -> stringResource(R.string.sync_status_pending)
                                                            }
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = statusBadgeBg,
                                                                modifier = Modifier.padding(end = 6.dp)
                                                            ) {
                                                                Text(
                                                                    text = statusBadgeText,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = statusBadgeFg,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = "${pt.category} · ${pt.note.ifBlank { "离线记账" }}",
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = Slate800,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "${pt.date} · 本地队列",
                                                            fontSize = 11.sp,
                                                            color = Slate500
                                                        )
                                                    }
                                                    Text(
                                                        text = "${if (pt.type == "expense") "-" else "+"}RM ${String.format(Locale.US, "%.2f", pt.amount)}",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (pt.type == "expense") Slate900 else Emerald600
                                                    )
                                                }

                                                if (pt.sync_status == "failed") {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.sync_failed_reason, pt.sync_error ?: "网络通信异常"),
                                                            fontSize = 11.sp,
                                                            color = Rose600,
                                                            modifier = Modifier.weight(1f),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        OutlinedButton(
                                                            onClick = {
                                                                scope.launch {
                                                                    val db = AppDatabase.getInstance(context)
                                                                    withContext(Dispatchers.IO) {
                                                                        db.pendingTransactionDao().resetToPending(pt.id)
                                                                    }
                                                                    NetworkHelper.syncPendingTransactionsDetailed(listOf(pt), context) { success, syncedIds, failedErrors ->
                                                                        scope.launch {
                                                                            withContext(Dispatchers.IO) {
                                                                                if (syncedIds.isNotEmpty()) {
                                                                                    db.pendingTransactionDao().markAsSynced(syncedIds)
                                                                                    db.pendingTransactionDao().deleteSynced()
                                                                                }
                                                                                failedErrors.forEach { (fid, ferr) ->
                                                                                    db.pendingTransactionDao().markAsFailed(fid, ferr)
                                                                                }
                                                                            }
                                                                            loadPending()
                                                                            loadData(resetPage = true)
                                                                        }
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.height(48.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                                        ) {
                                                            Text(stringResource(R.string.common_retry), fontSize = 11.sp, color = Rose600)
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
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryTeal)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            loadData(resetPage = false)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("加载更多流水 (已展示 ${transactions.size} / $totalCount 条)", fontSize = 13.sp, color = PrimaryTeal)
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
            title = { Text(stringResource(R.string.delete_tx_title), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Slate900) },
            text = { Text(stringResource(R.string.delete_tx_confirm_msg, String.format(Locale.US, "%.2f", tx.amount), tx.note.ifBlank { "-" }), fontSize = 13.sp, color = Slate600) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = NativeLedgerApi.deleteTransaction(context, tx.id)
                            showDeleteConfirm = null
                            editingTx = null
                            res.onSuccess {
                                Toast.makeText(context, context.getString(R.string.common_success), Toast.LENGTH_SHORT).show()
                                loadData()
                            }.onFailure {
                                Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500)
                ) {
                    Text(stringResource(R.string.delete_tx_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }

    // 高级筛选底部抽屉
    if (showAdvancedFilterSheet) {
        AdvancedFilterBottomSheet(
            currentType = selectedType,
            currentCategory = filterCategory,
            currentAccountId = filterAccountId,
            currentMinAmount = filterMinAmount,
            currentMaxAmount = filterMaxAmount,
            currentSource = filterSource,
            currentChannel = filterChannel,
            availableCategories = availableCategories,
            availableAccounts = availableAccounts,
            onDismiss = { showAdvancedFilterSheet = false },
            onSaveAsPresetClick = {
                showAdvancedFilterSheet = false
                showSavePresetDialog = true
            },
            onApply = { newType, newCat, newAid, newMin, newMax, newSrc, newChan ->
                selectedType = newType
                filterCategory = newCat
                filterAccountId = newAid
                filterMinAmount = newMin
                filterMaxAmount = newMax
                filterSource = newSrc
                filterChannel = newChan
                activeFilterId = null
                showAdvancedFilterSheet = false
            }
        )
    }

    // 保存为预设弹窗
    if (showSavePresetDialog) {
        SavePresetDialog(
            onDismiss = { showSavePresetDialog = false },
            onConfirm = { name, isPinned ->
                val criteria = JSONObject().apply {
                    put("type", selectedType)
                    filterCategory?.let { put("category", it) }
                    filterAccountId?.let { put("account_id", it) }
                    filterMinAmount?.let { put("min_amount", it) }
                    filterMaxAmount?.let { put("max_amount", it) }
                    filterSource?.let { put("source", it) }
                    filterChannel?.let { put("channel", it) }
                    if (searchQuery.isNotBlank()) put("q", searchQuery.trim())
                    put("date_range", "this_month")
                }
                scope.launch {
                    val res = NativeLedgerApi.createSavedFilter(context, name, criteria, isPinned)
                    showSavePresetDialog = false
                    res.onSuccess { created ->
                        Toast.makeText(context, context.getString(R.string.filter_save_preset_success), Toast.LENGTH_SHORT).show()
                        loadFilterOptions()
                        activeFilterId = created.id
                    }.onFailure { err ->
                        Toast.makeText(context, "${context.getString(R.string.common_failed)}: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
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
                        // 来源微型胶囊 (短标签、微型图标，低调优雅，不挤占金额与商户)
                        val badgeDisplay = if (tx.badgeText.isNotBlank()) tx.badgeText else tx.sourceLabel
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                if (tx.badgeEmoji.isNotBlank()) {
                                    Text(
                                        text = tx.badgeEmoji,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(end = 2.dp)
                                    )
                                }
                                Text(
                                    text = badgeDisplay,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Slate600
                                )
                                if (tx.isEdited) {
                                    Text(
                                        text = " ✎",
                                        fontSize = 9.sp,
                                        color = Slate400
                                    )
                                }
                            }
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
                        // 服务器已确认同步徽标
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Emerald50
                        ) {
                            Text(
                                text = "· " + stringResource(R.string.sync_status_synced),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Emerald700,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (tx.tags.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tx.tags,
                            fontSize = 10.sp,
                            color = PrimaryTeal,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.edit_tx_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.delete_tx_btn), tint = Rose500)
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
                    focusedBorderColor = PrimaryTeal,
                    focusedLabelColor = PrimaryTeal
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
                        focusedBorderColor = PrimaryTeal,
                        focusedLabelColor = PrimaryTeal
                    )
                )
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = { dateStr = it },
                    label = { Text("日期 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryTeal,
                        focusedLabelColor = PrimaryTeal
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
                    focusedBorderColor = PrimaryTeal,
                    focusedLabelColor = PrimaryTeal
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
                    focusedBorderColor = PrimaryTeal,
                    focusedLabelColor = PrimaryTeal
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 记录来源与溯源元数据区块 (展示来源、渠道、批次及独立性提示)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.source_provenance_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                        val badgeDisplay = if (tx.badgeText.isNotBlank()) tx.badgeText else tx.sourceLabel
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Slate200
                        ) {
                            Text(
                                text = "${tx.badgeEmoji} $badgeDisplay".trim(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate700,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 通知渠道与资金账户独立性说明
                    if (!tx.channel.isNullOrBlank() || tx.source == "notification") {
                        val chDisplay = tx.channelLabel ?: tx.channel ?: "App通知"
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.source_channel_label) + "：",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate500
                            )
                            Text(
                                text = chDisplay,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate800
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Amber50,
                            border = BorderStroke(1.dp, Amber200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.source_channel_hint, tx.accountName.ifBlank { "未指定" }),
                                fontSize = 10.sp,
                                color = Amber700,
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (!tx.importBatchId.isNullOrBlank()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.source_batch_label) + "：",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate500
                            )
                            Text(
                                text = tx.importBatchId,
                                fontSize = 11.sp,
                                color = Slate700,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (tx.isEdited || !tx.updatedAt.isNullOrBlank()) {
                        val editTime = tx.updatedAt ?: "-"
                        val editSrc = tx.lastEditSource ?: "已编辑"
                        Text(
                            text = stringResource(R.string.source_edited_desc, editTime, editSrc),
                            fontSize = 10.sp,
                            color = Slate500,
                            lineHeight = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (tx.hasMergedEvidence) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Slate100,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.source_merged_badge),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = PrimaryTeal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

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
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.edit_save), fontSize = 15.sp, fontWeight = FontWeight.Bold)
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

fun formatDisplayMonth(monthStr: String, context: Context? = null): String {
    return try {
        val parts = monthStr.split("-")
        val y = parts[0].toInt()
        val m = parts[1].toInt()
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val lang = if (context != null) com.siangloh.ledger.util.AppLanguageManager.getLanguage(context) else "zh"
        com.siangloh.ledger.util.AppLanguageManager.formatMonth(cal, lang)
    } catch (_: Exception) {
        monthStr
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedFilterBottomSheet(
    currentType: String,
    currentCategory: String?,
    currentAccountId: Int?,
    currentMinAmount: Double?,
    currentMaxAmount: Double?,
    currentSource: String? = null,
    currentChannel: String? = null,
    availableCategories: List<String>,
    availableAccounts: List<AccountRecord>,
    onDismiss: () -> Unit,
    onSaveAsPresetClick: () -> Unit,
    onApply: (type: String, category: String?, accountId: Int?, minAmount: Double?, maxAmount: Double?, source: String?, channel: String?) -> Unit
) {
    var tempType by remember { mutableStateOf(currentType) }
    var tempCategory by remember { mutableStateOf(currentCategory) }
    var tempAccountId by remember { mutableStateOf(currentAccountId) }
    var tempSource by remember { mutableStateOf(currentSource) }
    var tempChannel by remember { mutableStateOf(currentChannel) }
    var minAmountStr by remember { mutableStateOf(currentMinAmount?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var maxAmountStr by remember { mutableStateOf(currentMaxAmount?.let { String.format(Locale.US, "%.2f", it) } ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.filter_btn),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                TextButton(
                    onClick = {
                        tempType = "all"
                        tempCategory = null
                        tempAccountId = null
                        tempSource = null
                        tempChannel = null
                        minAmountStr = ""
                        maxAmountStr = ""
                    }
                ) {
                    Text(stringResource(R.string.filter_reset_btn), color = Slate500, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 交易类型
            Text(stringResource(R.string.filter_type_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "all" to "全部",
                    "expense" to "支出",
                    "income" to "收入",
                    "transfer" to "自转"
                ).forEach { (tKey, tLabel) ->
                    val isSel = tempType == tKey
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSel) PrimaryTeal else Slate100,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { tempType = tKey }
                    ) {
                        Text(
                            text = tLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) Color.White else Slate700,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 记录来源筛选
            Text(stringResource(R.string.filter_source_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAllSrc = tempSource == null
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAllSrc) PrimaryTeal else Slate100,
                    modifier = Modifier.clickable {
                        tempSource = null
                        tempChannel = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.filter_all),
                        fontSize = 12.sp,
                        fontWeight = if (isAllSrc) FontWeight.Bold else FontWeight.Medium,
                        color = if (isAllSrc) Color.White else Slate700,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                val sources = listOf(
                    "notification" to "📱 手机通知",
                    "manual" to "✍️ 手动记账",
                    "import" to "📥 文件导入",
                    "api" to "⚡ API 接入",
                    "nlp" to "🤖 智能记账",
                    "recurring" to "🔄 固定收支",
                    "split_bill" to "👥 AA分账",
                    "reconciliation" to "⚖️ 平账调整",
                    "unknown" to "❓ 未知/历史"
                )
                sources.forEach { (srcKey, srcLabel) ->
                    val isSel = tempSource == srcKey
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSel) PrimaryTeal else Slate100,
                        modifier = Modifier.clickable {
                            if (isSel) {
                                tempSource = null
                                tempChannel = null
                            } else {
                                tempSource = srcKey
                                if (srcKey != "notification") tempChannel = null
                            }
                        }
                    ) {
                        Text(
                            text = srcLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) Color.White else Slate700,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 当选择“手机通知”来源时，联动展示通知渠道二级筛选
            if (tempSource == "notification") {
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.filter_channel_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Slate600)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAllChan = tempChannel == null
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAllChan) PrimaryTeal else Slate100,
                        modifier = Modifier.clickable { tempChannel = null }
                    ) {
                        Text(
                            text = "全部渠道",
                            fontSize = 11.sp,
                            fontWeight = if (isAllChan) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllChan) Color.White else Slate700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    val channels = listOf(
                        "maybank" to "Maybank",
                        "tng" to "Touch 'n Go",
                        "grab" to "Grab",
                        "public_bank" to "Public Bank",
                        "shopee" to "ShopeePay",
                        "bank_card" to "银行短信",
                        "generic" to "其他通知"
                    )
                    channels.forEach { (chanKey, chanLabel) ->
                        val isSel = tempChannel == chanKey
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { tempChannel = if (isSel) null else chanKey }
                        ) {
                            Text(
                                text = chanLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 金额区间
            Text(stringResource(R.string.filter_amount_range), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = minAmountStr,
                    onValueChange = { minAmountStr = it },
                    placeholder = { Text(stringResource(R.string.filter_min_amount_hint), fontSize = 12.sp, color = Slate400) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryTeal, focusedLabelColor = PrimaryTeal)
                )
                Text("~", color = Slate400, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = maxAmountStr,
                    onValueChange = { maxAmountStr = it },
                    placeholder = { Text(stringResource(R.string.filter_max_amount_hint), fontSize = 12.sp, color = Slate400) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryTeal, focusedLabelColor = PrimaryTeal)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 账户筛选
            if (availableAccounts.isNotEmpty()) {
                Text(stringResource(R.string.filter_account_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAllAcc = tempAccountId == null
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAllAcc) PrimaryTeal else Slate100,
                        modifier = Modifier.clickable { tempAccountId = null }
                    ) {
                        Text(
                            text = stringResource(R.string.filter_all),
                            fontSize = 12.sp,
                            fontWeight = if (isAllAcc) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllAcc) Color.White else Slate700,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    availableAccounts.forEach { acc ->
                        val isSel = tempAccountId == acc.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { tempAccountId = if (isSel) null else acc.id }
                        ) {
                            Text(
                                text = acc.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 分类筛选
            if (availableCategories.isNotEmpty()) {
                Text(stringResource(R.string.filter_category_label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAllCat = tempCategory == null
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAllCat) PrimaryTeal else Slate100,
                        modifier = Modifier.clickable { tempCategory = null }
                    ) {
                        Text(
                            text = stringResource(R.string.filter_all),
                            fontSize = 12.sp,
                            fontWeight = if (isAllCat) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllCat) Color.White else Slate700,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    availableCategories.forEach { cat ->
                        val isSel = tempCategory == cat
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) PrimaryTeal else Slate100,
                            modifier = Modifier.clickable { tempCategory = if (isSel) null else cat }
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 底部操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveAsPresetClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.filter_save_as_preset), fontSize = 13.sp, color = PrimaryTeal)
                }
                Button(
                    onClick = {
                        val min = minAmountStr.toDoubleOrNull()
                        val max = maxAmountStr.toDoubleOrNull()
                        onApply(tempType, tempCategory, tempAccountId, min, max, tempSource, tempChannel)
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
                ) {
                    Text(stringResource(R.string.filter_apply_btn), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SavePresetDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, isPinned: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isPinned by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Slate900,
        textContentColor = Slate800,
        title = { Text(stringResource(R.string.filter_save_as_preset), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.filter_preset_name_label), fontSize = 13.sp, color = Slate600)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.filter_preset_name_hint), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryTeal,
                        focusedLabelColor = PrimaryTeal
                    )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPinned = !isPinned }
                ) {
                    Checkbox(
                        checked = isPinned,
                        onCheckedChange = { isPinned = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryTeal)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.filter_pin_quick_bar), fontSize = 13.sp, color = Slate700)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), isPinned)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Slate600)
            }
        }
    )
}

