package com.siangloh.ledger.ui.compose

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.siangloh.ledger.NetworkHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨 Tab 与页面生命周期保留用户记账草稿
 * 支持按用户/服务器隔离和本地 SharedPreferences 持久化，防止进程被系统回收后草稿丢失
 */
object TransactionDraftHolder {
    var txType by mutableStateOf("expense") // "expense", "income", "savings", "transfer"
    var amountText by mutableStateOf("")
    var note by mutableStateOf("")
    var tags by mutableStateOf("")
    var dateStr by mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    var selectedCategory by mutableStateOf("餐饮")
    var selectedAccountId by mutableStateOf<Int?>(null)
    var selectedToAccountId by mutableStateOf<Int?>(null)
    var incomeGroup by mutableStateOf("main")
    var isDetailsExpanded by mutableStateOf(false)

    private var currentDraftUserKey: String? = null
    private var isInitialized = false

    private fun getPrefKey(context: Context): String {
        val server = NetworkHelper.getServerUrl(context)
        val user = NetworkHelper.getUsername(context).ifBlank { "default_user" }
        return "tx_draft_${user}_${server.hashCode()}"
    }

    /**
     * 确保草稿与当前绑定用户/服务器对齐，并在冷启动/进程恢复时从持久化存储恢复草稿
     */
    fun ensureInitialized(context: Context) {
        val activeKey = getPrefKey(context)
        if (currentDraftUserKey != null && currentDraftUserKey != activeKey) {
            // 用户或服务器切换，清理并切换草稿空间
            clear(context)
        }
        currentDraftUserKey = activeKey

        if (!isInitialized) {
            isInitialized = true
            val prefs = context.getSharedPreferences(activeKey, Context.MODE_PRIVATE)
            if (prefs.contains("amountText")) {
                txType = prefs.getString("txType", "expense") ?: "expense"
                amountText = prefs.getString("amountText", "") ?: ""
                note = prefs.getString("note", "") ?: ""
                tags = prefs.getString("tags", "") ?: ""
                dateStr = prefs.getString("dateStr", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) ?: ""
                selectedCategory = prefs.getString("selectedCategory", "餐饮") ?: "餐饮"
                selectedAccountId = if (prefs.contains("selectedAccountId")) prefs.getInt("selectedAccountId", -1).takeIf { it != -1 } else null
                selectedToAccountId = if (prefs.contains("selectedToAccountId")) prefs.getInt("selectedToAccountId", -1).takeIf { it != -1 } else null
                incomeGroup = prefs.getString("incomeGroup", "main") ?: "main"
                isDetailsExpanded = prefs.getBoolean("isDetailsExpanded", false)
            }
        }
    }

    /**
     * 将当前草稿写入 SharedPreferences 持久化
     */
    fun persist(context: Context) {
        val activeKey = getPrefKey(context)
        val prefs = context.getSharedPreferences(activeKey, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("txType", txType)
            putString("amountText", amountText)
            putString("note", note)
            putString("tags", tags)
            putString("dateStr", dateStr)
            putString("selectedCategory", selectedCategory)
            if (selectedAccountId != null) putInt("selectedAccountId", selectedAccountId!!) else remove("selectedAccountId")
            if (selectedToAccountId != null) putInt("selectedToAccountId", selectedToAccountId!!) else remove("selectedToAccountId")
            putString("incomeGroup", incomeGroup)
            putBoolean("isDetailsExpanded", isDetailsExpanded)
            apply()
        }
    }

    fun clear(context: Context? = null) {
        txType = "expense"
        amountText = ""
        note = ""
        tags = ""
        dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        selectedCategory = "餐饮"
        selectedAccountId = null
        selectedToAccountId = null
        incomeGroup = "main"
        isDetailsExpanded = false

        context?.let { ctx ->
            val activeKey = getPrefKey(ctx)
            val prefs = ctx.getSharedPreferences(activeKey, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }
}
