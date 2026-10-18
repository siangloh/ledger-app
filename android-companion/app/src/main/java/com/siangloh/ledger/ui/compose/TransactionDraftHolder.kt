package com.siangloh.ledger.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨 Tab 与页面生命周期保留用户记账草稿
 * 避免在切页、查账返回或旋转屏幕时丢失用户输入的金额、分类、备注与账户信息
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

    fun clear() {
        txType = "expense"
        amountText = ""
        note = ""
        tags = ""
        dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        selectedCategory = "餐饮"
        incomeGroup = "main"
        isDetailsExpanded = false
    }
}
