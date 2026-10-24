package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.parser.LocalParsedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object LocalLedgerRepository {
    private const val TAG = "LocalLedgerRepository"
    private val initMutex = Mutex()

    suspend fun initDefaultsIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            try {
                val db = AppDatabase.getInstance(context)
                val existingAccounts = db.localAccountDao().getAll()
                if (existingAccounts.isEmpty()) {
                    val defaults = listOf(
                        LocalAccount(name = "现金", type = "cash", balance = 0.0, orderNum = 1),
                        LocalAccount(name = "Touch 'n Go", type = "e_wallet", balance = 0.0, orderNum = 2),
                        LocalAccount(name = "Maybank / MAE", type = "savings", balance = 0.0, orderNum = 3),
                        LocalAccount(name = "CIMB Bank", type = "savings", balance = 0.0, orderNum = 4),
                        LocalAccount(name = "GrabPay", type = "e_wallet", balance = 0.0, orderNum = 5),
                        LocalAccount(name = "信用卡", type = "credit_card", balance = 0.0, creditLimit = 5000.0, orderNum = 6)
                    )
                    db.localAccountDao().insertAll(defaults)
                } else {
                    val seenNames = mutableSetOf<String>()
                    for (acc in existingAccounts) {
                        if (!seenNames.add(acc.name)) {
                            db.localAccountDao().deleteById(acc.id)
                        }
                    }
                }

                val existingCategories = db.localCategoryDao().getAll()
                if (existingCategories.isEmpty()) {
                    val defaults = listOf(
                        LocalCategory(name = "餐饮", type = "expense", icon = "🍔", color = "#fb923c", orderNum = 1),
                        LocalCategory(name = "交通", type = "expense", icon = "🚗", color = "#38bdf8", orderNum = 2),
                        LocalCategory(name = "杂货", type = "expense", icon = "🛒", color = "#34d399", orderNum = 3),
                        LocalCategory(name = "购物", type = "expense", icon = "🛍️", color = "#f472b6", orderNum = 4),
                        LocalCategory(name = "账单", type = "expense", icon = "💡", color = "#818cf8", orderNum = 5),
                        LocalCategory(name = "娱乐", type = "expense", icon = "🎮", color = "#a78bfa", orderNum = 6),
                        LocalCategory(name = "医疗", type = "expense", icon = "💊", color = "#f87171", orderNum = 7),
                        LocalCategory(name = "其他", type = "expense", icon = "🏷️", color = "#94a3b8", orderNum = 8),
                        LocalCategory(name = "工资", type = "income", icon = "💰", color = "#10b981", orderNum = 9),
                        LocalCategory(name = "兼职", type = "income", icon = "💼", color = "#06b6d4", orderNum = 10),
                        LocalCategory(name = "理财", type = "income", icon = "📈", color = "#6366f1", orderNum = 11),
                        LocalCategory(name = "其他收入", type = "income", icon = "🏷️", color = "#64748b", orderNum = 12)
                    )
                    db.localCategoryDao().insertAll(defaults)
                } else {
                    val seenCats = mutableSetOf<String>()
                    for (cat in existingCategories) {
                        if (!seenCats.add("${cat.name}_${cat.type}")) {
                            db.localCategoryDao().deleteById(cat.id)
                        }
                    }
                }

                // 自动将以往积攒在本地离线表里的待处理通知解析并补录进 local_transactions
                val pendingNotifs = db.pendingNotificationDao().getAllNotifications()
                if (pendingNotifs.isNotEmpty()) {
                    Log.i(TAG, "Importing ${pendingNotifs.size} pending notifications into local ledger...")
                    for (p in pendingNotifs) {
                        val parsed = com.siangloh.ledger.parser.LocalNotificationParser.parse(p.rawText, p.packageName)
                        if (parsed != null && parsed.amount > 0 && !parsed.isPromo && !parsed.isInternalTransfer) {
                            internalRecordNotification(context, db, parsed)
                        }
                        db.pendingNotificationDao().delete(p.id)
                    }
                }

                // 迁移以往从服务器缓存保存在本地 SharedPreferences 里的历史交易记录到 local_transactions (仅首次迁移一次)
                val spMigration = context.getSharedPreferences("ledger_migration_flags", Context.MODE_PRIVATE)
                val alreadyMigrated = spMigration.getBoolean("historical_cache_migrated_v1", false)
                if (!alreadyMigrated) {
                    try {
                        val spRecords = context.getSharedPreferences("ledger_records_cache", Context.MODE_PRIVATE)
                        val allEntries = spRecords.all
                        if (allEntries.isNotEmpty()) {
                            val existingTx = db.localTransactionDao().getAll()
                            val existingKeys = existingTx.map { "${it.date}_${it.type}_${it.amount}_${it.note}" }.toMutableSet()
                            val toInsert = mutableListOf<LocalTransaction>()

                            for ((key, value) in allEntries) {
                                if (key.startsWith("ts_")) continue
                                val jsonStr = value as? String ?: continue
                                try {
                                    val root = JSONObject(jsonStr)
                                    val itemsArr = root.optJSONArray("items") ?: continue
                                    for (i in 0 until itemsArr.length()) {
                                        val it = itemsArr.optJSONObject(i) ?: continue
                                        val d = it.optString("date", "")
                                        val t = it.optString("type", "expense")
                                        val amt = it.optDouble("amount", 0.0)
                                        val note = it.optString("note", "")
                                        val cat = it.optString("category", "其他")
                                        val channel = it.optString("channel").takeIf { ch -> ch.isNotBlank() && ch != "null" }
                                        val src = it.optString("source", "manual")
                                        val dedupeKey = "${d}_${t}_${amt}_${note}"
                                        if (amt > 0 && d.isNotBlank() && !existingKeys.contains(dedupeKey)) {
                                            existingKeys.add(dedupeKey)
                                            toInsert.add(
                                                LocalTransaction(
                                                    date = d,
                                                    time = it.optString("time", "12:00:00"),
                                                    type = t,
                                                    category = cat,
                                                    amount = amt,
                                                    note = note,
                                                    tags = it.optString("tags", ""),
                                                    accountId = it.optInt("accountId", 0).takeIf { a -> a > 0 },
                                                    source = src,
                                                    channel = channel,
                                                    createdAt = System.currentTimeMillis(),
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            if (toInsert.isNotEmpty()) {
                                Log.i(TAG, "Migrated ${toInsert.size} historical cached transactions into local_transactions!")
                                db.localTransactionDao().insertAll(toInsert)

                                // 自动补充历史遗留分类
                                val allCats = db.localCategoryDao().getAll().map { it.name }.toMutableSet()
                                val newCats = toInsert.map { it.category }.distinct().filter { it.isNotBlank() && !allCats.contains(it) }
                                if (newCats.isNotEmpty()) {
                                    val newCatEntities = newCats.mapIndexed { idx, name ->
                                        LocalCategory(name = name, type = "expense", icon = "🏷️", color = "#94a3b8", orderNum = 20 + idx)
                                    }
                                    db.localCategoryDao().insertAll(newCatEntities)
                                }
                            }
                            // 迁移完成后清空旧缓存并标记已完成迁移，防止删除记录后被重新还原
                            spRecords.edit().clear().apply()
                        }
                        spMigration.edit().putBoolean("historical_cache_migrated_v1", true).apply()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate historical cache: ${e.message}")
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize default accounts/categories: ${e.message}")
            }
        }
    }

    private suspend fun internalRecordNotification(context: Context, db: AppDatabase, parsed: LocalParsedResult): Long {
        val accounts = db.localAccountDao().getAll()

        val matchedAccount = when {
            parsed.channel.contains("Touch", ignoreCase = true) || parsed.channel.contains("tng", ignoreCase = true) -> accounts.find { it.name.contains("Touch", ignoreCase = true) || it.name.contains("tng", ignoreCase = true) }
            parsed.channel.contains("Grab", ignoreCase = true) -> accounts.find { it.name.contains("Grab", ignoreCase = true) }
            parsed.channel.contains("Maybank", ignoreCase = true) || parsed.channel.contains("MAE", ignoreCase = true) -> accounts.find { it.name.contains("Maybank", ignoreCase = true) || it.name.contains("MAE", ignoreCase = true) }
            parsed.channel.contains("CIMB", ignoreCase = true) -> accounts.find { it.name.contains("CIMB", ignoreCase = true) }
            else -> accounts.firstOrNull()
        } ?: accounts.firstOrNull()

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val tx = LocalTransaction(
            date = parsed.date,
            time = timeStr,
            type = parsed.type,
            category = parsed.category,
            amount = parsed.amount,
            note = parsed.merchant,
            tags = "自动记账",
            accountId = matchedAccount?.id,
            source = "auto_notification",
            channel = parsed.channel,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val id = db.localTransactionDao().insert(tx)

        if (matchedAccount != null) {
            val delta = if (parsed.type == "income") parsed.amount else -parsed.amount
            db.localAccountDao().updateBalance(matchedAccount.id, delta)
        }

        NativeLedgerApi.invalidateCache()
        return id
    }

    suspend fun recordNotificationTransaction(context: Context, parsed: LocalParsedResult): Long = withContext(Dispatchers.IO) {
        initDefaultsIfNeeded(context)
        val db = AppDatabase.getInstance(context)
        internalRecordNotification(context, db, parsed)
    }

    suspend fun createTransaction(
        context: Context,
        date: String,
        type: String,
        category: String,
        amount: Double,
        note: String,
        tags: String = "",
        accountId: Int? = null,
        toAccountId: Int? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val tx = LocalTransaction(
                date = date,
                time = timeStr,
                type = type,
                category = category,
                amount = amount,
                note = note,
                tags = tags,
                accountId = accountId,
                toAccountId = toAccountId,
                source = "manual",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val id = db.localTransactionDao().insert(tx)

            if (accountId != null) {
                val delta = if (type == "income") amount else -amount
                db.localAccountDao().updateBalance(accountId, delta)
            }
            if (type == "transfer" && toAccountId != null) {
                db.localAccountDao().updateBalance(toAccountId, amount)
            }

            NativeLedgerApi.invalidateCache()
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "createTransaction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateTransaction(
        context: Context,
        id: Long,
        date: String,
        type: String,
        category: String,
        amount: Double,
        note: String,
        tags: String = "",
        accountId: Int? = null,
        toAccountId: Int? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val old = db.localTransactionDao().getById(id) ?: return@withContext Result.failure(Exception("交易不存在"))

            // 1. 恢复旧余额
            if (old.accountId != null) {
                val oldRevert = if (old.type == "income") -old.amount else old.amount
                db.localAccountDao().updateBalance(old.accountId, oldRevert)
            }
            if (old.type == "transfer" && old.toAccountId != null) {
                db.localAccountDao().updateBalance(old.toAccountId, -old.amount)
            }

            // 2. 更新交易
            val updated = old.copy(
                date = date,
                type = type,
                category = category,
                amount = amount,
                note = note,
                tags = tags,
                accountId = accountId,
                toAccountId = toAccountId,
                updatedAt = System.currentTimeMillis()
            )
            db.localTransactionDao().update(updated)

            // 3. 应用新余额
            if (accountId != null) {
                val newDelta = if (type == "income") amount else -amount
                db.localAccountDao().updateBalance(accountId, newDelta)
            }
            if (type == "transfer" && toAccountId != null) {
                db.localAccountDao().updateBalance(toAccountId, amount)
            }

            NativeLedgerApi.invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTransaction(context: Context, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val old = db.localTransactionDao().getById(id)

            if (old != null) {
                if (old.accountId != null) {
                    val revert = if (old.type == "income") -old.amount else old.amount
                    db.localAccountDao().updateBalance(old.accountId, revert)
                }
                if (old.type == "transfer" && old.toAccountId != null) {
                    db.localAccountDao().updateBalance(old.toAccountId, -old.amount)
                }
                db.localTransactionDao().deleteById(id)
            }

            try {
                db.pendingTransactionDao().deleteById(id)
            } catch (_: Exception) {}

            CashflowRepository.invalidate(context)
            NativeLedgerApi.invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTransactionCategory(context: Context, id: Long, category: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            db.localTransactionDao().updateCategory(id, category)
            NativeLedgerApi.invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAccountsSummary(context: Context): Result<AccountsSummary> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val localAccs = db.localAccountDao().getAll()

            val accountRecords = localAccs.map { a ->
                AccountRecord(
                    id = a.id,
                    name = a.name,
                    type = a.type,
                    balance = a.balance,
                    creditLimit = a.creditLimit,
                    currency = a.currency,
                    statementDay = null,
                    dueDay = null
                )
            }

            var assets = 0.0
            var liabilities = 0.0

            accountRecords.forEach { a ->
                if (a.type == "credit_card") {
                    if (a.balance < 0) liabilities += -a.balance
                } else {
                    if (a.balance >= 0) assets += a.balance else liabilities += -a.balance
                }
            }

            val summary = AccountsSummary(
                totalAssets = assets,
                totalLiabilities = liabilities,
                netWorth = assets - liabilities,
                accounts = accountRecords,
                defaultAccountId = accountRecords.firstOrNull()?.id
            )
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategoriesResult(context: Context): Result<CategoriesResult> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val all = db.localCategoryDao().getAll()

            val expenses = all.filter { it.type == "expense" }.map { CategoryRecord(it.id, it.name, it.type) }
            val incomes = all.filter { it.type == "income" }.map { CategoryRecord(it.id, it.name, it.type) }
            val savings = all.filter { it.type == "savings" }.map { CategoryRecord(it.id, it.name, it.type) }

            Result.success(CategoriesResult(expenses, incomes, savings))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTransactionsPage(
        context: Context,
        month: String? = null,
        type: String? = null,
        search: String? = null,
        page: Int = 1,
        limit: Int = 30
    ): Result<TransactionsPage> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val accountsMap = db.localAccountDao().getAll().associateBy { it.id }

            var list = if (!month.isNullOrBlank()) {
                db.localTransactionDao().getByMonth("$month%")
            } else {
                db.localTransactionDao().getAll()
            }

            if (!type.isNullOrBlank() && type != "all") {
                list = list.filter { it.type == type }
            }

            if (!search.isNullOrBlank()) {
                val q = search.trim().lowercase(Locale.ROOT)
                list = list.filter {
                    it.note.lowercase(Locale.ROOT).contains(q) ||
                    it.category.lowercase(Locale.ROOT).contains(q) ||
                    it.tags.lowercase(Locale.ROOT).contains(q)
                }
            }

            val totalCount = list.size
            val offset = (page - 1) * limit
            val paged = if (offset < list.size) {
                list.subList(offset, Math.min(offset + limit, list.size))
            } else {
                emptyList()
            }

            val items = paged.map { tx ->
                val acc = tx.accountId?.let { accountsMap[it] }
                TransactionRecord(
                    id = tx.id,
                    date = tx.date,
                    type = tx.type,
                    category = tx.category,
                    amount = tx.amount,
                    note = tx.note,
                    tags = tx.tags,
                    accountId = tx.accountId,
                    accountName = acc?.name ?: "默认账户",
                    accountType = acc?.type ?: "savings",
                    source = tx.source,
                    channel = tx.channel,
                    sourceLabel = if (tx.source == "auto_notification") "自动记账" else "手动记账",
                    channelLabel = tx.channel
                )
            }

            Result.success(
                TransactionsPage(
                    items = items,
                    totalCount = totalCount,
                    page = page,
                    limit = limit,
                    hasMore = (offset + limit) < totalCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOverviewJson(context: Context, month: String? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val currentMonth = month ?: SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val db = AppDatabase.getInstance(context)
            val txList = db.localTransactionDao().getByMonth("$currentMonth%")
            val accounts = db.localAccountDao().getAll()

            var totalExp = 0.0
            var totalInc = 0.0
            val catExpMap = mutableMapOf<String, Double>()

            val dailyExpMap = mutableMapOf<String, Double>()
            txList.forEach { tx ->
                if (tx.type == "expense") {
                    totalExp += tx.amount
                    catExpMap[tx.category] = (catExpMap[tx.category] ?: 0.0) + tx.amount
                    dailyExpMap[tx.date] = (dailyExpMap[tx.date] ?: 0.0) + tx.amount
                } else if (tx.type == "income") {
                    totalInc += tx.amount
                }
            }

            val net = totalInc - totalExp

            val topCatsArray = JSONArray()
            catExpMap.entries.sortedByDescending { it.value }.take(8).forEach { (cat, amt) ->
                val pct = if (totalExp > 0) (amt / totalExp * 100).toInt() else 0
                topCatsArray.put(JSONObject().apply {
                    put("category", cat)
                    put("amount", amt)
                    put("percentage", pct)
                })
            }

            val sparklineArray = JSONArray()
            dailyExpMap.entries.sortedBy { it.key }.forEach { (date, amt) ->
                sparklineArray.put(JSONObject().apply {
                    put("date", date)
                    put("amount", amt)
                })
            }

            val recentArray = JSONArray()
            txList.take(20).forEach { tx ->
                recentArray.put(JSONObject().apply {
                    put("id", tx.id)
                    put("date", tx.date)
                    put("type", tx.type)
                    put("category", tx.category)
                    put("amount", tx.amount)
                    put("note", tx.note)
                    put("channel", tx.channel ?: "")
                    put("source", tx.source)
                    put("badge_text", if (tx.source == "auto_notification") "自动记账" else "手动记账")
                    put("badge_color", if (tx.source == "auto_notification") "#0d9488" else "#64748b")
                })
            }

            var totalNetWorth = 0.0
            accounts.forEach {
                totalNetWorth += if (it.type == "credit_card") it.balance else it.balance
            }

            val listeningStatus = JSONObject().apply {
                put("last_active_time", "刚刚")
            }

            val json = JSONObject().apply {
                put("ok", true)
                put("month", currentMonth)
                put("currency", "RM")
                put("income", totalInc)
                put("expense", totalExp)
                put("total_spending", totalExp)
                put("expense_amount", totalExp)
                put("transfer_amount", 0.0)
                put("uncategorized_count", 0)
                put("uncategorized_amount", 0.0)
                put("net", net)
                put("balance", net)
                put("net_worth", totalNetWorth)
                put("categories", topCatsArray)
                put("top_categories", topCatsArray)
                put("sparkline", sparklineArray)
                put("recent_transactions", recentArray)
                put("listening_status", listeningStatus)
                put("transaction_count", txList.size)
            }

            Result.success(json)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addOrUpdateAccount(
        context: Context,
        id: Int? = null,
        name: String,
        type: String,
        balance: Double,
        creditLimit: Double = 0.0,
        currency: String = "RM"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            if (id != null && id > 0) {
                val acc = db.localAccountDao().getById(id)
                if (acc != null) {
                    db.localAccountDao().update(acc.copy(name = name, type = type, balance = balance, creditLimit = creditLimit, currency = currency))
                }
            } else {
                db.localAccountDao().insert(LocalAccount(name = name, type = type, balance = balance, creditLimit = creditLimit, currency = currency))
            }
            NativeLedgerApi.invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
