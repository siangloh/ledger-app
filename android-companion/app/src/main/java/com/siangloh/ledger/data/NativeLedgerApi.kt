package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

data class TransactionRecord(
    val id: Long,
    val date: String,
    val type: String, // 'expense', 'income', 'transfer'
    val category: String,
    val amount: Double,
    val note: String,
    val tags: String,
    val accountId: Int?,
    val accountName: String,
    val accountType: String,
    val source: String = "unknown",
    val channel: String? = null,
    val sourceLabel: String = "未知/历史",
    val channelLabel: String? = null,
    val badgeText: String = "",
    val badgeEmoji: String = "",
    val badgeColor: String = "#94a3b8",
    val importBatchId: String? = null,
    val sourceRef: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastEditSource: String? = null,
    val isEdited: Boolean = false,
    val hasMergedEvidence: Boolean = false
)

data class TransactionsPage(
    val items: List<TransactionRecord>,
    val totalCount: Int,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean
)

data class AccountRecord(
    val id: Int,
    val name: String,
    val type: String, // 'savings', 'current', 'credit_card', 'e_wallet', 'cash', 'investment', 'other'
    val balance: Double,
    val creditLimit: Double,
    val currency: String,
    val statementDay: Int?,
    val dueDay: Int?,
    val lastReconciledAt: String? = null,
    val reconciledBalance: Double? = null,
    val unreconciledCount: Int = 0
)

data class ReconcileTxItem(
    val id: Int,
    val date: String,
    val type: String,
    val category: String,
    val amount: Double,
    val note: String,
    val isReconciled: Boolean
)

data class ReconcileHistoryItem(
    val id: Int,
    val statementBalance: Double,
    val systemBalance: Double,
    val discrepancy: Double,
    val reconciledAt: String,
    val note: String
)

data class AccountReconciliationData(
    val accountId: Int,
    val accountName: String,
    val accountType: String,
    val currency: String,
    val systemBalance: Double,
    val unreconciledCount: Int,
    val transactions: List<ReconcileTxItem>,
    val history: List<ReconcileHistoryItem>
)

data class AccountsSummary(
    val totalAssets: Double,
    val totalLiabilities: Double,
    val netWorth: Double,
    val accounts: List<AccountRecord>,
    val defaultAccountId: Int? = null,
    val defaultGroup: String? = null
)

data class CategoryRecord(
    val id: Int,
    val name: String,
    val type: String // 'expense', 'income', 'savings'
)

class ServerValidationException(message: String) : Exception(message)

data class CategoriesResult(
    val expenses: List<CategoryRecord>,
    val incomes: List<CategoryRecord>,
    val savings: List<CategoryRecord>
)

data class CategoryRuleRecord(
    val merchantPattern: String,
    val category: String,
    val matchType: String, // 'contains', 'exact', 'regex'
    val applyCount: Int,
    val isActive: Boolean,
    val createdAt: String? = null,
    val updatedAt: String? = null
)


data class SubscriptionRecord(
    val id: Int,
    val name: String,
    val category: String,
    val cost: Double,
    val currency: String,
    val nextBillingDate: String,
    val autoRenew: Boolean
)

data class InstallmentRecord(
    val id: Int,
    val title: String,
    val totalAmount: Double,
    val tenureMonths: Int,
    val paidPeriods: Int,
    val monthlyAmount: Double,
    val remainingAmount: Double
)

data class ToolsSummary(
    val subscriptions: List<SubscriptionRecord>,
    val totalMonthlySub: Double,
    val installments: List<InstallmentRecord>,
    val totalRemainingInst: Double
)

data class TransactionMatchRecord(
    val txId: Long,
    val txDate: String,
    val txAmount: Double,
    val txCategory: String,
    val txNote: String,
    val matchedSubId: Int? = null,
    val matchedSubName: String? = null,
    val matchedSubCost: Double? = null,
    val matchedSubNextBilling: String? = null,
    val matchedSubTargetToCancel: Boolean = false,
    val matchedInstId: Int? = null,
    val matchedInstTitle: String? = null,
    val matchedInstNextPeriod: Int? = null,
    val matchedInstTenure: Int? = null
)

data class BudgetItemRecord(
    val category: String,
    val limit: Double,
    val spent: Double,
    val remaining: Double,
    val pct: Double,
    val level: String, // 'ok', 'warn', 'reached', 'over'
    val color: String
)

data class BillingCycleRecord(
    val startDate: String,
    val endDate: String,
    val label: String,
    val startDay: Int,
    val totalDays: Int,
    val passedDays: Int,
    val remainingDays: Int
)

data class OverallBudgetRecord(
    val limit: Double,
    val spent: Double,
    val remaining: Double,
    val pct: Double,
    val level: String,
    val dailyAllowance: Double,
    val isExplicit: Boolean,
    val isSet: Boolean
)

data class BudgetOverviewRecord(
    val month: String,
    val billingCycle: BillingCycleRecord,
    val overall: OverallBudgetRecord,
    val categories: List<BudgetItemRecord>,
    val totalCategoriesBudgeted: Int,
    val overCount: Int,
    val warnCount: Int,
    val okCount: Int
)

data class SavedFilterRecord(
    val id: Int,
    val name: String,
    val criteriaJson: String,
    val isPinned: Boolean,
    val orderNum: Int
)

object NativeLedgerApi {
    private const val TAG = "NativeLedgerApi"

    // 内存级快速缓存（In-Memory Cache）：支持秒切 Tab 瞬间渲染，消除重复网络请求
    private var cachedAccounts: AccountsSummary? = null
    private var cachedAccountsTimestamp: Long = 0L

    private var cachedCategories: CategoriesResult? = null
    private var cachedCategoriesTimestamp: Long = 0L

    private var cachedTools: ToolsSummary? = null
    private var cachedToolsTimestamp: Long = 0L

    private val recordsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TransactionsPage>>()

    fun invalidateCache() {
        cachedAccounts = null
        cachedAccountsTimestamp = 0L
        cachedCategories = null
        cachedCategoriesTimestamp = 0L
        cachedTools = null
        cachedToolsTimestamp = 0L
        recordsCache.clear()
    }

    private fun openConnection(context: Context, endpoint: String, method: String = "GET"): HttpURLConnection {
        val serverUrl = NetworkHelper.getServerUrl(context).trimEnd('/')
        val url = URL("$serverUrl$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "LedgerAppNative/1.0 (Android; Dalvik)")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val key = NetworkHelper.getApiKey(context)
        if (key.isNotEmpty()) {
            conn.setRequestProperty("X-API-KEY", key)
        }
        return conn
    }

    suspend fun getTransactions(
        context: Context,
        type: String? = null,
        month: String? = null,
        category: String? = null,
        search: String? = null,
        accountId: Int? = null,
        startDate: String? = null,
        endDate: String? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        source: String? = null,
        channel: String? = null,
        page: Int = 1,
        limit: Int = 50,
        forceRefresh: Boolean = false
    ): Result<TransactionsPage> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getTransactionsPage(
            context = context,
            month = month,
            type = type,
            search = search,
            page = page,
            limit = limit,
            category = category,
            accountId = accountId,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            source = source,
            channel = channel
        )
    }

    fun parseTransactionsPageJson(json: JSONObject?, fallbackPage: Int = 1, fallbackLimit: Int = 50): TransactionsPage {
        if (json == null) {
            return TransactionsPage(emptyList(), 0, fallbackPage, fallbackLimit, false)
        }
        return try {
            val itemsArr = json.optJSONArray("items") ?: org.json.JSONArray()
            val list = mutableListOf<TransactionRecord>()
            for (i in 0 until itemsArr.length()) {
                try {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val amtRaw = if (obj.isNull("amount")) 0.0 else obj.optDouble("amount", 0.0)
                    val amt = if (amtRaw.isNaN() || amtRaw.isInfinite()) 0.0 else amtRaw

                    val accountId = if (obj.has("account_id") && !obj.isNull("account_id")) obj.optInt("account_id") else null
                    val channel = if (obj.has("channel") && !obj.isNull("channel")) {
                        obj.optString("channel").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val channelLabel = if (obj.has("channel_label") && !obj.isNull("channel_label")) {
                        obj.optString("channel_label").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val importBatchId = if (obj.has("import_batch_id") && !obj.isNull("import_batch_id")) {
                        obj.optString("import_batch_id").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val sourceRef = if (obj.has("source_ref") && !obj.isNull("source_ref")) {
                        obj.optString("source_ref").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val createdAt = if (obj.has("created_at") && !obj.isNull("created_at")) {
                        obj.optString("created_at").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val updatedAt = if (obj.has("updated_at") && !obj.isNull("updated_at")) {
                        obj.optString("updated_at").takeIf { it.isNotBlank() && it != "null" }
                    } else null
                    val lastEditSource = if (obj.has("last_edit_source") && !obj.isNull("last_edit_source")) {
                        obj.optString("last_edit_source").takeIf { it.isNotBlank() && it != "null" }
                    } else null

                    list.add(
                        TransactionRecord(
                            id = obj.optLong("id", 0L),
                            date = obj.optString("date", "").takeIf { it != "null" } ?: "",
                            type = obj.optString("type", "expense").takeIf { it != "null" } ?: "expense",
                            category = obj.optString("category", "其他").takeIf { it != "null" } ?: "其他",
                            amount = amt,
                            note = obj.optString("note", "").takeIf { it != "null" } ?: "",
                            tags = obj.optString("tags", "").takeIf { it != "null" } ?: "",
                            accountId = accountId,
                            accountName = obj.optString("account_name", "未指定").takeIf { it != "null" } ?: "未指定",
                            accountType = obj.optString("account_type", "").takeIf { it != "null" } ?: "",
                            source = obj.optString("source", "unknown").takeIf { it != "null" } ?: "unknown",
                            channel = channel,
                            sourceLabel = obj.optString("source_label", "未知/历史").takeIf { it != "null" } ?: "未知/历史",
                            channelLabel = channelLabel,
                            badgeText = obj.optString("badge_text", "").takeIf { it != "null" } ?: "",
                            badgeEmoji = obj.optString("badge_emoji", "").takeIf { it != "null" } ?: "",
                            badgeColor = obj.optString("badge_color", "#94a3b8").takeIf { it != "null" } ?: "#94a3b8",
                            importBatchId = importBatchId,
                            sourceRef = sourceRef,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                            lastEditSource = lastEditSource,
                            isEdited = obj.optBoolean("is_edited", false),
                            hasMergedEvidence = obj.optBoolean("has_merged_evidence", false)
                        )
                    )
                } catch (_: Exception) {}
            }
            TransactionsPage(
                items = list,
                totalCount = if (json.isNull("total_count")) list.size else json.optInt("total_count", list.size),
                page = if (json.isNull("page")) fallbackPage else json.optInt("page", fallbackPage),
                limit = if (json.isNull("limit")) fallbackLimit else json.optInt("limit", fallbackLimit),
                hasMore = json.optBoolean("has_more", false)
            )
        } catch (e: Exception) {
            TransactionsPage(emptyList(), 0, fallbackPage, fallbackLimit, false)
        }
    }

    suspend fun addTransaction(
        context: Context,
        type: String,
        amount: Double,
        category: String,
        date: String,
        note: String,
        tags: String,
        accountId: Int? = null,
        toAccountId: Int? = null,
        groupName: String? = null,
        clientTxId: String? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        invalidateCache()
        LocalLedgerRepository.createTransaction(context, date, type, category, amount, note, tags, accountId, toAccountId)
    }

    suspend fun updateTransaction(
        context: Context,
        txId: Long,
        type: String,
        amount: Double,
        category: String,
        date: String,
        note: String,
        tags: String,
        accountId: Int? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        invalidateCache()
        LocalLedgerRepository.updateTransaction(context, txId, date, type, category, amount, note, tags, accountId)
    }

    suspend fun deleteTransaction(context: Context, txId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        invalidateCache()
        LocalLedgerRepository.deleteTransaction(context, txId)
    }

    suspend fun restoreTransaction(context: Context, record: TransactionRecord): Result<Unit> = withContext(Dispatchers.IO) {
        invalidateCache()
        LocalLedgerRepository.restoreTransaction(context, record)
    }

    suspend fun getAccounts(context: Context, forceRefresh: Boolean = false): Result<AccountsSummary> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getAccountsSummary(context)
    }

    fun parseAccountsSummaryJson(json: JSONObject): AccountsSummary {
        val accArr = json.optJSONArray("accounts") ?: JSONArray()
        val list = mutableListOf<AccountRecord>()
        for (i in 0 until accArr.length()) {
            val obj = accArr.getJSONObject(i)
            list.add(
                AccountRecord(
                    id = obj.optInt("id"),
                    name = obj.optString("name"),
                    type = obj.optString("type", "savings"),
                    balance = obj.optDouble("balance", 0.0),
                    creditLimit = obj.optDouble("credit_limit", 0.0),
                    currency = obj.optString("currency", "MYR"),
                    statementDay = if (obj.has("statement_day") && !obj.isNull("statement_day")) obj.optInt("statement_day") else null,
                    dueDay = if (obj.has("due_day") && !obj.isNull("due_day")) obj.optInt("due_day") else null,
                    lastReconciledAt = if (obj.has("last_reconciled_at") && !obj.isNull("last_reconciled_at")) obj.optString("last_reconciled_at") else null,
                    reconciledBalance = if (obj.has("reconciled_balance") && !obj.isNull("reconciled_balance")) obj.optDouble("reconciled_balance") else null,
                    unreconciledCount = obj.optInt("unreconciled_count", 0)
                )
            )
        }
        val defaultAccountId = if (json.has("default_account_id") && !json.isNull("default_account_id")) json.optInt("default_account_id") else null
        val defaultGroup = if (json.has("default_group") && !json.isNull("default_group")) json.optString("default_group") else null

        return AccountsSummary(
            totalAssets = json.optDouble("total_assets", 0.0),
            totalLiabilities = json.optDouble("total_liabilities", 0.0),
            netWorth = json.optDouble("net_worth", 0.0),
            accounts = list,
            defaultAccountId = defaultAccountId,
            defaultGroup = defaultGroup
        )
    }

    suspend fun addOrUpdateAccount(
        context: Context,
        id: Int? = null,
        name: String,
        type: String,
        currency: String = "MYR",
        creditLimit: Double = 0.0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        invalidateCache()
        LocalLedgerRepository.addOrUpdateAccount(context, id, name, type, 0.0, creditLimit, currency)
    }

    fun parseCategoriesJson(json: JSONObject): CategoriesResult {
        val expenseArr = json.optJSONArray("expense_categories") ?: JSONArray()
        val incomeArr = json.optJSONArray("income_categories") ?: JSONArray()
        val savingsArr = json.optJSONArray("savings_categories") ?: JSONArray()

        val expenses = mutableListOf<CategoryRecord>()
        for (i in 0 until expenseArr.length()) {
            val obj = expenseArr.getJSONObject(i)
            expenses.add(CategoryRecord(obj.optInt("id"), obj.optString("name"), "expense"))
        }

        val incomes = mutableListOf<CategoryRecord>()
        for (i in 0 until incomeArr.length()) {
            val obj = incomeArr.getJSONObject(i)
            incomes.add(CategoryRecord(obj.optInt("id"), obj.optString("name"), "income"))
        }

        val savings = mutableListOf<CategoryRecord>()
        for (i in 0 until savingsArr.length()) {
            val obj = savingsArr.getJSONObject(i)
            savings.add(CategoryRecord(obj.optInt("id"), obj.optString("name"), "savings"))
        }

        return CategoriesResult(expenses, incomes, savings)
    }

    suspend fun getCategories(context: Context, forceRefresh: Boolean = false): Result<CategoriesResult> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getCategoriesResult(context)
    }

    suspend fun getToolsSummary(context: Context, forceRefresh: Boolean = false): Result<ToolsSummary> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedTools != null && (now - cachedToolsTimestamp < 300_000L)) {
            return@withContext Result.success(cachedTools!!)
        }
        val res = LocalLedgerRepository.getToolsSummary(context)
        res.onSuccess {
            cachedTools = it
            cachedToolsTimestamp = now
        }
        res
    }

    fun parseToolsSummaryJson(json: JSONObject): ToolsSummary {
        val subArr = json.optJSONArray("subscriptions") ?: JSONArray()
        val instArr = json.optJSONArray("installments") ?: JSONArray()

        val subs = mutableListOf<SubscriptionRecord>()
        for (i in 0 until subArr.length()) {
            val obj = subArr.getJSONObject(i)
            subs.add(
                SubscriptionRecord(
                    id = obj.optInt("id"),
                    name = obj.optString("name"),
                    category = obj.optString("category"),
                    cost = obj.optDouble("cost", 0.0),
                    currency = obj.optString("currency", "MYR"),
                    nextBillingDate = obj.optString("next_billing_date", ""),
                    autoRenew = obj.optBoolean("auto_renew", true)
                )
            )
        }

        val insts = mutableListOf<InstallmentRecord>()
        for (i in 0 until instArr.length()) {
            val obj = instArr.getJSONObject(i)
            insts.add(
                InstallmentRecord(
                    id = obj.optInt("id"),
                    title = obj.optString("title"),
                    totalAmount = obj.optDouble("total_amount", 0.0),
                    tenureMonths = obj.optInt("tenure_months", 1),
                    paidPeriods = obj.optInt("paid_periods", 0),
                    monthlyAmount = obj.optDouble("monthly_amount", 0.0),
                    remainingAmount = obj.optDouble("remaining_amount", 0.0)
                )
            )
        }

        return ToolsSummary(
            subscriptions = subs,
            totalMonthlySub = json.optDouble("total_monthly_sub", 0.0),
            installments = insts,
            totalRemainingInst = json.optDouble("total_remaining_inst", 0.0)
        )
    }

    suspend fun handlePendingAction(
        context: Context,
        pendingId: Long,
        action: String, // 'confirm', 'ignore', 'transfer'
        category: String? = null,
        note: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "/api/pending-transactions/$pendingId/$action"
            val conn = openConnection(context, endpoint, "POST")
            conn.doOutput = true

            val jsonPayload = JSONObject().apply {
                category?.let { put("category", it) }
                note?.let { put("note", it) }
                val username = NetworkHelper.getUsername(context)
                if (username.isNotEmpty()) put("username", username)
            }.toString()

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(jsonPayload)
                it.flush()
            }

            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(json.optString("message", "操作失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlePendingAction error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun quickCategorize(
        context: Context,
        transactionId: Long,
        category: String,
        rememberRule: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(context, "/api/native/quick-categorize", "POST")
            conn.doOutput = true
            val apiKey = NetworkHelper.getApiKey(context)
            val username = NetworkHelper.getUsername(context)
            val payload = JSONObject().apply {
                if (apiKey.isNotEmpty()) put("key", apiKey)
                put("transaction_id", transactionId)
                put("category", category)
                put("remember_rule", rememberRule)
                if (username.isNotEmpty()) put("username", username)
            }.toString()

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(payload)
                it.flush()
            }

            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(json.optString("message", "分类失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "quickCategorize error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getAccountReconciliation(context: Context, accountId: Int): Result<AccountReconciliationData> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getAccountReconciliation(context, accountId)
    }

    suspend fun reconcileAccount(
        context: Context,
        accountId: Int,
        statementBalance: Double,
        autoAdjust: Boolean,
        note: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.reconcileAccount(context, accountId, statementBalance, autoAdjust, note)
    }

    suspend fun toggleTransactionReconciled(
        context: Context,
        txId: Int,
        isReconciled: Boolean
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.toggleTransactionReconciled(context, txId, isReconciled)
    }

    suspend fun getCategoryRules(
        context: Context,
        search: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CategoryRuleRecord>> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getCategoryRules(context, search, page, limit)
    }

    suspend fun saveCategoryRule(
        context: Context,
        pattern: String,
        category: String,
        matchType: String = "contains",
        isActive: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.saveCategoryRule(context, pattern, category, matchType, isActive)
    }

    suspend fun deleteCategoryRule(
        context: Context,
        pattern: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.deleteCategoryRule(context, pattern)
    }

    suspend fun toggleCategoryRule(
        context: Context,
        pattern: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.toggleCategoryRule(context, pattern)
    }

    suspend fun applyCategoryRuleHistory(
        context: Context,
        pattern: String,
        category: String,
        matchType: String = "contains",
        onlyUncategorized: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        recordsCache.clear()
        LocalLedgerRepository.applyCategoryRuleHistory(context, pattern, category, matchType, onlyUncategorized)
    }

    suspend fun applyAllCategoryRulesHistory(
        context: Context,
        onlyUncategorized: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        recordsCache.clear()
        LocalLedgerRepository.applyAllCategoryRulesHistory(context, onlyUncategorized)
    }

    suspend fun getBudgetOverview(
        context: Context,
        month: String? = null
    ): Result<BudgetOverviewRecord> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getBudgetOverview(context, month)
    }

    suspend fun saveOverallBudget(
        context: Context,
        monthlyLimit: Double
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.saveOverallBudget(context, monthlyLimit)
    }

    suspend fun saveCategoryBudget(
        context: Context,
        category: String,
        monthlyLimit: Double
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.saveCategoryBudget(context, category, monthlyLimit)
    }

    suspend fun deleteCategoryBudget(
        context: Context,
        category: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.deleteCategoryBudget(context, category)
    }

    suspend fun getSavedFilters(context: Context): Result<List<SavedFilterRecord>> = withContext(Dispatchers.IO) {
        try {
            val username = NetworkHelper.getUsername(context)
            val endpoint = "/api/native/saved-filters" + if (username.isNotEmpty()) "?username=${URLEncoder.encode(username, "UTF-8")}" else ""
            val conn = openConnection(context, endpoint, "GET")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                val arr = json.optJSONArray("filters") ?: JSONArray()
                val list = mutableListOf<SavedFilterRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        SavedFilterRecord(
                            id = obj.getInt("id"),
                            name = obj.getString("name"),
                            criteriaJson = obj.optJSONObject("criteria")?.toString() ?: "{}",
                            isPinned = obj.optBoolean("is_pinned", false),
                            orderNum = obj.optInt("order_num", 0)
                        )
                    )
                }
                Result.success(list)
            } else {
                Result.failure(Exception(json.optString("message", "获取筛选预设失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSavedFilters error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createSavedFilter(
        context: Context,
        name: String,
        criteria: JSONObject,
        isPinned: Boolean = false
    ): Result<SavedFilterRecord> = withContext(Dispatchers.IO) {
        try {
            val username = NetworkHelper.getUsername(context)
            val endpoint = "/api/native/saved-filters" + if (username.isNotEmpty()) "?username=${URLEncoder.encode(username, "UTF-8")}" else ""
            val conn = openConnection(context, endpoint, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().apply {
                put("name", name)
                put("criteria", criteria)
                put("is_pinned", if (isPinned) 1 else 0)
            }
            conn.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { it.write(payload.toString()) }
            }
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                val fObj = json.getJSONObject("filter")
                Result.success(
                    SavedFilterRecord(
                        id = fObj.getInt("id"),
                        name = fObj.getString("name"),
                        criteriaJson = fObj.optJSONObject("criteria")?.toString() ?: "{}",
                        isPinned = fObj.optBoolean("is_pinned", false),
                        orderNum = fObj.optInt("order_num", 0)
                    )
                )
            } else {
                Result.failure(Exception(json.optString("message", "保存筛选预设失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSavedFilter error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteSavedFilter(context: Context, filterId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val username = NetworkHelper.getUsername(context)
            val endpoint = "/api/native/saved-filters/$filterId" + if (username.isNotEmpty()) "?username=${URLEncoder.encode(username, "UTF-8")}" else ""
            val conn = openConnection(context, endpoint, "DELETE")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                Result.success(true)
            } else {
                Result.failure(Exception(json.optString("message", "删除筛选预设失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteSavedFilter error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getSubscriptionMatches(context: Context): Result<List<TransactionMatchRecord>> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.getSubscriptionMatches(context)
    }

    suspend fun saveSubscription(
        context: Context,
        id: Int?,
        name: String,
        category: String,
        cost: Double,
        currency: String = "MYR",
        nextBillingDate: String = "每月1号",
        autoRenew: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cachedTools = null
        LocalLedgerRepository.saveSubscription(context, id, name, category, cost, currency, nextBillingDate, autoRenew)
    }

    suspend fun deleteSubscription(context: Context, id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        cachedTools = null
        LocalLedgerRepository.deleteSubscription(context, id)
    }

    suspend fun saveInstallment(
        context: Context,
        id: Int?,
        title: String,
        totalAmount: Double,
        tenureMonths: Int,
        paidPeriods: Int,
        monthlyAmount: Double? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cachedTools = null
        LocalLedgerRepository.saveInstallment(context, id, title, totalAmount, tenureMonths, paidPeriods, monthlyAmount)
    }

    suspend fun payInstallmentPeriod(context: Context, id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        cachedTools = null
        LocalLedgerRepository.payInstallmentPeriod(context, id)
    }

    suspend fun deleteInstallment(context: Context, id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        cachedTools = null
        LocalLedgerRepository.deleteInstallment(context, id)
    }

    suspend fun applySubscriptionMatch(context: Context, subId: Int, txId: Long?): Result<Boolean> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.applySubscriptionMatch(context, subId, txId)
    }

    suspend fun applyInstallmentMatch(context: Context, instId: Int, txId: Long?): Result<Boolean> = withContext(Dispatchers.IO) {
        LocalLedgerRepository.applyInstallmentMatch(context, instId, txId)
    }
}



