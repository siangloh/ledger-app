package com.siangloh.ledger.ui.compose

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.siangloh.ledger.R
import com.siangloh.ledger.ui.compose.theme.*

object CategoryUiHelper {
    fun cleanCategoryName(raw: String?): String {
        if (raw == null) return "其他"
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return "其他"
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = org.json.JSONObject(trimmed)
                val extracted = obj.optString("category").takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("name").takeIf { it.isNotBlank() && it != "null" }
                if (!extracted.isNullOrBlank()) return cleanCategoryName(extracted)
            } catch (_: Exception) {}
        }
        if (trimmed.contains("category") && (trimmed.startsWith("{") || trimmed.contains(":"))) {
            val regex = Regex("""['"]?category['"]?\s*:\s*['"]([^'"}]+)['"]""")
            val match = regex.find(trimmed)
            if (match != null) {
                val extracted = match.groupValues[1].trim()
                if (extracted.isNotBlank()) return cleanCategoryName(extracted)
            }
        }
        return trimmed.removeSurrounding("\"").removeSurrounding("'").trim().ifBlank { "其他" }
    }

    fun getCategoryEmoji(category: String, type: String = "expense"): String {
        val clean = cleanCategoryName(category).trim().lowercase()
        if (clean.contains("内部") || clean.contains("划转") || clean.contains("自转") || clean.contains("转账") || clean.contains("transfer") || clean.contains("pindahan")) return "🔄"
        if (type == "income") {
            return when {
                clean.contains("工资") || clean.contains("薪水") || clean.contains("salary") || clean.contains("gaji") -> "💰"
                clean.contains("奖金") || clean.contains("分红") || clean.contains("bonus") -> "🧧"
                clean.contains("投资") || clean.contains("利息") || clean.contains("invest") || clean.contains("pelaburan") -> "📈"
                clean.contains("兼职") || clean.contains("副业") || clean.contains("part-time") || clean.contains("sampingan") -> "💼"
                clean.contains("退款") || clean.contains("报销") || clean.contains("refund") || clean.contains("reimburse") -> "🧾"
                else -> "💵"
            }
        }
        return when {
            clean.contains("餐") || clean.contains("外卖") || clean.contains("美食") || clean.contains("早餐") || clean.contains("午餐") || clean.contains("晚餐") ||
                clean.contains("food") || clean.contains("makan") || clean.contains("dining") -> "🍔"
            clean.contains("交通") || clean.contains("打车") || clean.contains("油费") || clean.contains("停车") || clean.contains("grab") ||
                clean.contains("transport") || clean.contains("pengangkutan") -> "🚗"
            clean.contains("杂货") || clean.contains("超市") || clean.contains("生活") || clean.contains("grocer") || clean.contains("runcit") -> "🛒"
            clean.contains("账单") || clean.contains("水费") || clean.contains("电费") || clean.contains("话费") || clean.contains("网费") ||
                clean.contains("bill") || clean.contains("bil") || clean.contains("utilit") -> "💡"
            clean.contains("购") || clean.contains("衣服") || clean.contains("鞋") || clean.contains("shop") || clean.contains("beli") -> "🛍️"
            clean.contains("娱乐") || clean.contains("电影") || clean.contains("游戏") || clean.contains("聚会") ||
                clean.contains("game") || clean.contains("entertain") || clean.contains("hiburan") -> "🎮"
            clean.contains("医疗") || clean.contains("看病") || clean.contains("药") || clean.contains("保健") ||
                clean.contains("health") || clean.contains("medic") || clean.contains("perubatan") -> "💊"
            clean.contains("住") || clean.contains("房租") || clean.contains("物业") || clean.contains("rent") || clean.contains("sewa") || clean.contains("housing") -> "🏠"
            clean.contains("教") || clean.contains("学") || clean.contains("书") || clean.contains("edu") || clean.contains("pendidikan") -> "📚"
            clean.contains("宠") || clean.contains("pet") -> "🐱"
            clean.contains("旅") || clean.contains("酒店") || clean.contains("机票") || clean.contains("travel") || clean.contains("melancong") || clean.contains("cuti") -> "✈️"
            clean.contains("储蓄") || clean.contains("理财") || clean.contains("saving") || clean.contains("simpanan") -> "🏦"
            else -> "🏷️"
        }
    }

    fun getCategoryColor(category: String): Color {
        val clean = cleanCategoryName(category).trim().lowercase()
        return when {
            clean.contains("餐") || clean.contains("food") || clean.contains("makan") -> Color(0xFFF97316) // Orange
            clean.contains("交通") || clean.contains("transport") || clean.contains("pengangkutan") -> Sky500
            clean.contains("杂货") || clean.contains("grocer") || clean.contains("runcit") -> Emerald500
            clean.contains("账单") || clean.contains("bill") || clean.contains("bil") -> Amber500
            clean.contains("购") || clean.contains("shop") || clean.contains("beli") -> Rose500
            clean.contains("娱乐") || clean.contains("entertain") || clean.contains("hiburan") -> Sky500
            clean.contains("医疗") || clean.contains("health") || clean.contains("perubatan") -> Color(0xFFEF4444) // Red
            clean.contains("内部") || clean.contains("划转") || clean.contains("transfer") -> Slate600
            else -> Slate500
        }
    }

    fun getAccountTypeIcon(type: String): String {
        return when (type.lowercase()) {
            "savings" -> "🏦"
            "current" -> "🏛️"
            "credit_card" -> "💳"
            "e_wallet" -> "📱"
            "investment" -> "📈"
            "cash" -> "💵"
            else -> "📂"
        }
    }

    fun getAccountTypeName(type: String): String {
        return when (type.lowercase()) {
            "savings" -> "储蓄账户"
            "current" -> "往来账户"
            "credit_card" -> "信用卡"
            "e_wallet" -> "电子钱包"
            "investment" -> "投资理财"
            "cash" -> "现金钱包"
            else -> "其他账户"
        }
    }

    fun getAccountTypeName(context: Context, type: String): String {
        return when (type.lowercase()) {
            "savings" -> context.getString(R.string.account_type_savings)
            "current" -> context.getString(R.string.account_type_current)
            "credit_card" -> context.getString(R.string.account_type_credit_card)
            "e_wallet" -> context.getString(R.string.account_type_e_wallet)
            "investment" -> context.getString(R.string.account_type_investment)
            "cash" -> context.getString(R.string.account_type_cash)
            else -> context.getString(R.string.account_type_other)
        }
    }

    fun getLocalizedCategoryName(context: Context, category: String): String {
        val cleanCat = cleanCategoryName(category)
        val clean = cleanCat.trim().lowercase()
        return when {
            clean.contains("餐") || clean.contains("food") || clean.contains("makan") || clean.contains("dining") -> context.getString(R.string.category_dining)
            clean.contains("交通") || clean.contains("transport") || clean.contains("pengangkutan") -> context.getString(R.string.category_transport)
            clean.contains("杂货") || clean.contains("grocer") || clean.contains("runcit") -> context.getString(R.string.category_groceries)
            clean.contains("账单") || clean.contains("bill") || clean.contains("bil") || clean.contains("utilit") -> context.getString(R.string.category_bills)
            clean.contains("购") || clean.contains("shop") || clean.contains("beli") -> context.getString(R.string.category_shopping)
            clean.contains("娱乐") || clean.contains("entertain") || clean.contains("hiburan") || clean.contains("game") -> context.getString(R.string.category_entertainment)
            clean.contains("医疗") || clean.contains("medic") || clean.contains("health") || clean.contains("perubatan") -> context.getString(R.string.category_medical)
            clean.contains("住") || clean.contains("house") || clean.contains("rent") || clean.contains("sewa") -> context.getString(R.string.category_housing)
            clean.contains("教") || clean.contains("edu") || clean.contains("pendidikan") -> context.getString(R.string.category_education)
            clean.contains("宠") || clean.contains("pet") -> context.getString(R.string.category_pets)
            clean.contains("旅") || clean.contains("travel") || clean.contains("cuti") -> context.getString(R.string.category_travel)
            clean.contains("工资") || clean.contains("salary") || clean.contains("gaji") -> context.getString(R.string.category_salary)
            clean.contains("兼职") || clean.contains("副业") || clean.contains("sampingan") -> context.getString(R.string.category_side_income)
            clean.contains("奖金") || clean.contains("bonus") -> context.getString(R.string.category_bonus)
            clean.contains("定期") || clean.contains("fixed deposit") -> context.getString(R.string.category_fixed_deposit)
            clean.contains("应急") || clean.contains("emergency") -> context.getString(R.string.category_emergency_fund)
            clean.contains("心愿") || clean.contains("wish") -> context.getString(R.string.category_wish_fund)
            clean.contains("投资") || clean.contains("invest") || clean.contains("pelaburan") -> context.getString(R.string.category_investments)
            clean.contains("内部") || clean.contains("划转") || clean.contains("transfer") || clean.contains("pindahan") -> context.getString(R.string.category_transfer)
            clean.contains("其他") || clean.contains("other") || clean.contains("lain") -> context.getString(R.string.category_other)
            clean.contains("未分类") || clean.contains("待分类") || clean.contains("uncategorized") || clean.isBlank() -> context.getString(R.string.uncategorized_status)
            else -> cleanCat
        }
    }
}
