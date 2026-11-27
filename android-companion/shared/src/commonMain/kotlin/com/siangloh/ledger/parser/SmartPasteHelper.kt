package com.siangloh.ledger.parser

import com.siangloh.ledger.data.entities.LocalAccount

/**
 * Pure-Kotlin helper for zero-setup clipboard smart-paste transaction detection.
 * Reuses [LocalNotificationParser] so bank/e-wallet extraction and anti-promo rules
 * stay 100% consistent across Android notifications, iOS Shortcuts, and Smart Paste.
 */
object SmartPasteHelper {

    fun parseClipboardTransaction(rawText: String?): LocalParsedResult? {
        val trimmed = rawText?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        val parsed = LocalNotificationParser.parse(trimmed) ?: return null
        if (parsed.isPromo || parsed.isInternalTransfer || parsed.amount <= 0.0) {
            return null
        }
        return parsed
    }

    fun matchAccountIdForChannel(
        channel: String,
        accounts: List<LocalAccount>
    ): Int? {
        if (channel.isBlank() || accounts.isEmpty()) return null
        val lower = channel.lowercase()
        val keywords = when {
            lower.contains("touch") || lower.contains("tng") -> listOf("tng", "touch", "ewallet", "钱包")
            lower.contains("grab") -> listOf("grab")
            lower.contains("maybank") || lower.contains("mae") -> listOf("maybank", "mae", "m2u")
            lower.contains("public bank") || lower.contains("pbb") -> listOf("public", "pbb")
            lower.contains("cimb") -> listOf("cimb")
            lower.contains("rhb") -> listOf("rhb")
            lower.contains("hong leong") || lower.contains("hlb") -> listOf("hong leong", "hlb")
            lower.contains("setel") -> listOf("setel")
            else -> emptyList()
        }
        if (keywords.isEmpty()) return null
        return accounts.firstOrNull { acc ->
            val nameLower = acc.name.lowercase()
            keywords.any { kw -> nameLower.contains(kw) }
        }?.id
    }
}
