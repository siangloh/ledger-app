package com.siangloh.ledger.data

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.widget.LedgerAppWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

object LedgerBackupManager {
    const val CURRENT_SCHEMA_VERSION = 1

    suspend fun createBackup(context: Context): BackupExportSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val accounts = db.localAccountDao().getAll()
        val categories = db.localCategoryDao().getAll()
        val transactions = db.localTransactionDao().getAll()
        val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val json = buildBackupJson(accounts, categories, transactions, exportedAt)
        BackupExportSummary(
            accountCount = accounts.size,
            categoryCount = categories.size,
            transactionCount = transactions.size,
            jsonContent = json
        )
    }

    suspend fun createTransactionsCsv(context: Context): CsvExportSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val accounts = db.localAccountDao().getAll()
        val accountMap = accounts.associate { it.id to it.name }
        val transactions = db.localTransactionDao().getAll()
        val csv = buildTransactionsCsv(transactions, accountMap)
        CsvExportSummary(
            transactionCount = transactions.size,
            csvContent = csv
        )
    }

    suspend fun restoreBackup(context: Context, payload: BackupPayload): Unit = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
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
        LocalDataStore.syncAllUserData(context, force = true)
        LedgerAppWidgetProvider.notifyDataChanged(context)
        val intent = Intent("com.siangloh.ledger.DATA_SYNCED").setPackage(context.packageName)
        context.sendBroadcast(intent)
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
                if (t.accountId != null) put("accountId", t.accountId) else put("accountId", JSONObject.NULL)
                if (t.toAccountId != null) put("toAccountId", t.toAccountId) else put("toAccountId", JSONObject.NULL)
                put("source", t.source)
                if (t.channel != null) put("channel", t.channel) else put("channel", JSONObject.NULL)
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
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
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
            val amtStr = String.format(Locale.US, "%.2f", tx.amount)
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

    internal fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
