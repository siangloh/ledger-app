package com.siangloh.ledger.parser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class LocalParsedResult(
    val amount: Double,
    val type: String, // "expense", "income"
    val category: String,
    val merchant: String,
    val note: String,
    val channel: String,
    val date: String,
    val isPromo: Boolean = false,
    val isInternalTransfer: Boolean = false
)

object LocalNotificationParser {

    private val PROMO_KEYWORDS = listOf(
        "apply online", "apply & get", "apply for", "credit cardmember", "eligible for",
        "double cashback", "win a", "win up to", "contest", "rewards point", "free gift",
        "t&cs apply", "terms and conditions", "exclusive offer", "limited time offer",
        "otp", "tac", "one-time password", "verification code", "reset password",
        "free shipping", "tebus baucar", "voucher", "super brand day", "flash sale",
        "% off", "save up to", "claim voucher", "spin & win", "payday sale"
    )

    private val INTERNAL_TRANSFER_KEYWORDS = listOf(
        "into your go+ account", "into your go+", "cashed in", "cash in successful",
        "reload successful", "top up successful", "转存进入", "钱包充值成功",
        "reload touch n go", "top up touch n go", "grab top up", "grabpay reload"
    )

    private val TNG_PAYMENT_PATTERN = Pattern.compile(
        """(?:paid|payment of|spent)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val TNG_TRANSFER_OUT_PATTERN = Pattern.compile(
        """(?:transfer of|transferred|transfer)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|has been transferred to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val TNG_TRANSFER_OUT_ALT_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:has been transferred to|transferred to|transfer to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val TNG_DEDUCTED_PATTERN = Pattern.compile(
        """Payment\s+To:\s*(.*?)(?::\s*|\s+)(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+has\s+been\s+deducted""",
        Pattern.CASE_INSENSITIVE
    )

    private val TNG_TRANSFER_IN_PATTERN = Pattern.compile(
        """(?:received|transfer from|duitnow transfer from)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:from)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})?""",
        Pattern.CASE_INSENSITIVE
    )

    private val MAYBANK_PAYMENT_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:paid to|transferred to|transfer to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val MAYBANK_TRANSFER_OUT_PATTERN = Pattern.compile(
        """(?:transfer of|transferred|transfer|duitnow transfer to|duitnow transfer)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})?""",
        Pattern.CASE_INSENSITIVE
    )

    private val MAYBANK_CARD_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+at\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val GRAB_PAYMENT_PATTERN = Pattern.compile(
        """(?:you paid|payment of)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_RM_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_MERCHANT_PATTERN = Pattern.compile(
        """(?:to|at|paid to|transfer to|transferred to|transfer of|payment to|转账给|付款给)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,35})""",
        Pattern.CASE_INSENSITIVE
    )

    private val CATEGORY_RULES = listOf(
        "餐饮" to listOf(
            "mcdonald", "kfc", "starbucks", "zus", "mixue", "subway", "tea", "boba", "coffee",
            "cafe", "kopitiam", "restaurant", "food", "bakery", "hawker", "mamak", "nasi lemak",
            "burger", "pizza", "sushi", "ramen", "hotpot", "tealive", "chagee", "llaollao",
            "dunkin", "secret recipe", "oldtown", "oriental kopi", "marrybrown", "liho"
        ),
        "交通" to listOf(
            "shell", "petronas", "caltex", "petron", "bhp", "parking", "toll", "plus",
            "touch n go - toll", "rapid", "mrt", "lrt", "monorail", "ktm", "grab ride",
            "grabcar", "airasia", "mycar", "indrive", "maxim", "petrol"
        ),
        "杂货" to listOf(
            "jaya grocer", "99 speedmart", "speedmart", "lotus", "village grocer", "aeon",
            "giant", "econsave", "pasar", "supermarket", "grocer", "bens independent",
            "nsk", "my hero", "hero market", "hero grocer", "don don donki", "family mart", "7-eleven", "kk mart", "my news", "cu mart"
        ),
        "购物" to listOf(
            "shopee", "lazada", "taobao", "tiktok shop", "uniqlo", "padini", "h&m",
            "zara", "cotton on", "shein", "decathlon", "mr diy", "mr. diy", "daiso",
            "ikea", "harvey norman", "popular bookstore", "apple", "samsung", "mi store"
        ),
        "日用" to listOf(
            "watsons", "guardian", "caring", "farmasi", "pharmacy", "clinic", "hospital",
            "dental", "laundry", "dry clean", "optometry", "hair salon", "barber"
        ),
        "账单" to listOf(
            "tnb", "tenaga", "air selangor", "indah water", "maxis", "celcom", "digi",
            "unifi", "astro", "telekom", "yes 5g", "umobile", "u mobile", "bill", "cuckoo", "coway"
        ),
        "娱乐" to listOf(
            "cinema", "gsc", "tgv", "mbo", "steam", "playstation", "nintendo", "spotify",
            "netflix", "youtube", "disney", "ktv", "karaoke", "bowling", "badminton"
        )
    )

    fun parse(text: String, packageName: String? = null): LocalParsedResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. 营销广告过滤
        if (PROMO_KEYWORDS.any { lower.contains(it) }) {
            return LocalParsedResult(0.0, "expense", "广告过滤", "营销推广", "命中营销广告词库", "", getToday(), isPromo = true)
        }

        // 2. 内部划转与充值过滤
        if (INTERNAL_TRANSFER_KEYWORDS.any { lower.contains(it) }) {
            val hasExternal = listOf("from ", "received from", "duitnow from", "dari ").any { lower.contains(it) }
            if (!hasExternal) {
                return LocalParsedResult(0.0, "expense", "内部划转", "钱包充值/划转", "已自动忽略钱包内部充值", "", getToday(), isInternalTransfer = true)
            }
        }

        val pkg = packageName?.lowercase(Locale.ROOT) ?: ""
        val isTng = pkg.contains("touchngo") || pkg.contains("tng") || pkg.contains("ewallet") || lower.contains("touch 'n go") || lower.contains("tng") || lower.contains("ewallet")
        val isGrab = pkg.contains("grab") || lower.contains("grab")
        val isMaybank = pkg.contains("maybank") || pkg.contains("m2u") || pkg.contains("mae") || lower.contains("maybank") || lower.contains("mae")

        val today = getToday()

        // 3. Touch 'n Go 解析
        if (isTng) {
            val mTngPay = TNG_PAYMENT_PATTERN.matcher(trimmed)
            if (mTngPay.find()) {
                val amt = parseAmount(mTngPay.group(1))
                val rawMerchant = cleanMerchant(mTngPay.group(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Touch 'n Go", today)
                }
            }
            val mTngOut = TNG_TRANSFER_OUT_PATTERN.matcher(trimmed)
            if (mTngOut.find()) {
                val amt = parseAmount(mTngOut.group(1))
                val rawMerchant = cleanMerchant(mTngOut.group(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "transfer", "转账", rawMerchant, "转账给 $rawMerchant", "Touch 'n Go", today)
                }
            }
            val mTngOutAlt = TNG_TRANSFER_OUT_ALT_PATTERN.matcher(trimmed)
            if (mTngOutAlt.find()) {
                val amt = parseAmount(mTngOutAlt.group(1))
                val rawMerchant = cleanMerchant(mTngOutAlt.group(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "transfer", "转账", rawMerchant, "转账给 $rawMerchant", "Touch 'n Go", today)
                }
            }
            val mTngDed = TNG_DEDUCTED_PATTERN.matcher(trimmed)
            if (mTngDed.find()) {
                val rawMerchant = cleanMerchant(mTngDed.group(1) ?: "")
                val amt = parseAmount(mTngDed.group(2))
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Touch 'n Go", today)
                }
            }
            val mTngIn = TNG_TRANSFER_IN_PATTERN.matcher(trimmed)
            if (mTngIn.find()) {
                val amt = parseAmount(mTngIn.group(1))
                val sender = cleanMerchant(mTngIn.group(2) ?: "朋友转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "income", "其他收入", sender, "收到 $sender 转账", "Touch 'n Go", today)
                }
            }
        }

        // 4. Grab 解析
        if (isGrab) {
            val mGrab = GRAB_PAYMENT_PATTERN.matcher(trimmed)
            if (mGrab.find()) {
                val amt = parseAmount(mGrab.group(1))
                val rawMerchant = cleanMerchant(mGrab.group(2) ?: "Grab")
                if (amt > 0) {
                    val cat = if (lower.contains("grabfood")) "餐饮" else if (lower.contains("grabcar") || lower.contains("ride")) "交通" else predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Grab", today)
                }
            }
        }

        // 5. Maybank 解析
        if (isMaybank) {
            val mMay = MAYBANK_PAYMENT_PATTERN.matcher(trimmed)
            if (mMay.find()) {
                val amt = parseAmount(mMay.group(1))
                val rawMerchant = cleanMerchant(mMay.group(2) ?: "")
                if (amt > 0) {
                    val isTf = lower.contains("transfer")
                    val cat = if (isTf) "转账" else predictCategory(rawMerchant, lower)
                    val type = if (isTf) "transfer" else "expense"
                    return LocalParsedResult(amt, type, cat, rawMerchant, rawMerchant, "Maybank", today)
                }
            }
            val mMayOut = MAYBANK_TRANSFER_OUT_PATTERN.matcher(trimmed)
            if (mMayOut.find()) {
                val amt = parseAmount(mMayOut.group(1))
                val rawMerchant = cleanMerchant(mMayOut.group(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "transfer", "转账", rawMerchant, "转账给 $rawMerchant", "Maybank", today)
                }
            }
            val mCard = MAYBANK_CARD_PATTERN.matcher(trimmed)
            if (mCard.find()) {
                val amt = parseAmount(mCard.group(1))
                val rawMerchant = cleanMerchant(mCard.group(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Maybank", today)
                }
            }
        }

        // 6. 通用兜底解析 (DuitNow QR, CIMB, Public Bank, 银行短信等)
        val isTransfer = listOf("transfer", "transferred", "duitnow transfer", "转账", "duitnow to").any { lower.contains(it) }
        val isExpense = isTransfer || listOf("paid", "spent", "payment to", "payment of", "deducted", "debited", "transfer to", "transferred to", "transfer of", "duitnow qr", "扣款", "支付", "支出", "转账给").any { lower.contains(it) }
        val isIncome = listOf("received from", "received", "credited", "duitnow transfer from", "transfer from", "收款", "转入", "到账").any { lower.contains(it) }

        if (isExpense || isIncome) {
            val mRm = GENERIC_RM_PATTERN.matcher(trimmed)
            if (mRm.find()) {
                val amt = parseAmount(mRm.group(1))
                if (amt > 0) {
                    val type = when {
                        isTransfer -> "transfer"
                        isIncome && !isExpense -> "income"
                        else -> "expense"
                    }
                    val mMcht = GENERIC_MERCHANT_PATTERN.matcher(trimmed)
                    val rawMerchant = if (mMcht.find()) cleanMerchant(mMcht.group(1) ?: "") else if (isTransfer) "转账" else if (type == "expense") "本地消费" else "本地入账"
                    val cat = when {
                        isTransfer -> "转账"
                        type == "income" -> "其他收入"
                        else -> predictCategory(rawMerchant, lower)
                    }
                    val channel = if (isTng) "Touch 'n Go" else if (isGrab) "Grab" else if (isMaybank) "Maybank" else "银行/卡"
                    return LocalParsedResult(amt, type, cat, rawMerchant, rawMerchant, channel, today)
                }
            }
        }

        return null
    }

    private fun parseAmount(str: String?): Double {
        if (str == null) return 0.0
        return try {
            str.replace(",", "").toDouble()
        } catch (_: Exception) {
            0.0
        }
    }

    private fun cleanMerchant(raw: String): String {
        var cleaned = raw.split(Regex("""[\.\r\n]|\s+(?:on|via|ref|using|with|at|date|txid|claim|get|earn)\b""", RegexOption.IGNORE_CASE))[0].trim()
        cleaned = cleaned.trim(' ', '.', '-', ':', ',')
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size >= 2 && words.size % 2 == 0) {
            val half = words.size / 2
            val firstHalf = words.subList(0, half).joinToString(" ").lowercase(Locale.ROOT)
            val secondHalf = words.subList(half, words.size).joinToString(" ").lowercase(Locale.ROOT)
            if (firstHalf == secondHalf) {
                cleaned = words.subList(0, half).joinToString(" ")
            }
        }
        return if (cleaned.isBlank()) "本地商户" else cleaned
    }

    fun predictCategory(merchant: String, fullTextLower: String): String {
        val mLower = merchant.lowercase(Locale.ROOT)
        for ((cat, keywords) in CATEGORY_RULES) {
            if (keywords.any { mLower.contains(it) || fullTextLower.contains(it) }) {
                return cat
            }
        }
        return "餐饮" // 马来西亚小额动账最常见兜底
    }

    private fun getToday(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}
