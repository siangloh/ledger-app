package com.siangloh.ledger.data

import android.content.Context
import android.content.Intent
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.widget.LedgerAppWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LedgerBackupManager {
    const val CURRENT_SCHEMA_VERSION = LedgerBackupCore.CURRENT_SCHEMA_VERSION

    suspend fun createBackup(context: Context): BackupExportSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        LedgerBackupCore.exportFromDatabase(db)
    }

    suspend fun createTransactionsCsv(context: Context): CsvExportSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        LedgerBackupCore.exportCsvFromDatabase(db)
    }

    suspend fun restoreBackup(context: Context, payload: BackupPayload): Unit = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        LedgerBackupCore.restoreToDatabase(db, payload)
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
    ): String = LedgerBackupCore.buildBackupJson(accounts, categories, transactions, exportedAt)

    fun parseBackupJson(rawJson: String): BackupPayload =
        LedgerBackupCore.parseBackupJson(rawJson)

    /**
     * 生成 Excel 兼容的 UTF-8 (含 BOM) CSV 账单表格，防止中文/马来文在 Excel 中乱码。
     */
    fun buildTransactionsCsv(
        transactions: List<LocalTransaction>,
        accountNames: Map<Int, String>
    ): String = LedgerBackupCore.buildTransactionsCsv(transactions, accountNames)

    internal fun csvEscape(value: String): String =
        LedgerBackupCore.csvEscape(value)
}
