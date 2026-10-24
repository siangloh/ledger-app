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

        notifyDataChanged(context)
        return id
    }

    fun notifyDataChanged(context: Context) {
        try {
            LocalDataStore.clearOverviewCache(context)
            CashflowRepository.invalidate(context)
            NativeLedgerApi.invalidateCache()
            val intent = android.content.Intent("com.siangloh.ledger.DATA_SYNCED").setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify data changed: ${e.message}")
        }
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

            notifyDataChanged(context)
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

            notifyDataChanged(context)
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
                // Save to local recycle bin before deleting
                try {
                    val sp = context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
                    val existing = sp.getString("trash_items", "[]") ?: "[]"
                    val trashArr = JSONArray(existing)
                    trashArr.put(JSONObject().apply {
                        put("id", old.id)
                        put("date", old.date)
                        put("time", old.time)
                        put("type", old.type)
                        put("category", old.category)
                        put("amount", old.amount)
                        put("note", old.note)
                        put("tags", old.tags)
                        put("account_id", old.accountId ?: 0)
                        put("source", old.source)
                        put("deleted_at", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                    })
                    sp.edit().putString("trash_items", trashArr.toString()).apply()
                } catch (_: Exception) {}

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

            notifyDataChanged(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreTransaction(context: Context, record: TransactionRecord): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val entity = LocalTransaction(
                id = record.id,
                date = record.date,
                time = "",
                type = record.type,
                category = record.category,
                amount = record.amount,
                note = record.note,
                tags = record.tags,
                accountId = record.accountId,
                source = record.source,
                channel = record.channel
            )
            db.localTransactionDao().insert(entity)
            if (record.accountId != null) {
                val delta = if (record.type == "income") record.amount else -record.amount
                db.localAccountDao().updateBalance(record.accountId, delta)
            }
            notifyDataChanged(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTransactionCategory(context: Context, id: Long, category: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            db.localTransactionDao().updateCategory(id, category)
            notifyDataChanged(context)
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
            val allTxs = db.localTransactionDao().getAll()

            val accountRecords = localAccs.map { a ->
                val unreconciled = allTxs.count {
                    (it.accountId == a.id || it.toAccountId == a.id) && !it.tags.contains("#reconciled")
                }
                AccountRecord(
                    id = a.id,
                    name = a.name,
                    type = a.type,
                    balance = a.balance,
                    creditLimit = a.creditLimit,
                    currency = a.currency,
                    statementDay = null,
                    dueDay = null,
                    unreconciledCount = unreconciled
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

            if (!category.isNullOrBlank() && category != "全部" && category != "all") {
                list = list.filter { it.category == category }
            }

            if (accountId != null) {
                list = list.filter { it.accountId == accountId || it.toAccountId == accountId }
            }

            if (!startDate.isNullOrBlank()) {
                list = list.filter { it.date >= startDate }
            }

            if (!endDate.isNullOrBlank()) {
                list = list.filter { it.date <= endDate }
            }

            if (minAmount != null) {
                list = list.filter { it.amount >= minAmount }
            }

            if (maxAmount != null) {
                list = list.filter { it.amount <= maxAmount }
            }

            if (!source.isNullOrBlank() && source != "all") {
                list = list.filter { it.source == source || it.source.contains(source) }
            }

            if (!channel.isNullOrBlank() && channel != "all") {
                list = list.filter { it.channel == channel }
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

    suspend fun getAccountReconciliation(context: Context, accountId: Int): Result<AccountReconciliationData> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val acc = db.localAccountDao().getById(accountId) ?: return@withContext Result.failure(Exception("账户未找到"))
            val txs = db.localTransactionDao().getByAccountId(accountId)

            val txItems = txs.map { tx ->
                ReconcileTxItem(
                    id = tx.id.toInt(),
                    date = tx.date,
                    type = tx.type,
                    category = tx.category,
                    amount = tx.amount,
                    note = tx.note,
                    isReconciled = tx.tags.contains("#reconciled")
                )
            }

            val unreconciledCount = txItems.count { !it.isReconciled }

            val data = AccountReconciliationData(
                accountId = acc.id,
                accountName = acc.name,
                accountType = acc.type,
                currency = acc.currency,
                systemBalance = acc.balance,
                unreconciledCount = unreconciledCount,
                transactions = txItems,
                history = emptyList()
            )
            Result.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "getAccountReconciliation error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun reconcileAccount(
        context: Context,
        accountId: Int,
        statementBalance: Double,
        autoAdjust: Boolean,
        note: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val acc = db.localAccountDao().getById(accountId) ?: return@withContext Result.failure(Exception("账户未找到"))

            val diff = statementBalance - acc.balance
            if (autoAdjust && kotlin.math.abs(diff) >= 0.005) {
                val now = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val adjType = if (diff > 0) "income" else "expense"
                val adjAmount = kotlin.math.abs(diff)
                val adjCategory = "平账调整"
                val adjNote = if (note.isNotBlank()) "对账平账: $note" else "对账差额平账"

                val adjTx = LocalTransaction(
                    date = now,
                    time = timeNow,
                    type = adjType,
                    category = adjCategory,
                    amount = adjAmount,
                    note = adjNote,
                    tags = "#reconciled #calibration",
                    accountId = accountId,
                    source = "reconciliation"
                )
                db.localTransactionDao().insert(adjTx)
            }

            // Mark all existing transactions for this account as reconciled
            val txs = db.localTransactionDao().getByAccountId(accountId)
            for (t in txs) {
                if (!t.tags.contains("#reconciled")) {
                    val updatedTags = if (t.tags.isBlank()) "#reconciled" else "${t.tags.trim()} #reconciled"
                    db.localTransactionDao().update(t.copy(tags = updatedTags, updatedAt = System.currentTimeMillis()))
                }
            }

            // Update account balance to statement balance
            db.localAccountDao().update(acc.copy(balance = statementBalance))
            NativeLedgerApi.invalidateCache()

            val json = JSONObject().apply {
                put("ok", true)
                put("final_balance", statementBalance)
                put("message", "对账成功")
            }
            Result.success(json)
        } catch (e: Exception) {
            Log.e(TAG, "reconcileAccount error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun toggleTransactionReconciled(
        context: Context,
        txId: Int,
        isReconciled: Boolean
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val tx = db.localTransactionDao().getById(txId.toLong())
            if (tx != null) {
                val newTags = if (isReconciled) {
                    if (!tx.tags.contains("#reconciled")) "${tx.tags.trim()} #reconciled".trim() else tx.tags
                } else {
                    tx.tags.replace("#reconciled", "").trim()
                }
                db.localTransactionDao().update(tx.copy(tags = newTags, updatedAt = System.currentTimeMillis()))
            }
            Result.success(JSONObject().put("ok", true))
        } catch (e: Exception) {
            Log.e(TAG, "toggleTransactionReconciled error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getToolsSummary(context: Context): Result<ToolsSummary> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("local_tools_prefs", Context.MODE_PRIVATE)
            val subsJson = sp.getString("subscriptions_json", null)
            val instJson = sp.getString("installments_json", null)

            val subscriptions = mutableListOf<SubscriptionRecord>()
            if (!subsJson.isNullOrBlank()) {
                val arr = JSONArray(subsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    subscriptions.add(
                        SubscriptionRecord(
                            id = obj.optInt("id", i + 1),
                            name = obj.optString("name", "周期订阅"),
                            category = obj.optString("category", "账单"),
                            cost = obj.optDouble("cost", 0.0),
                            currency = obj.optString("currency", "MYR"),
                            nextBillingDate = obj.optString("next_billing_date", "每月1号"),
                            autoRenew = obj.optBoolean("auto_renew", true)
                        )
                    )
                }
            } else {
                val defaults = listOf(
                    SubscriptionRecord(1, "Netflix 会员", "娱乐", 44.90, "MYR", "每月15号", true),
                    SubscriptionRecord(2, "Spotify 音乐", "娱乐", 15.90, "MYR", "每月20号", true),
                    SubscriptionRecord(3, "iCloud 存储", "日常", 11.90, "MYR", "每月1号", true),
                    SubscriptionRecord(4, "家庭宽带 Unifi", "账单", 139.00, "MYR", "每月10号", true),
                    SubscriptionRecord(5, "TNB 电费账单", "账单", 120.00, "MYR", "每月25号", true)
                )
                subscriptions.addAll(defaults)
                val arr = JSONArray()
                defaults.forEach { sub ->
                    arr.put(JSONObject().apply {
                        put("id", sub.id)
                        put("name", sub.name)
                        put("category", sub.category)
                        put("cost", sub.cost)
                        put("currency", sub.currency)
                        put("next_billing_date", sub.nextBillingDate)
                        put("auto_renew", sub.autoRenew)
                    })
                }
                sp.edit().putString("subscriptions_json", arr.toString()).apply()
            }

            val installments = mutableListOf<InstallmentRecord>()
            if (!instJson.isNullOrBlank()) {
                val arr = JSONArray(instJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    installments.add(
                        InstallmentRecord(
                            id = obj.optInt("id", i + 1),
                            title = obj.optString("title", "分期付款"),
                            totalAmount = obj.optDouble("total_amount", 0.0),
                            tenureMonths = obj.optInt("tenure_months", 12),
                            paidPeriods = obj.optInt("paid_periods", 0),
                            monthlyAmount = obj.optDouble("monthly_amount", 0.0),
                            remainingAmount = obj.optDouble("remaining_amount", 0.0)
                        )
                    )
                }
            } else {
                val defaults = listOf(
                    InstallmentRecord(1, "0% 免息电子产品分期", 3600.0, 12, 4, 300.0, 2400.0)
                )
                installments.addAll(defaults)
                val arr = JSONArray()
                defaults.forEach { inst ->
                    arr.put(JSONObject().apply {
                        put("id", inst.id)
                        put("title", inst.title)
                        put("total_amount", inst.totalAmount)
                        put("tenure_months", inst.tenureMonths)
                        put("paid_periods", inst.paidPeriods)
                        put("monthly_amount", inst.monthlyAmount)
                        put("remaining_amount", inst.remainingAmount)
                    })
                }
                sp.edit().putString("installments_json", arr.toString()).apply()
            }

            val totalMonthly = subscriptions.sumOf { it.cost }
            val totalRemaining = installments.sumOf { it.remainingAmount }

            Result.success(
                ToolsSummary(
                    subscriptions = subscriptions,
                    totalMonthlySub = totalMonthly,
                    installments = installments,
                    totalRemainingInst = totalRemaining
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getToolsSummary error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getSubscriptionMatches(context: Context): Result<List<TransactionMatchRecord>> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val txs = db.localTransactionDao().getAll().take(50)
            val tools = getToolsSummary(context).getOrNull()
            val subs = tools?.subscriptions ?: emptyList()

            val matches = mutableListOf<TransactionMatchRecord>()
            for (sub in subs) {
                val matchedTx = txs.firstOrNull {
                    it.type == "expense" &&
                    (kotlin.math.abs(it.amount - sub.cost) < 1.0 || it.note.contains(sub.name, ignoreCase = true))
                }
                matches.add(
                    TransactionMatchRecord(
                        txId = matchedTx?.id ?: 0L,
                        txDate = matchedTx?.date ?: "",
                        txAmount = matchedTx?.amount ?: sub.cost,
                        txCategory = matchedTx?.category ?: sub.category,
                        txNote = matchedTx?.note ?: sub.name,
                        matchedSubId = sub.id,
                        matchedSubName = sub.name,
                        matchedSubCost = sub.cost,
                        matchedSubNextBilling = sub.nextBillingDate
                    )
                )
            }
            Result.success(matches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applySubscriptionMatch(context: Context, subId: Int, txId: Long?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (txId != null) {
                val db = AppDatabase.getInstance(context)
                val tx = db.localTransactionDao().getById(txId)
                if (tx != null && !tx.tags.contains("#subscription")) {
                    val newTags = if (tx.tags.isBlank()) "#subscription" else "${tx.tags.trim()} #subscription"
                    db.localTransactionDao().update(tx.copy(tags = newTags, updatedAt = System.currentTimeMillis()))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applyInstallmentMatch(context: Context, instId: Int, txId: Long?): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (txId != null) {
                val db = AppDatabase.getInstance(context)
                val tx = db.localTransactionDao().getById(txId)
                if (tx != null && !tx.tags.contains("#installment")) {
                    val newTags = if (tx.tags.isBlank()) "#installment" else "${tx.tags.trim()} #installment"
                    db.localTransactionDao().update(tx.copy(tags = newTags, updatedAt = System.currentTimeMillis()))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBudgetOverview(context: Context, month: String? = null): Result<BudgetOverviewRecord> = withContext(Dispatchers.IO) {
        try {
            initDefaultsIfNeeded(context)
            val db = AppDatabase.getInstance(context)
            val targetMonth = if (!month.isNullOrBlank()) month else SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

            val cal = Calendar.getInstance()
            val totalDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val passedDays = cal.get(Calendar.DAY_OF_MONTH)
            val remainingDays = Math.max(0, totalDays - passedDays)

            val sp = context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
            val overallLimit = sp.getFloat("overall_limit", 3000f).toDouble()
            val isExplicit = sp.getBoolean("overall_explicit", true)
            val isSet = sp.getBoolean("overall_set", true)
            val startDay = sp.getInt("start_day", 1)

            val txList = db.localTransactionDao().getByMonth("$targetMonth%")
            var totalSpent = 0.0
            val catSpentMap = mutableMapOf<String, Double>()

            for (tx in txList) {
                if (tx.type == "expense") {
                    totalSpent += tx.amount
                    catSpentMap[tx.category] = (catSpentMap[tx.category] ?: 0.0) + tx.amount
                }
            }

            val remaining = Math.max(0.0, overallLimit - totalSpent)
            val pct = if (overallLimit > 0) totalSpent / overallLimit * 100.0 else 0.0
            val level = when {
                pct >= 100.0 -> "danger"
                pct >= 80.0 -> "warning"
                else -> "safe"
            }
            val allowance = if (remainingDays > 0) remaining / remainingDays else 0.0

            val cycle = BillingCycleRecord(
                startDate = "$targetMonth-01",
                endDate = String.format(Locale.getDefault(), "%s-%02d", targetMonth, totalDays),
                label = "$targetMonth 账单周期",
                startDay = startDay,
                totalDays = totalDays,
                passedDays = passedDays,
                remainingDays = remainingDays
            )

            val overall = OverallBudgetRecord(
                limit = overallLimit,
                spent = totalSpent,
                remaining = remaining,
                pct = pct,
                level = level,
                dailyAllowance = allowance,
                isExplicit = isExplicit,
                isSet = isSet
            )

            val categories = db.localCategoryDao().getAll().filter { it.type == "expense" }
            val catItems = mutableListOf<BudgetItemRecord>()
            var overCount = 0
            var warnCount = 0
            var okCount = 0

            for (c in categories) {
                val spent = catSpentMap[c.name] ?: 0.0
                val catLimit = sp.getFloat("cat_limit_${c.name}", 0f).toDouble()
                val catPct = if (catLimit > 0) spent / catLimit * 100.0 else 0.0
                val catLevel = when {
                    catLimit <= 0.0 -> "ok"
                    catPct >= 100.0 -> { overCount++; "over" }
                    catPct >= 80.0 -> { warnCount++; "warn" }
                    else -> { okCount++; "ok" }
                }

                catItems.add(
                    BudgetItemRecord(
                        category = c.name,
                        limit = catLimit,
                        spent = spent,
                        remaining = Math.max(0.0, catLimit - spent),
                        pct = catPct,
                        level = catLevel,
                        color = c.color ?: "#0F766E"
                    )
                )
            }

            Result.success(
                BudgetOverviewRecord(
                    month = targetMonth,
                    billingCycle = cycle,
                    overall = overall,
                    categories = catItems,
                    totalCategoriesBudgeted = catItems.count { it.limit > 0 },
                    overCount = overCount,
                    warnCount = warnCount,
                    okCount = okCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getBudgetOverview error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun saveOverallBudget(context: Context, limit: Double, billingCycleStartDay: Int = 1): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putFloat("overall_limit", limit.toFloat())
                .putInt("start_day", billingCycleStartDay)
                .putBoolean("overall_set", true)
                .putBoolean("overall_explicit", true)
                .apply()
            Result.success(JSONObject().put("ok", true).put("message", "总预算保存成功"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveCategoryBudget(context: Context, category: String, limit: Double): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putFloat("cat_limit_$category", limit.toFloat())
                .apply()
            Result.success(JSONObject().put("ok", true).put("message", "分类预算保存成功"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCategoryBudget(context: Context, category: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("cat_limit_$category")
                .apply()
            Result.success(JSONObject().put("ok", true).put("message", "分类预算已删除"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategoryRules(
        context: Context,
        search: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<CategoryRuleRecord>> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("local_category_rules_prefs", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("rules_json", null)
            val list = mutableListOf<CategoryRuleRecord>()
            if (!jsonStr.isNullOrBlank()) {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pattern = obj.optString("merchant_pattern", "")
                    val category = obj.optString("category", "其他")
                    val matchType = obj.optString("match_type", "contains")
                    val applyCount = obj.optInt("apply_count", 0)
                    val isActive = obj.optBoolean("is_active", true)
                    val createdAt = obj.optString("created_at", "")
                    val updatedAt = obj.optString("updated_at", "")
                    if (search.isNullOrBlank() || pattern.contains(search, ignoreCase = true) || category.contains(search, ignoreCase = true)) {
                        list.add(
                            CategoryRuleRecord(
                                merchantPattern = pattern,
                                category = category,
                                matchType = matchType,
                                applyCount = applyCount,
                                isActive = isActive,
                                createdAt = createdAt,
                                updatedAt = updatedAt
                            )
                        )
                    }
                }
            } else {
                val defaults = listOf(
                    CategoryRuleRecord("tng", "交通", "contains", 5, true, "2026-10-01", "2026-10-01"),
                    CategoryRuleRecord("grab", "交通", "contains", 3, true, "2026-10-01", "2026-10-01"),
                    CategoryRuleRecord("shopee", "购物", "contains", 4, true, "2026-10-01", "2026-10-01"),
                    CategoryRuleRecord("lazada", "购物", "contains", 2, true, "2026-10-01", "2026-10-01"),
                    CategoryRuleRecord("mcdonald", "餐饮", "contains", 6, true, "2026-10-01", "2026-10-01"),
                    CategoryRuleRecord("kfc", "餐饮", "contains", 3, true, "2026-10-01", "2026-10-01")
                )
                list.addAll(defaults)
                saveCategoryRulesList(context, defaults)
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveCategoryRulesList(context: Context, rules: List<CategoryRuleRecord>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("merchant_pattern", r.merchantPattern)
                put("category", r.category)
                put("match_type", r.matchType)
                put("is_active", r.isActive)
                put("apply_count", r.applyCount)
                put("created_at", r.createdAt ?: "")
                put("updated_at", r.updatedAt ?: "")
            })
        }
        context.getSharedPreferences("local_category_rules_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("rules_json", arr.toString())
            .apply()
    }

    suspend fun saveCategoryRule(
        context: Context,
        pattern: String,
        category: String,
        matchType: String = "contains",
        isActive: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val rules = getCategoryRules(context).getOrNull()?.toMutableList() ?: mutableListOf()
            val existingIdx = rules.indexOfFirst { it.merchantPattern.equals(pattern, ignoreCase = true) }
            val now = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (existingIdx >= 0) {
                val old = rules[existingIdx]
                rules[existingIdx] = old.copy(category = category, matchType = matchType, isActive = isActive, updatedAt = now)
            } else {
                rules.add(CategoryRuleRecord(pattern, category, matchType, 0, isActive, now, now))
            }
            saveCategoryRulesList(context, rules)
            Result.success(JSONObject().put("ok", true).put("message", "规则已保存"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCategoryRule(context: Context, pattern: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val rules = getCategoryRules(context).getOrNull()?.toMutableList() ?: mutableListOf()
            rules.removeAll { it.merchantPattern.equals(pattern, ignoreCase = true) }
            saveCategoryRulesList(context, rules)
            Result.success(JSONObject().put("ok", true).put("message", "规则已删除"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleCategoryRule(context: Context, pattern: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val rules = getCategoryRules(context).getOrNull()?.toMutableList() ?: mutableListOf()
            val idx = rules.indexOfFirst { it.merchantPattern.equals(pattern, ignoreCase = true) }
            if (idx >= 0) {
                val old = rules[idx]
                rules[idx] = old.copy(isActive = !old.isActive)
                saveCategoryRulesList(context, rules)
            }
            Result.success(JSONObject().put("ok", true).put("message", "状态已切换"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applyCategoryRuleHistory(
        context: Context,
        pattern: String,
        category: String,
        matchType: String = "contains",
        onlyUncategorized: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            var updatedCount = 0
            val db = AppDatabase.getInstance(context)
            val txs = db.localTransactionDao().getAll()
            for (tx in txs) {
                val matches = if (matchType == "exact") tx.note.equals(pattern, ignoreCase = true)
                              else tx.note.contains(pattern, ignoreCase = true)
                if (matches && (!onlyUncategorized || tx.category == "其他" || tx.category == "未分类" || tx.category.isBlank())) {
                    db.localTransactionDao().update(tx.copy(category = category, updatedAt = System.currentTimeMillis()))
                    updatedCount++
                }
            }
            NativeLedgerApi.invalidateCache()
            Result.success(JSONObject().put("ok", true).put("updated_count", updatedCount).put("message", "已对 $updatedCount 条历史流水生效"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applyAllCategoryRulesHistory(
        context: Context,
        onlyUncategorized: Boolean = true
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val rules = getCategoryRules(context).getOrNull()?.filter { it.isActive } ?: emptyList()
            var totalUpdated = 0
            val db = AppDatabase.getInstance(context)
            val txs = db.localTransactionDao().getAll()
            for (rule in rules) {
                for (tx in txs) {
                    val matches = if (rule.matchType == "exact") tx.note.equals(rule.merchantPattern, ignoreCase = true)
                                  else tx.note.contains(rule.merchantPattern, ignoreCase = true)
                    if (matches && (!onlyUncategorized || tx.category == "其他" || tx.category == "未分类" || tx.category.isBlank())) {
                        db.localTransactionDao().update(tx.copy(category = rule.category, updatedAt = System.currentTimeMillis()))
                        totalUpdated++
                    }
                }
            }
            NativeLedgerApi.invalidateCache()
            Result.success(JSONObject().put("ok", true).put("updated_count", totalUpdated).put("message", "全量规则应用成功，更新 $totalUpdated 笔历史记录"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecycleBinItems(context: Context): List<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("trash_items", "[]") ?: "[]"
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun restoreRecycleBinItem(context: Context, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("trash_items", "[]") ?: "[]"
            val arr = JSONArray(jsonStr)
            var target: JSONObject? = null
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optLong("id") == id) {
                    target = item
                } else {
                    newArr.put(item)
                }
            }
            sp.edit().putString("trash_items", newArr.toString()).apply()

            if (target != null) {
                val db = AppDatabase.getInstance(context)
                val tx = LocalTransaction(
                    id = target.optLong("id"),
                    date = target.optString("date"),
                    time = target.optString("time", "12:00:00"),
                    type = target.optString("type", "expense"),
                    category = target.optString("category", "其他"),
                    amount = target.optDouble("amount", 0.0),
                    note = target.optString("note", ""),
                    tags = target.optString("tags", ""),
                    accountId = if (target.has("account_id")) target.optInt("account_id") else null,
                    source = target.optString("source", "manual")
                )
                db.localTransactionDao().insert(tx)
                if (tx.accountId != null) {
                    val delta = if (tx.type == "income") tx.amount else -tx.amount
                    db.localAccountDao().updateBalance(tx.accountId, delta)
                }
                NativeLedgerApi.invalidateCache()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun permanentDeleteRecycleBinItem(context: Context, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
            val jsonStr = sp.getString("trash_items", "[]") ?: "[]"
            val arr = JSONArray(jsonStr)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optLong("id") != id) {
                    newArr.put(item)
                }
            }
            sp.edit().putString("trash_items", newArr.toString()).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun emptyRecycleBin(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
