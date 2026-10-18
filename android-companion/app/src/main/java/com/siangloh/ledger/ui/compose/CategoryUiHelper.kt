package com.siangloh.ledger.ui.compose

import androidx.compose.ui.graphics.Color
import com.siangloh.ledger.ui.compose.theme.*

object CategoryUiHelper {
    fun getCategoryEmoji(category: String, type: String = "expense"): String {
        val clean = category.trim()
        if (clean == "内部划转" || clean == "自转" || clean == "转账/充值" || clean == "账户互转") return "🔄"
        if (type == "income") {
            return when {
                clean.contains("工资") || clean.contains("薪水") -> "💰"
                clean.contains("奖金") || clean.contains("分红") -> "🧧"
                clean.contains("投资") || clean.contains("利息") -> "📈"
                clean.contains("兼职") || clean.contains("副业") -> "💼"
                clean.contains("退款") || clean.contains("报销") -> "🧾"
                else -> "💵"
            }
        }
        return when (clean) {
            "餐饮", "外卖", "美食", "早餐", "午餐", "晚餐" -> "🍔"
            "交通", "打车", "油费", "停车", "Grab" -> "🚗"
            "杂货", "超市", "生活用品" -> "🛒"
            "账单", "水费", "电费", "话费", "网费" -> "💡"
            "购物", "网购", "衣服", "鞋子" -> "🛍️"
            "娱乐", "电影", "游戏", "聚会" -> "🎮"
            "医疗", "看病", "药品", "保健" -> "💊"
            "住房", "房租", "物业" -> "🏠"
            "教育", "学习", "书籍" -> "📚"
            "宠物" -> "🐱"
            "旅行", "酒店", "机票" -> "✈️"
            "储蓄", "理财" -> "🏦"
            else -> "🏷️"
        }
    }

    fun getCategoryColor(category: String): Color {
        return when (category.trim()) {
            "餐饮" -> Color(0xFFF97316) // Orange
            "交通" -> Sky500
            "杂货" -> Emerald500
            "账单" -> Amber500
            "购物" -> Rose500
            "娱乐" -> Color(0xFFA855F7) // Purple
            "医疗" -> Color(0xFFEF4444) // Red
            "内部划转" -> Indigo600
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
}
