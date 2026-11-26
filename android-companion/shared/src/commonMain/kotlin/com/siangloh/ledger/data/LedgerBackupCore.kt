package com.siangloh.ledger.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.siangloh.ledger.core.format.currentEpochMillis
import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.json.JSONArray
import com.siangloh.ledger.core.json.JSONObject
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class BackupPayload(
    val schemaVersion: Int,
    val exportedAt: String,
    val accounts: List<LocalAccount>,
    val categories: List<LocalCategory>,
    val transactions: List<LocalTransaction>
)

data class BackupExportSummary(
    val accountCount: Int,
    val categoryCount: Int,
    val transactionCount: Int,
    val jsonContent: String
)

data class CsvExportSummary(
    val transactionCount: Int,
    val csvContent: String
)

object LedgerBackupCore {
    const val CURRENT_SCHEMA_VERSION = 1

    suspend fun exportFromDatabase(
        db: AppDatabase = AppDatabase.getInstance()
    ): BackupExportSummary = withContext(Dispatchers.IO) {
        val accounts = db.localAccountDao().getAll()
        val categories = db.localCategoryDao().getAll()
        val transactions = db.localTransactionDao().getAll()
        val exportedAt = currentIsoTimestamp()
        val json = buildBackupJson(accounts, categories, transactions, exportedAt)
        BackupExportSummary(
            accountCount = accounts.size,
            categoryCount = categories.size,
            transactionCount = transactions.size,
            jsonContent = json
        )
    }

    suspend fun exportCsvFromDatabase(
        db: AppDatabase = AppDatabase.getInstance()
    ): CsvExportSummary = withContext(Dispatchers.IO) {
        val accounts = db.localAccountDao().getAll()
        val accountMap = accounts.associate { it.id to it.name }
        val transactions = db.localTransactionDao().getAll()
        val csv = buildTransactionsCsv(transactions, accountMap)
        CsvExportSummary(
            transactionCount = transactions.size,
            csvContent = csv
        )
    }

    suspend fun restoreToDatabase(
        db: AppDatabase = AppDatabase.getInstance(),
        payload: BackupPayload
    ): Unit = withContext(Dispatchers.IO) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.localTransactionDao().deleteAll()
                db.localAccountDao().deleteAll()
                db.localCategoryDao().deleteAll()

                if (payload.accounts.isNotEmpty()) {
                    db.localAccountDao().insertAll(payload.accounts)
                }
                if (payload.categories.isNotEmpty()) {
                    db.localCategoryDao().insertAll(payload.categories)
                }
                if (payload.transactions.isNotEmpty()) {
                    db.localTransactionDao().insertAll(payload.transactions)
                }
            }
        }
    }

    fun buildBackupJson(
        accounts: List<LocalAccount>,
        categories: List<LocalCategory>,
        transactions: List<LocalTransaction>,
        exportedAt: String
    ): String {
        val root = JSONObject()
        root.put("schema_version", CURRENT_SCHEMA_VERSION)
        root.put("app", "ledger-android")
        root.put("exported_at", exportedAt)

        val accArray = JSONArray()
        for (a in accounts) {
            accArray.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("type", a.type)
                put("balance", a.balance)
                put("creditLimit", a.creditLimit)
                put("currency", a.currency)
                put("orderNum", a.orderNum)
            })
        }
        root.put("accounts", accArray)

        val catArray = JSONArray()
        for (c in categories) {
            catArray.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("type", c.type)
                put("icon", c.icon)
                put("color", c.color)
                put("orderNum", c.orderNum)
            })
        }
        root.put("categories", catArray)

        val txArray = JSONArray()
        for (t in transactions) {
            txArray.put(JSONObject().apply {
                put("id", t.id)
                put("date", t.date)
                put("time", t.time)
                put("type", t.type)
                put("category", t.category)
                put("amount", t.amount)
                put("note", t.note)
                put("tags", t.tags)
                if (t.accountId != null) put("accountId", t.accountId) else put("accountId", JSONObject.NULL_VALUE)
                if (t.toAccountId != null) put("toAccountId", t.toAccountId) else put("toAccountId", JSONObject.NULL_VALUE)
                put("source", t.source)
                if (t.channel != null) put("channel", t.channel) else put("channel", JSONObject.NULL_VALUE)
                put("createdAt", t.createdAt)
                put("updatedAt", t.updatedAt)
            })
        }
        root.put("transactions", txArray)

        return root.toString(2)
    }

    fun parseBackupJson(rawJson: String): BackupPayload {
        if (rawJson.isBlank()) {
            throw IllegalArgumentException("Empty backup file")
        }
        val root = JSONObject(rawJson)
        val version = root.optInt("schema_version", -1)
        if (version < 1 || !root.has("transactions") || !root.has("accounts")) {
            throw IllegalArgumentException("Invalid ledger backup format")
        }

        val accounts = mutableListOf<LocalAccount>()
        val accArray = root.optJSONArray("accounts") ?: JSONArray()
        for (i in 0 until accArray.length()) {
            val o = accArray.getJSONObject(i)
            accounts.add(
                LocalAccount(
                    id = o.optInt("id", 0),
                    name = o.optString("name", ""),
                    type = o.optString("type", "cash"),
                    balance = o.optDouble("balance", 0.0),
                    creditLimit = o.optDouble("creditLimit", 0.0),
                    currency = o.optString("currency", "RM"),
                    orderNum = o.optInt("orderNum", i)
                )
            )
        }

        val categories = mutableListOf<LocalCategory>()
        val catArray = root.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until catArray.length()) {
            val o = catArray.getJSONObject(i)
            categories.add(
                LocalCategory(
                    id = o.optInt("id", 0),
                    name = o.optString("name", ""),
                    type = o.optString("type", "expense"),
                    icon = o.optString("icon", ""),
                    color = o.optString("color", "#94a3b8"),
                    orderNum = o.optInt("orderNum", i)
                )
            )
        }

        val nowMs = currentEpochMillis()
        val transactions = mutableListOf<LocalTransaction>()
        val txArray = root.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until txArray.length()) {
            val o = txArray.getJSONObject(i)
            val date = o.optString("date", "")
            if (date.isBlank()) continue
            transactions.add(
                LocalTransaction(
                    id = o.optLong("id", 0L),
                    date = date,
                    time = o.optString("time", ""),
                    type = o.optString("type", "expense"),
                    category = o.optString("category", ""),
                    amount = o.optDouble("amount", 0.0),
                    note = o.optString("note", ""),
                    tags = o.optString("tags", ""),
                    accountId = if (o.isNull("accountId")) null else o.optInt("accountId"),
                    toAccountId = if (o.isNull("toAccountId")) null else o.optInt("toAccountId"),
                    source = o.optString("source", "manual"),
                    channel = if (o.isNull("channel")) null else o.optString("channel"),
                    createdAt = o.optLong("createdAt", nowMs),
                    updatedAt = o.optLong("updatedAt", nowMs)
                )
            )
        }

        return BackupPayload(
            schemaVersion = version,
            exportedAt = root.optString("exported_at", ""),
            accounts = accounts,
            categories = categories,
            transactions = transactions
        )
    }

    /**
     * 生成 Excel 兼容的 UTF-8 (含 BOM) CSV 账单表格，防止中文/马来文在 Excel 中乱码。
     */
    fun buildTransactionsCsv(
        transactions: List<LocalTransaction>,
        accountNames: Map<Int, String>
    ): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("Date,Time,Type,Category,Amount,Account,ToAccount,Note,Tags\r\n")
        for (tx in transactions) {
            val accName = tx.accountId?.let { accountNames[it] } ?: ""
            val toAccName = tx.toAccountId?.let { accountNames[it] } ?: ""
            val amtStr = formatMoney(tx.amount, decimals = 2, grouping = false)
            sb.append(csvEscape(tx.date)).append(',')
            sb.append(csvEscape(tx.time)).append(',')
            sb.append(csvEscape(tx.type)).append(',')
            sb.append(csvEscape(tx.category)).append(',')
            sb.append(amtStr).append(',')
            sb.append(csvEscape(accName)).append(',')
            sb.append(csvEscape(toAccName)).append(',')
            sb.append(csvEscape(tx.note)).append(',')
            sb.append(csvEscape(tx.tags)).append("\r\n")
        }
        return sb.toString()
    }

    fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun currentIsoTimestamp(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val dt = Clock.System.now().toLocalDateTime(timeZone)
        val y = dt.year.toString().padStart(4, '0')
        val m = dt.monthNumber.toString().padStart(2, '0')
        val d = dt.dayOfMonth.toString().padStart(2, '0')
        val h = dt.hour.toString().padStart(2, '0')
        val min = dt.minute.toString().padStart(2, '0')
        val s = dt.second.toString().padStart(2, '0')
        return "$y-$m-${d}T$h:$min:$s"
    }
}
