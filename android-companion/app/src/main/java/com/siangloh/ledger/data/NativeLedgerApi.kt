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
    val accountType: String
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
    val dueDay: Int?
)

data class AccountsSummary(
    val totalAssets: Double,
    val totalLiabilities: Double,
    val netWorth: Double,
    val accounts: List<AccountRecord>
)

data class CategoryRecord(
    val id: Int,
    val name: String,
    val type: String // 'expense' or 'income'
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

object NativeLedgerApi {
    private const val TAG = "NativeLedgerApi"

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
        page: Int = 1,
        limit: Int = 50
    ): Result<TransactionsPage> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            val username = NetworkHelper.getUsername(context)
            if (username.isNotEmpty()) params.add("username=${URLEncoder.encode(username, "UTF-8")}")
            if (!type.isNullOrBlank() && type != "all") params.add("type=${URLEncoder.encode(type, "UTF-8")}")
            if (!month.isNullOrBlank()) params.add("month=${URLEncoder.encode(month, "UTF-8")}")
            if (!category.isNullOrBlank() && category != "全部" && category != "all") params.add("category=${URLEncoder.encode(category, "UTF-8")}")
            if (!search.isNullOrBlank()) params.add("search=${URLEncoder.encode(search, "UTF-8")}")
            if (accountId != null && accountId > 0) params.add("account_id=$accountId")
            params.add("page=$page")
            params.add("limit=$limit")

            val endpoint = "/api/native/transactions" + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
            val conn = openConnection(context, endpoint, "GET")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(Exception(json.optString("message", "获取流水失败")))
            }

            val itemsArr = json.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<TransactionRecord>()
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.getJSONObject(i)
                list.add(
                    TransactionRecord(
                        id = obj.optLong("id"),
                        date = obj.optString("date"),
                        type = obj.optString("type", "expense"),
                        category = obj.optString("category", "其他"),
                        amount = obj.optDouble("amount", 0.0),
                        note = obj.optString("note", ""),
                        tags = obj.optString("tags", ""),
                        accountId = if (obj.has("account_id") && !obj.isNull("account_id")) obj.optInt("account_id") else null,
                        accountName = obj.optString("account_name", "未指定"),
                        accountType = obj.optString("account_type", "")
                    )
                )
            }

            Result.success(
                TransactionsPage(
                    items = list,
                    totalCount = json.optInt("total_count", list.size),
                    page = json.optInt("page", page),
                    limit = json.optInt("limit", limit),
                    hasMore = json.optBoolean("has_more", false)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getTransactions error: ${e.message}", e)
            Result.failure(e)
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
        toAccountId: Int? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(context, "/api/native/transactions", "POST")
            conn.doOutput = true

            val jsonPayload = JSONObject().apply {
                put("type", type)
                put("amount", amount)
                put("category", category)
                put("date", date)
                put("note", note)
                put("tags", tags)
                accountId?.let { put("account_id", it) }
                toAccountId?.let { put("to_account_id", it) }
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
                Result.success(json.optLong("id", 0L))
            } else {
                Result.failure(Exception(json.optString("message", "记账失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "addTransaction error: ${e.message}", e)
            Result.failure(e)
        }
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
        try {
            val conn = openConnection(context, "/api/native/transactions/$txId", "POST")
            conn.doOutput = true

            val jsonPayload = JSONObject().apply {
                put("type", type)
                put("amount", amount)
                put("category", category)
                put("date", date)
                put("note", note)
                put("tags", tags)
                accountId?.let { put("account_id", it) }
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
                Result.failure(Exception(json.optString("message", "更新失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateTransaction error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteTransaction(context: Context, txId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(context, "/api/native/transactions/$txId", "DELETE")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            if (json.optBoolean("ok", false)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(json.optString("message", "删除失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteTransaction error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getAccounts(context: Context): Result<AccountsSummary> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            val username = NetworkHelper.getUsername(context)
            if (username.isNotEmpty()) params.add("username=${URLEncoder.encode(username, "UTF-8")}")
            val endpoint = "/api/native/accounts" + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            val conn = openConnection(context, endpoint, "GET")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(Exception(json.optString("message", "获取账户失败")))
            }

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
                        dueDay = if (obj.has("due_day") && !obj.isNull("due_day")) obj.optInt("due_day") else null
                    )
                )
            }

            Result.success(
                AccountsSummary(
                    totalAssets = json.optDouble("total_assets", 0.0),
                    totalLiabilities = json.optDouble("total_liabilities", 0.0),
                    netWorth = json.optDouble("net_worth", 0.0),
                    accounts = list
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getAccounts error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun addOrUpdateAccount(
        context: Context,
        id: Int? = null,
        name: String,
        type: String,
        currency: String = "MYR",
        creditLimit: Double = 0.0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(context, "/api/native/accounts", "POST")
            conn.doOutput = true

            val jsonPayload = JSONObject().apply {
                id?.let { put("id", it) }
                put("name", name)
                put("type", type)
                put("currency", currency)
                put("credit_limit", creditLimit)
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
                Result.failure(Exception(json.optString("message", "保存账户失败")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "addOrUpdateAccount error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCategories(context: Context): Result<Pair<List<CategoryRecord>, List<CategoryRecord>>> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            val username = NetworkHelper.getUsername(context)
            if (username.isNotEmpty()) params.add("username=${URLEncoder.encode(username, "UTF-8")}")
            val endpoint = "/api/native/categories" + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            val conn = openConnection(context, endpoint, "GET")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
            val expenseArr = json.optJSONArray("expense_categories") ?: JSONArray()
            val incomeArr = json.optJSONArray("income_categories") ?: JSONArray()

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

            Result.success(Pair(expenses, incomes))
        } catch (e: Exception) {
            Log.e(TAG, "getCategories error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getToolsSummary(context: Context): Result<ToolsSummary> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            val username = NetworkHelper.getUsername(context)
            if (username.isNotEmpty()) params.add("username=${URLEncoder.encode(username, "UTF-8")}")
            val endpoint = "/api/native/tools-summary" + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            val conn = openConnection(context, endpoint, "GET")
            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                           else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(respBody)
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

            Result.success(
                ToolsSummary(
                    subscriptions = subs,
                    totalMonthlySub = json.optDouble("total_monthly_sub", 0.0),
                    installments = insts,
                    totalRemainingInst = json.optDouble("total_remaining_inst", 0.0)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getToolsSummary error: ${e.message}", e)
            Result.failure(e)
        }
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
}
