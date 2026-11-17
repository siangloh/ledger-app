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

    private val REFUND_REBATE_PATTERN = Pattern.compile(
        """(?:refund of|refunded|payment refunded|rebate of|cashback of|rebate|cashback)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?:\s+(?:from|for)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40}))?""",
        Pattern.CASE_INSENSITIVE
    )

    private val MAYBANK_PAYMENT_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:paid to|transferred to|transfer to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val MAYBANK_TRANSFER_OUT_PATTERN = Pattern.compile(
        """(?:transfer of|transferred|transfer|duitnow transfer to|duitnow transfer)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|kepada)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
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

    private val PBB_CARD_PATTERN = Pattern.compile(
        """using\s+your\s+(?:cd|card)(?:\s+ending\s+\d+)?\s+(?:@|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})\s+(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val PBB_CARD_PATTERN_ALT = Pattern.compile(
        """using\s+your\s+(?:cd|card)(?:\s+ending\s+\d+)?\s+(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:@|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val PBB_PAYMENT_PATTERN = Pattern.compile(
        """(?:payment|transfer)\s+of\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+to\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_RM_PATTERN = Pattern.compile(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_MERCHANT_PATTERN = Pattern.compile(
        """(?:\b(?:paid to|transfer to|transferred to|transfer of|payment to|payment at|spent at|purchase at|bayaran di|transaksi di|kepada|to|at|di|for)|@|转账给|付款给)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,35})""",
        Pattern.CASE_INSENSITIVE
    )

    private val KNOWN_MERCHANT_LABELS = listOf(
        "petronas" to "PETRONAS",
        "setel" to "Setel",
        "shell" to "Shell",
        "caltex" to "Caltex",
        "bhpetrol" to "BHPetrol",
        "bhp" to "BHPetrol",
        "petron" to "Petron",
        "ron95" to "Petrol (RON95)",
        "ron97" to "Petrol (RON97)",
        "petrol" to "Petrol",
        "diesel" to "Diesel",
        "fuel" to "Fuel",
        "minyak" to "Fuel",
        "mcdonald" to "McDonald's",
        "kfc" to "KFC",
        "starbucks" to "Starbucks",
        "zus" to "ZUS Coffee",
        "tealive" to "Tealive",
        "chagee" to "CHAGEE",
        "99 speedmart" to "99 Speedmart",
        "jaya grocer" to "Jaya Grocer",
        "lotus" to "Lotus's",
        "aeon" to "AEON",
        "shopee" to "Shopee",
        "lazada" to "Lazada",
        "tnb" to "TNB",
        "unifi" to "Unifi"
    )

    private val CATEGORY_RULES = listOf(
        "交通" to listOf(
            "shell", "petronas", "caltex", "petron", "bhp", "bhpetrol", "setel",
            "fuel", "petrol", "diesel", "ron95", "ron97", "ron 95", "ron 97",
            "v-power", "vpower", "primax", "techron", "minyak", "isi minyak",
            "stesen minyak", "refuel", "engine oil", "lubricant", "oil",
            "parking", "toll", "plus", "rfid", "smarttag",
            "touch n go - toll", "rapid", "mrt", "lrt", "monorail", "ktm", "erl",
            "grab ride", "grabcar", "airasia", "mycar", "indrive", "maxim"
        ),
        "餐饮" to listOf(
            "mcdonald", "kfc", "starbucks", "zus", "mixue", "subway", "tea", "boba", "coffee",
            "cafe", "kopitiam", "restaurant", "food", "bakery", "hawker", "mamak", "nasi lemak",
            "burger", "pizza", "sushi", "ramen", "hotpot", "tealive", "chagee", "llaollao",
            "dunkin", "secret recipe", "oldtown", "oriental kopi", "marrybrown", "liho"
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
        val isPublicBank = pkg.contains("pbb") || pkg.contains("publicbank") || pkg.contains("mypb") || lower.contains("public bank") || lower.contains("pbb") || lower.contains("mypb")
        val isSetel = pkg.contains("setel") || lower.contains("setel")
        val isCimb = pkg.contains("cimb") || lower.contains("cimb")
        val isRhb = pkg.contains("rhb") || lower.contains("rhb")
        val isHlb = pkg.contains("hongleong") || pkg.contains("hlb") || lower.contains("hong leong") || lower.contains("hlb")

        val today = getToday()

        // 2.5 优先识别退款 / 返现回扣 (如 Touch 'n Go 预授权退款、加油退差额、消费返现)
        parseRefundOrRebate(
            trimmed = trimmed,
            lower = lower,
            channel = when {
                isTng -> "Touch 'n Go"
                isSetel -> "Setel"
                isGrab -> "Grab"
                isMaybank -> "Maybank"
                isPublicBank -> "Public Bank"
                isCimb -> "CIMB"
                isRhb -> "RHB"
                isHlb -> "Hong Leong"
                else -> "银行/卡"
            },
            today = today
        )?.let { return it }

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

        // 6. Public Bank 解析
        if (isPublicBank) {
            val mCard = PBB_CARD_PATTERN.matcher(trimmed)
            if (mCard.find()) {
                val rawMerchant = cleanMerchant(mCard.group(1) ?: "")
                val amt = parseAmount(mCard.group(2))
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
            val mCardAlt = PBB_CARD_PATTERN_ALT.matcher(trimmed)
            if (mCardAlt.find()) {
                val amt = parseAmount(mCardAlt.group(1))
                val rawMerchant = cleanMerchant(mCardAlt.group(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
            val mPbbPay = PBB_PAYMENT_PATTERN.matcher(trimmed)
            if (mPbbPay.find()) {
                val amt = parseAmount(mPbbPay.group(1))
                val rawMerchant = cleanMerchant(mPbbPay.group(2) ?: "Public Bank支出")
                if (amt > 0) {
                    val isTf = lower.contains("transfer")
                    val cat = if (isTf) "转账" else predictCategory(rawMerchant, lower)
                    val type = if (isTf) "transfer" else "expense"
                    return LocalParsedResult(amt, type, cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
        }

        // 7. 通用兜底解析 (Setel, DuitNow QR, CIMB, Public Bank, 银行短信等)
        val isTransfer = listOf("transfer", "transferred", "duitnow transfer", "转账", "duitnow to").any { lower.contains(it) }
        val isExpense = isTransfer || listOf(
            "paid", "spent", "payment to", "payment of", "deducted", "debited",
            "transfer to", "transferred to", "transfer of", "duitnow qr",
            "扣款", "支付", "支出", "转账给", "using your cd", "using your card", "card transaction",
            "fuel", "refuel", "petrol", "bayar", "bayaran", "transaksi", "berjaya"
        ).any { lower.contains(it) }
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
                    val channel = when {
                        isSetel -> "Setel"
                        isTng -> "Touch 'n Go"
                        isGrab -> "Grab"
                        isMaybank -> "Maybank"
                        isPublicBank -> "Public Bank"
                        isCimb -> "CIMB"
                        isRhb -> "RHB"
                        isHlb -> "Hong Leong"
                        else -> "银行/卡"
                    }
                    val mMcht = GENERIC_MERCHANT_PATTERN.matcher(trimmed)
                    val regexMerchant = if (mMcht.find()) cleanMerchant(mMcht.group(1) ?: "", "") else ""
                    val rawMerchant = when {
                        regexMerchant.isNotBlank() -> regexMerchant
                        isTransfer -> "转账"
                        type == "income" -> "$channel 入账"
                        else -> inferBrandFromText(lower) ?: if (channel != "银行/卡") "$channel 消费" else "日常消费"
                    }
                    val cat = when {
                        isTransfer -> "转账"
                        type == "income" -> "其他收入"
                        else -> predictCategory(rawMerchant, lower)
                    }
                    return LocalParsedResult(amt, type, cat, rawMerchant, rawMerchant, channel, today)
                }
            }
        }

        return null
    }

    private fun parseRefundOrRebate(
        trimmed: String,
        lower: String,
        channel: String,
        today: String
    ): LocalParsedResult? {
        val isRebate = listOf("rebate", "cashback", "回扣", "返现").any { lower.contains(it) }
        val isRefund = listOf("refund", "refunded", "退款", "退回", "pulangan").any { lower.contains(it) }
        if (!isRebate && !isRefund) return null

        val mRef = REFUND_REBATE_PATTERN.matcher(trimmed)
        var amt = 0.0
        var rawMerchant = ""
        if (mRef.find()) {
            amt = parseAmount(mRef.group(1))
            rawMerchant = cleanMerchant(mRef.group(2) ?: "", "")
        }
        if (amt <= 0.0) {
            val mRm = GENERIC_RM_PATTERN.matcher(trimmed)
            if (mRm.find()) {
                amt = parseAmount(mRm.group(1))
            }
        }
        if (amt <= 0.0) return null

        if (rawMerchant.isBlank()) {
            val mMcht = GENERIC_MERCHANT_PATTERN.matcher(trimmed)
            if (mMcht.find()) {
                val candidate = cleanMerchant(mMcht.group(1) ?: "", "")
                if (candidate.isNotBlank() && !candidate.lowercase(Locale.ROOT).startsWith("your ")) {
                    rawMerchant = candidate
                }
            }
        }

        val brand = rawMerchant.ifBlank { inferBrandFromText(lower) ?: channel }
        val category = if (isRebate && !isRefund) "返现" else "退款"
        val note = if (isRebate && !isRefund) "$brand 返现" else "$brand 退款"
        return LocalParsedResult(amt, "income", category, note, note, channel, today)
    }

    private fun inferBrandFromText(lower: String): String? {
        for ((kw, label) in KNOWN_MERCHANT_LABELS) {
            if (matchesKeyword(lower, kw)) {
                return label
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

    private fun cleanMerchant(raw: String, fallback: String = "日常消费"): String {
        var cleaned = raw.split(Regex("""[\.\r\n]|\s+(?:on|via|ref|using|with|at|date|txid|claim|get|earn|for)\b""", RegexOption.IGNORE_CASE))[0].trim()
        cleaned = cleaned.trim(' ', '.', '-', ':', ',', '@')
        cleaned = cleaned.replace(Regex("""\s+#?\d{3,10}$"""), "").trim()
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size >= 2 && words.size % 2 == 0) {
            val half = words.size / 2
            val firstHalf = words.subList(0, half).joinToString(" ").lowercase(Locale.ROOT)
            val secondHalf = words.subList(half, words.size).joinToString(" ").lowercase(Locale.ROOT)
            if (firstHalf == secondHalf) {
                cleaned = words.subList(0, half).joinToString(" ")
            }
        }
        return if (cleaned.isBlank()) fallback else cleaned
    }

    private fun matchesKeyword(textLower: String, keyword: String): Boolean {
        if (keyword.length <= 4 && keyword.all { it.isLetterOrDigit() }) {
            return Regex("""\b${Regex.escape(keyword)}\b""").containsMatchIn(textLower)
        }
        return textLower.contains(keyword)
    }

    fun predictCategory(merchant: String, fullTextLower: String): String {
        val mLower = merchant.lowercase(Locale.ROOT)
        for ((cat, keywords) in CATEGORY_RULES) {
            if (keywords.any { kw -> matchesKeyword(mLower, kw) || matchesKeyword(fullTextLower, kw) }) {
                return cat
            }
        }
        return "其他"
    }

    private fun getToday(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}
