package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale


/** 离线优先的收支分析数据仓库 (Local-First Cashflow Repository) */
object CashflowRepository {
    data class Result(val data: JSONObject, val cached: Boolean)

    private fun getCacheKey(
        context: Context,
        month: String,
        currency: String,
        startDate: String? = null,
        endDate: String? = null
    ): String {
        val server = NetworkHelper.getServerUrl(context).trimEnd('/')
        val key = NetworkHelper.getApiKey(context)
        val user = NetworkHelper.getUsername(context)
        val scope = "$server|$user|$key|$month|$currency|${startDate.orEmpty()}|${endDate.orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 100% 同步纯本地获取缓存。0ms 延迟，无网络开销。
     * 若当前指定币种未找到，会尝试用默认币种的本地缓存兜底。
     */
    fun getCached(
        context: Context,
        month: String,
        currency: String = "",
        startDate: String? = null,
        endDate: String? = null
    ): Result? {
        return try {
            val cache = context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE)
            val directKey = getCacheKey(context, month, currency, startDate, endDate)
            val directSaved = cache.getString(directKey, null)
            if (directSaved != null) {
                val directJson = JSONObject(directSaved)
                if (directJson.has("savings")) {
                    return Result(directJson, cached = true)
                }
            }
            if (currency.isNotEmpty()) {
                val fallbackKey = getCacheKey(context, month, "", startDate, endDate)
                val fallbackSaved = cache.getString(fallbackKey, null)
                if (fallbackSaved != null) {
                    val fallbackJson = JSONObject(fallbackSaved)
                    if (fallbackJson.optString("currency").equals(currency, ignoreCase = true) && fallbackJson.has("savings")) {
                        return Result(fallbackJson, cached = true)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("Cashflow", "getCached failed: ${e.message}")
            null
        }
    }

    fun invalidate(context: Context) {
        try {
            context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    /**
     * 离线优先加载 (Local-First / Offline-First)
     * 支持按月份（YYYY-MM）或自定义日期区间（startDate ~ endDate）加载
     */
    suspend fun load(
        context: Context,
        month: String,
        currency: String = "",
        forceRefresh: Boolean = false,
        startDate: String? = null,
        endDate: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val cacheKey = getCacheKey(context, month, currency, startDate, endDate)
        val cache = context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE)

        // 1. 本地优先：未强制刷新时，只要本地有缓存，直接返回本地数据！
        if (!forceRefresh) {
            val cachedResult = getCached(context, month, currency, startDate, endDate)
            if (cachedResult != null) {
                return@withContext cachedResult
            }
        }

        LocalLedgerRepository.initDefaultsIfNeeded(context)
        val db = AppDatabase.getInstance(context)
        val txList = if (!startDate.isNullOrBlank() && !endDate.isNullOrBlank()) {
            db.localTransactionDao().getByDateRange(startDate, endDate)
        } else {
            db.localTransactionDao().getByMonth("$month%")
        }
        val accounts = db.localAccountDao().getAll().associateBy { it.id }

        var inc = 0.0
        var exp = 0.0
        var savings = 0.0
        var transfer = 0.0

        val expCategoriesMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val expPlatformMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val incPlatformMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val savingsCategoriesMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val savingsPlatformMap = mutableMapOf<String, Pair<Double, MutableList<Int>>>()
        val dailyMap = mutableMapOf<String, Double>()
        val txArray = org.json.JSONArray()

        for (tx in txList) {
            val amt = tx.amount
            val matchedAcc = accounts[tx.accountId]
                ?: LocalLedgerRepository.resolveAccountFallback(tx.channel, tx.note, accounts.values)
            val platformName = matchedAcc?.name ?: "未指定账户"
            if (tx.type == "expense") {
                exp += amt
                val entry = expCategoriesMap.getOrPut(tx.category) { Pair(0.0, mutableListOf()) }
                expCategoriesMap[tx.category] = Pair(entry.first + amt, entry.second.apply { add(tx.id.toInt()) })

                val pEntry = expPlatformMap.getOrPut(platformName) { Pair(0.0, mutableListOf()) }
                expPlatformMap[platformName] = Pair(pEntry.first + amt, pEntry.second.apply { add(tx.id.toInt()) })

                dailyMap[tx.date] = (dailyMap[tx.date] ?: 0.0) + amt
            } else if (tx.type == "income") {
                inc += amt
                val pEntry = incPlatformMap.getOrPut(platformName) { Pair(0.0, mutableListOf()) }
                incPlatformMap[platformName] = Pair(pEntry.first + amt, pEntry.second.apply { add(tx.id.toInt()) })
            } else if (tx.type == "savings") {
                savings += amt
                val rawCat = if (tx.category.isNotBlank() && tx.category != "null") tx.category else "常规储蓄"
                val sEntry = savingsCategoriesMap.getOrPut(rawCat) { Pair(0.0, mutableListOf()) }
                savingsCategoriesMap[rawCat] = Pair(sEntry.first + amt, sEntry.second.apply { add(tx.id.toInt()) })

                val spEntry = savingsPlatformMap.getOrPut(platformName) { Pair(0.0, mutableListOf()) }
                savingsPlatformMap[platformName] = Pair(spEntry.first + amt, spEntry.second.apply { add(tx.id.toInt()) })
            } else if (tx.type == "transfer") {
                transfer += amt
            }

            txArray.put(JSONObject().apply {
                put("id", tx.id)
                put("date", tx.date)
                put("type", tx.type)
                put("amount", tx.amount)
                put("note", tx.note)
                put("category", tx.category)
                put("channel", tx.channel ?: "")
                put("account_name", platformName)
            })
        }

        // 实际结余扣除支出与储蓄存入金额 (可用现金流 = 收入 - 支出 - 储蓄)
        val avail = inc - exp - savings

        val expCategories = org.json.JSONArray()
        for ((cat, pair) in expCategoriesMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            expCategories.put(JSONObject().apply {
                put("label", cat)
                put("amount", amt)
                put("income_percent", if (inc > 0) amt / inc * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val incPlatforms = org.json.JSONArray()
        for ((plat, pair) in incPlatformMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            incPlatforms.put(JSONObject().apply {
                put("key", plat)
                put("label", plat)
                put("amount", amt)
                put("income_percent", if (inc > 0) amt / inc * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val expPlatforms = org.json.JSONArray()
        for ((plat, pair) in expPlatformMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            expPlatforms.put(JSONObject().apply {
                put("key", plat)
                put("label", plat)
                put("amount", amt)
                put("total_percent", if (exp > 0) amt / exp * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val savingsCategories = org.json.JSONArray()
        for ((cat, pair) in savingsCategoriesMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            savingsCategories.put(JSONObject().apply {
                put("label", cat)
                put("amount", amt)
                put("total_percent", if (savings > 0) amt / savings * 100.0 else 0.0)
                put("income_percent", if (inc > 0) amt / inc * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val savingsPlatforms = org.json.JSONArray()
        for ((plat, pair) in savingsPlatformMap.entries.sortedByDescending { it.value.first }) {
            val amt = pair.first
            val ids = pair.second
            savingsPlatforms.put(JSONObject().apply {
                put("key", plat)
                put("label", plat)
                put("amount", amt)
                put("total_percent", if (savings > 0) amt / savings * 100.0 else 0.0)
                val idArr = org.json.JSONArray()
                ids.forEach { idArr.put(it) }
                put("transaction_ids", idArr)
            })
        }

        val dailyArray = org.json.JSONArray()
        if (!startDate.isNullOrBlank() && !endDate.isNullOrBlank()) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val cal = java.util.Calendar.getInstance().apply { time = sdf.parse(startDate)!! }
                val endCal = java.util.Calendar.getInstance().apply { time = sdf.parse(endDate)!! }
                var count = 0
                while (!cal.after(endCal) && count < 62) {
                    val dStr = sdf.format(cal.time)
                    dailyArray.put(dailyMap[dStr] ?: 0.0)
                    cal.add(java.util.Calendar.DATE, 1)
                    count++
                }
            } catch (_: Exception) {
                for (d in 1..31) {
                    val dateStr = String.format(Locale.getDefault(), "%s-%02d", month, d)
                    dailyArray.put(dailyMap[dateStr] ?: 0.0)
                }
            }
        } else {
            for (d in 1..31) {
                val dateStr = String.format(Locale.getDefault(), "%s-%02d", month, d)
                dailyArray.put(dailyMap[dateStr] ?: 0.0)
            }
        }

        val cur = if (currency.isNotBlank()) currency else "RM"

        val toolsRes = LocalLedgerRepository.getToolsSummary(context).getOrNull()
        val subs = toolsRes?.subscriptions ?: emptyList()
        val billsArray = org.json.JSONArray()
        var plannedBills = 0.0
        var paidBills = 0.0
        var pendingBills = 0.0

        val linksArr = getPaymentLinks(context)
        val linksList = mutableListOf<JSONObject>()
        for (i in 0 until linksArr.length()) {
            linksList.add(linksArr.getJSONObject(i))
        }

        for (sub in subs) {
            plannedBills += sub.cost
            val billKey = "sub_${sub.id}"
            val directLinks = linksList.filter { it.optString("bill_key") == billKey }

            val paidAmt: Double
            val pendingAmt: Double
            val isPaid: Boolean

            if (directLinks.isNotEmpty()) {
                var sum = 0.0
                for (link in directLinks) {
                    val txId = link.optLong("transaction_id")
                    val isRefund = link.optBoolean("is_refund", false)
                    val tx = txList.find { it.id == txId }
                    if (tx != null) {
                        if (isRefund) sum -= tx.amount else sum += tx.amount
                    }
                }
                paidAmt = sum.coerceAtLeast(0.0)
                pendingAmt = (sub.cost - paidAmt).coerceAtLeast(0.0)
                isPaid = pendingAmt <= 0.0
            } else {
                // 检查当月是否有对应类别的支出或包含该订阅名称的交易 (优先匹配同账户)
                val matchedTx = txList.find { tx ->
                    if (tx.type != "expense") return@find false
                    val accMatch = sub.accountId == null || tx.accountId == sub.accountId || (sub.accountName != null && sub.accountName.equals(tx.channel, ignoreCase = true))
                    val nameOrCatMatch = tx.category.equals(sub.category, ignoreCase = true) ||
                        tx.note.contains(sub.name, ignoreCase = true) ||
                        tx.tags.contains(sub.name, ignoreCase = true)
                    accMatch && nameOrCatMatch
                } ?: txList.find { tx ->
                    tx.type == "expense" && (
                        tx.category.equals(sub.category, ignoreCase = true) ||
                        tx.note.contains(sub.name, ignoreCase = true) ||
                        tx.tags.contains(sub.name, ignoreCase = true)
                    )
                }
                isPaid = matchedTx != null
                paidAmt = if (isPaid) sub.cost else 0.0
                pendingAmt = if (isPaid) 0.0 else sub.cost
            }

            if (isPaid) paidBills += paidAmt else pendingBills += pendingAmt

            billsArray.put(JSONObject().apply {
                put("bill_key", billKey)
                put("title", sub.name)
                put("amount", sub.cost)
                put("due_date", sub.nextBillingDate.ifBlank { "$month-15" })
                put("paid", paidAmt)
                put("pending", pendingAmt)
                put("is_paid", isPaid)
                put("category", sub.category)
                if (sub.accountId != null) put("account_id", sub.accountId)
                if (!sub.accountName.isNullOrBlank()) put("account_name", sub.accountName)
            })
        }

        val totalPlanned = if (plannedBills > 0.0) plannedBills else exp
        val totalPaid = if (plannedBills > 0.0) paidBills else exp
        val totalPending = if (plannedBills > 0.0) pendingBills else 0.0
        val paymentPct = if (totalPlanned > 0.0) (totalPaid / totalPlanned * 100.0).coerceIn(0.0, 100.0) else 100.0

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(java.util.Date())

        val isCustom = !startDate.isNullOrBlank() && !endDate.isNullOrBlank()
        val data = JSONObject().apply {
            put("ok", true)
            put("month", if (isCustom) "$startDate ~ $endDate" else month)
            put("is_custom_range", isCustom)
            put("start_date", startDate ?: "$month-01")
            put("end_date", endDate ?: "$month-31")
            put("currency", cur)
            put("currencies", org.json.JSONArray().put(cur))
            put("income", inc)
            put("expense", exp)
            put("savings", savings)
            put("total_savings", savings)
            put("transfer", transfer)
            put("total_transfer", transfer)
            put("net", avail)
            put("reserved", totalPending)
            put("planned", totalPlanned)
            put("paid", totalPaid)
            put("payment_percent", paymentPct)
            put("available", avail - totalPending)
            put("daily", dailyArray)
            put("expense_percent", if (inc > 0) (exp / inc * 100) else 0.0)
            put("savings_percent", if (inc > 0) (savings / inc * 100) else 0.0)
            put("reserve_percent", if (inc > 0) (totalPending / inc * 100) else 0.0)
            put("available_percent", if (inc > 0) ((avail - totalPending) / inc * 100) else 0.0)
            put("transactions", txArray)
            put("expense_categories", expCategories)
            put("income_platforms", incPlatforms)
            put("expense_platforms", expPlatforms)
            put("savings_categories", savingsCategories)
            put("savings_platforms", savingsPlatforms)
            put("bills", billsArray)
            put("payment_links", linksArr)
            put("prior_overdue", org.json.JSONArray())
            put("scheduled_transactions", org.json.JSONArray())
            put("confirmed_transactions", org.json.JSONArray())
            put("inactive_bills", org.json.JSONArray())
            put("updated_at", nowIso)
        }
        cache.edit().putString(cacheKey, data.toString()).apply()
        Result(data, false)
    }

    private fun getLinksPrefs(context: Context) =
        context.getSharedPreferences("cashflow_links_prefs", Context.MODE_PRIVATE)

    fun getPaymentLinks(context: Context): org.json.JSONArray {
        val raw = getLinksPrefs(context).getString("payment_links", null)
        return if (!raw.isNullOrBlank()) {
            try { org.json.JSONArray(raw) } catch (_: Exception) { org.json.JSONArray() }
        } else org.json.JSONArray()
    }

    private fun savePaymentLinks(context: Context, arr: org.json.JSONArray) {
        getLinksPrefs(context).edit().putString("payment_links", arr.toString()).apply()
    }

    fun addPaymentLink(context: Context, billKey: String, txId: Long, isRefund: Boolean, month: String = "") {
        val arr = getPaymentLinks(context)
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("bill_key") == billKey && obj.optLong("transaction_id") == txId) {
                return
            }
            list.add(obj)
        }
        list.add(JSONObject().apply {
            put("bill_key", billKey)
            put("transaction_id", txId)
            put("is_refund", isRefund)
            put("month", month)
        })
        val newArr = org.json.JSONArray()
        list.forEach { newArr.put(it) }
        savePaymentLinks(context, newArr)
    }

    fun removePaymentLink(context: Context, billKey: String, txId: Long) {
        val arr = getPaymentLinks(context)
        val newArr = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!(obj.optString("bill_key") == billKey && obj.optLong("transaction_id") == txId)) {
                newArr.put(obj)
            }
        }
        savePaymentLinks(context, newArr)
    }

    suspend fun updateBillAccount(context: Context, billKey: String, accountId: Int?, accountName: String?) {
        if (billKey.startsWith("sub_")) {
            val subId = billKey.removePrefix("sub_").toIntOrNull() ?: return
            val tools = LocalLedgerRepository.getToolsSummary(context).getOrNull()
            val sub = tools?.subscriptions?.find { it.id == subId } ?: return
            LocalLedgerRepository.saveSubscription(
                context = context,
                id = sub.id,
                name = sub.name,
                category = sub.category,
                cost = sub.cost,
                currency = sub.currency,
                nextBillingDate = sub.nextBillingDate,
                autoRenew = sub.autoRenew,
                accountId = accountId,
                accountName = accountName
            )
        }
    }

    suspend fun updateBillAmount(context: Context, billKey: String, amount: Double) {
        if (billKey.startsWith("sub_")) {
            val subId = billKey.removePrefix("sub_").toIntOrNull() ?: return
            val tools = LocalLedgerRepository.getToolsSummary(context).getOrNull()
            val sub = tools?.subscriptions?.find { it.id == subId } ?: return
            LocalLedgerRepository.saveSubscription(
                context = context,
                id = sub.id,
                name = sub.name,
                category = sub.category,
                cost = amount,
                currency = sub.currency,
                nextBillingDate = sub.nextBillingDate,
                autoRenew = sub.autoRenew,
                accountId = sub.accountId,
                accountName = sub.accountName
            )
        }
    }

    suspend fun update(context: Context, payload: JSONObject) = withContext(Dispatchers.IO) {
        val action = payload.optString("action")
        val billKey = payload.optString("bill_key")
        val month = payload.optString("month")

        if (action == "link_account" || payload.has("account_id") || payload.has("account_name")) {
            val accId = if (payload.has("account_id") && !payload.isNull("account_id")) payload.optInt("account_id") else null
            val accName = if (payload.has("account_name") && !payload.isNull("account_name")) payload.optString("account_name").takeIf { it != "null" && it.isNotBlank() } else null
            if (billKey.isNotBlank()) {
                updateBillAccount(context, billKey, accId, accName)
            }
        }

        if (payload.has("transaction_id") && billKey.isNotBlank()) {
            val txId = payload.optLong("transaction_id")
            val unlink = payload.optBoolean("unlink", false)
            val refund = payload.optBoolean("refund", false)
            if (unlink) {
                removePaymentLink(context, billKey, txId)
            } else {
                addPaymentLink(context, billKey, txId, refund, month)
            }
        }

        if (action == "bill") {
            if (payload.has("amount")) {
                val amtStr = payload.optString("amount")
                val amt = amtStr.toDoubleOrNull() ?: payload.optDouble("amount", -1.0)
                if (amt >= 0.0 && billKey.isNotBlank()) {
                    updateBillAmount(context, billKey, amt)
                }
            }
            if (payload.optBoolean("cancelled", false) && billKey.isNotBlank()) {
                if (billKey.startsWith("sub_")) {
                    billKey.removePrefix("sub_").toIntOrNull()?.let { subId ->
                        LocalLedgerRepository.deleteSubscription(context, subId)
                    }
                }
            }
        }

        // 清除现金流报告缓存以触发本地重新计算并即时生效
        context.getSharedPreferences("cashflow_reports", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

