package com.siangloh.ledger.data

import com.siangloh.ledger.core.format.daysInMonth
import com.siangloh.ledger.core.format.shiftMonth
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round

data class SharedOverviewStats(
    val period: String,
    val yearMonth: String,
    val income: Double,
    val expense: Double,
    val savings: Double,
    val netBalance: Double,
    val recentTransactions: List<LocalTransaction>
)

data class SharedAccountItem(
    val account: LocalAccount,
    val monthlyIn: Double,
    val monthlyOut: Double
)

data class SharedAccountsSummary(
    val totalAssets: Double,
    val totalLiabilities: Double,
    val netWorth: Double,
    val items: List<SharedAccountItem>
)

object SharedLedgerEngine {
    private val initMutex = Mutex()
    private val writeMutex = Mutex()
    private val categoryJsonRegex = Regex("""['"]?(?:category|name)['"]?\s*:\s*['"]([^'"}]+)['"]""")

    fun defaultAccounts(): List<LocalAccount> = listOf(
        LocalAccount(name = "现金", type = "cash", balance = 0.0, orderNum = 1),
        LocalAccount(name = "Touch 'n Go", type = "e_wallet", balance = 0.0, orderNum = 2),
        LocalAccount(name = "Maybank / MAE", type = "savings", balance = 0.0, orderNum = 3),
        LocalAccount(name = "Public Bank", type = "savings", balance = 0.0, orderNum = 4),
        LocalAccount(name = "CIMB Bank", type = "savings", balance = 0.0, orderNum = 5),
        LocalAccount(name = "GrabPay", type = "e_wallet", balance = 0.0, orderNum = 6),
        LocalAccount(name = "信用卡", type = "credit_card", balance = 0.0, creditLimit = 5000.0, orderNum = 7)
    )

    fun defaultCategories(): List<LocalCategory> = listOf(
        // Expense categories
        LocalCategory(name = "餐饮", type = "expense", icon = "🍔", color = "#fb923c", orderNum = 1),
        LocalCategory(name = "交通", type = "expense", icon = "🚗", color = "#38bdf8", orderNum = 2),
        LocalCategory(name = "杂货", type = "expense", icon = "🛒", color = "#34d399", orderNum = 3),
        LocalCategory(name = "购物", type = "expense", icon = "🛍️", color = "#f472b6", orderNum = 4),
        LocalCategory(name = "账单", type = "expense", icon = "💡", color = "#818cf8", orderNum = 5),
        LocalCategory(name = "娱乐", type = "expense", icon = "🎮", color = "#a78bfa", orderNum = 6),
        LocalCategory(name = "医疗", type = "expense", icon = "💊", color = "#f87171", orderNum = 7),
        LocalCategory(name = "其他", type = "expense", icon = "🏷️", color = "#94a3b8", orderNum = 8),
        // Income categories
        LocalCategory(name = "工资", type = "income", icon = "💰", color = "#10b981", orderNum = 9),
        LocalCategory(name = "兼职", type = "income", icon = "💼", color = "#06b6d4", orderNum = 10),
        LocalCategory(name = "理财", type = "income", icon = "📈", color = "#6366f1", orderNum = 11),
        LocalCategory(name = "其他收入", type = "income", icon = "🏷️", color = "#64748b", orderNum = 12),
        // Savings categories
        LocalCategory(name = "定期存款", type = "savings", icon = "🏦", color = "#0f766e", orderNum = 13),
        LocalCategory(name = "应急基金", type = "savings", icon = "🛡️", color = "#059669", orderNum = 14),
        LocalCategory(name = "心愿基金", type = "savings", icon = "⭐", color = "#d97706", orderNum = 15),
        LocalCategory(name = "投资储蓄", type = "savings", icon = "📈", color = "#0284c7", orderNum = 16)
    )

    fun cleanCategoryName(raw: String?): String {
        if (raw == null) return "其他"
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return "其他"
        if (!trimmed.contains("{") && !trimmed.contains(":") && !trimmed.startsWith("\"") && !trimmed.startsWith("'")) {
            return trimmed
        }
        val match = categoryJsonRegex.find(trimmed)
        if (match != null) {
            val extracted = match.groupValues[1].trim()
            if (extracted.isNotBlank() && extracted != "null") {
                return extracted
            }
        }
        return trimmed.removeSurrounding("\"").removeSurrounding("'").trim().ifBlank { "其他" }
    }

    fun computeOverviewStats(
        transactions: List<LocalTransaction>,
        yearMonth: String,
        period: String = "month",
        recentLimit: Int = 20
    ): SharedOverviewStats {
        val filtered = filterTransactionsByPeriod(transactions, yearMonth, period)
        var totalIncome = 0.0
        var totalExpense = 0.0
        var totalSavings = 0.0

        for (tx in filtered) {
            when (tx.type) {
                "income" -> totalIncome += tx.amount
                "expense" -> totalExpense += tx.amount
                "savings" -> totalSavings += tx.amount
                "transfer" -> Unit // Internal transfers do not count as income, expense, or savings
            }
        }

        val roundedIncome = roundCents(totalIncome)
        val roundedExpense = roundCents(totalExpense)
        val roundedSavings = roundCents(totalSavings)
        val netBalance = roundCents(roundedIncome - roundedExpense - roundedSavings)

        return SharedOverviewStats(
            period = period,
            yearMonth = yearMonth,
            income = roundedIncome,
            expense = roundedExpense,
            savings = roundedSavings,
            netBalance = netBalance,
            recentTransactions = filtered.take(recentLimit)
        )
    }

    fun computeAccountsSummary(
        accounts: List<LocalAccount>,
        transactions: List<LocalTransaction>,
        yearMonth: String
    ): SharedAccountsSummary {
        val monthTxs = if (yearMonth.isBlank()) {
            transactions
        } else {
            transactions.filter { it.date.startsWith(yearMonth) }
        }

        val items = accounts.map { acc ->
            var inAmt = 0.0
            var outAmt = 0.0
            for (tx in monthTxs) {
                // Exclude internal transfers (type == "transfer") from monthly cashflow In/Out
                if (tx.accountId == acc.id) {
                    when (tx.type) {
                        "income" -> inAmt += tx.amount
                        "expense" -> outAmt += tx.amount
                    }
                }
            }
            SharedAccountItem(
                account = acc,
                monthlyIn = roundCents(inAmt),
                monthlyOut = roundCents(outAmt)
            )
        }

        var assets = 0.0
        var liabilities = 0.0
        for (acc in accounts) {
            if (acc.type == "credit_card") {
                if (acc.balance < 0.0) {
                    liabilities += -acc.balance
                }
            } else {
                if (acc.balance >= 0.0) {
                    assets += acc.balance
                } else {
                    liabilities += -acc.balance
                }
            }
        }

        val roundedAssets = roundCents(assets)
        val roundedLiabilities = roundCents(liabilities)
        val netWorth = roundCents(roundedAssets - roundedLiabilities)

        return SharedAccountsSummary(
            totalAssets = roundedAssets,
            totalLiabilities = roundedLiabilities,
            netWorth = netWorth,
            items = items
        )
    }

    fun computeAccountDeltas(
        type: String,
        amount: Double,
        accountId: Int?,
        toAccountId: Int?
    ): Map<Int, Double> {
        if (amount == 0.0) return emptyMap()
        val deltas = LinkedHashMap<Int, Double>()
        when (type) {
            "income" -> {
                if (accountId != null) {
                    deltas[accountId] = roundCents(amount)
                }
            }
            "expense" -> {
                if (accountId != null) {
                    deltas[accountId] = roundCents(-amount)
                }
            }
            "savings" -> {
                if (accountId != null) {
                    deltas[accountId] = roundCents((deltas[accountId] ?: 0.0) - amount)
                }
                if (toAccountId != null) {
                    val updated = roundCents((deltas[toAccountId] ?: 0.0) + amount)
                    if (updated == 0.0) deltas.remove(toAccountId) else deltas[toAccountId] = updated
                }
            }
            "transfer" -> {
                if (accountId != null) {
                    deltas[accountId] = roundCents((deltas[accountId] ?: 0.0) - amount)
                }
                if (toAccountId != null) {
                    val updated = roundCents((deltas[toAccountId] ?: 0.0) + amount)
                    if (updated == 0.0) deltas.remove(toAccountId) else deltas[toAccountId] = updated
                }
            }
            else -> {
                if (accountId != null) {
                    deltas[accountId] = roundCents(-amount)
                }
            }
        }
        return deltas
    }

    suspend fun initDefaultsIfNeeded(db: AppDatabase = AppDatabase.getInstance()) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            val accountDao = db.localAccountDao()
            val existingAccounts = accountDao.getAll()
            if (existingAccounts.isEmpty()) {
                accountDao.insertAll(defaultAccounts())
            } else {
                val seenNames = mutableSetOf<String>()
                for (acc in existingAccounts) {
                    if (!seenNames.add(acc.name)) {
                        accountDao.deleteById(acc.id)
                    }
                }
                val hasPublicBank = existingAccounts.any { it.name.equals("Public Bank", ignoreCase = true) }
                if (!hasPublicBank) {
                    val maxOrder = existingAccounts.maxOfOrNull { it.orderNum } ?: 6
                    accountDao.insert(
                        LocalAccount(name = "Public Bank", type = "savings", balance = 0.0, orderNum = maxOrder + 1)
                    )
                }
            }

            val categoryDao = db.localCategoryDao()
            val existingCategories = categoryDao.getAll()
            if (existingCategories.isEmpty()) {
                categoryDao.insertAll(defaultCategories())
            } else {
                val seenCats = mutableSetOf<String>()
                for (cat in existingCategories) {
                    val clean = cleanCategoryName(cat.name)
                    if (!seenCats.add("${clean}_${cat.type}")) {
                        categoryDao.deleteById(cat.id)
                    } else if (clean != cat.name) {
                        categoryDao.deleteById(cat.id)
                        categoryDao.insert(cat.copy(id = 0, name = clean))
                    }
                }
                val hasSavingsCats = existingCategories.any { it.type == "savings" }
                if (!hasSavingsCats) {
                    val maxOrder = existingCategories.maxOfOrNull { it.orderNum } ?: 12
                    val savingsDefaults = defaultCategories()
                        .filter { it.type == "savings" }
                        .mapIndexed { idx, cat -> cat.copy(orderNum = maxOrder + 1 + idx) }
                    categoryDao.insertAll(savingsDefaults)
                }
            }
        }
    }

    suspend fun getOverviewStats(
        yearMonth: String,
        db: AppDatabase = AppDatabase.getInstance(),
        period: String = "month"
    ): SharedOverviewStats = withContext(Dispatchers.IO) {
        val txDao = db.localTransactionDao()
        val txList = when (period) {
            "3m" -> {
                val startMonth = shiftMonth(yearMonth, -2)
                val lastDay = daysInMonth(yearMonth).toString().padStart(2, '0')
                txDao.getByDateRange("$startMonth-01", "$yearMonth-$lastDay")
            }
            "year" -> {
                val targetYear = yearMonth.substringBefore('-')
                txDao.getByYear("$targetYear%")
            }
            else -> {
                txDao.getByMonth("$yearMonth%")
            }
        }
        computeOverviewStats(transactions = txList, yearMonth = yearMonth, period = period)
    }

    suspend fun getAccountsSummary(
        yearMonth: String,
        db: AppDatabase = AppDatabase.getInstance()
    ): SharedAccountsSummary = withContext(Dispatchers.IO) {
        val accounts = db.localAccountDao().getAll()
        val monthTxs = db.localTransactionDao().getByMonth("$yearMonth%")
        computeAccountsSummary(accounts = accounts, transactions = monthTxs, yearMonth = yearMonth)
    }

    suspend fun recordTransaction(
        date: String,
        time: String = "",
        type: String,
        category: String,
        amount: Double,
        note: String = "",
        tags: String = "",
        accountId: Int? = null,
        toAccountId: Int? = null,
        source: String = "manual",
        channel: String? = null,
        db: AppDatabase = AppDatabase.getInstance()
    ): Long = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val nowMillis = Clock.System.now().toEpochMilliseconds()
            val effectiveTime = time.ifBlank { currentTimeHms() }
            val cleanCat = cleanCategoryName(category)
            val tx = LocalTransaction(
                date = date,
                time = effectiveTime,
                type = type,
                category = cleanCat,
                amount = amount,
                note = note,
                tags = tags,
                accountId = accountId,
                toAccountId = toAccountId,
                source = source,
                channel = channel,
                createdAt = nowMillis,
                updatedAt = nowMillis
            )

            val insertedId = db.localTransactionDao().insert(tx)
            val deltas = computeAccountDeltas(
                type = type,
                amount = amount,
                accountId = accountId,
                toAccountId = toAccountId
            )
            val accountDao = db.localAccountDao()
            for ((accId, delta) in deltas) {
                accountDao.updateBalance(accId, delta)
            }
            insertedId
        }
    }

    suspend fun deleteTransaction(
        id: Long,
        db: AppDatabase = AppDatabase.getInstance()
    ): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val txDao = db.localTransactionDao()
            val existing = txDao.getById(id) ?: return@withLock false
            val deltas = computeAccountDeltas(
                type = existing.type,
                amount = existing.amount,
                accountId = existing.accountId,
                toAccountId = existing.toAccountId
            )
            val accountDao = db.localAccountDao()
            for ((accId, delta) in deltas) {
                accountDao.updateBalance(accId, roundCents(-delta))
            }
            txDao.deleteById(id)
            true
        }
    }

    private fun filterTransactionsByPeriod(
        transactions: List<LocalTransaction>,
        yearMonth: String,
        period: String
    ): List<LocalTransaction> {
        if (yearMonth.isBlank()) return transactions
        return when (period) {
            "3m" -> {
                val startMonth = shiftMonth(yearMonth, -2)
                val lastDay = daysInMonth(yearMonth).toString().padStart(2, '0')
                val startDate = "$startMonth-01"
                val endDate = "$yearMonth-$lastDay"
                transactions.filter { it.date >= startDate && it.date <= endDate }
            }
            "year" -> {
                val yearPrefix = yearMonth.substringBefore('-')
                transactions.filter { it.date.startsWith(yearPrefix) }
            }
            else -> {
                transactions.filter { it.date.startsWith(yearMonth) }
            }
        }
    }

    private fun currentTimeHms(): String {
        val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val h = dt.hour.toString().padStart(2, '0')
        val m = dt.minute.toString().padStart(2, '0')
        val s = dt.second.toString().padStart(2, '0')
        return "$h:$m:$s"
    }

    private fun roundCents(value: Double): Double {
        val rounded = round(value * 100.0) / 100.0
        return if (rounded == 0.0) 0.0 else rounded
    }
}
