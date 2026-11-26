package com.siangloh.ledger.parser

import com.siangloh.ledger.core.format.currentEpochMillis
import com.siangloh.ledger.core.format.todayDateString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.floor

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

    // Layer A: 绝对广告/非动账强特征词（即便夹杂 received / paid / transaksi 等词也一律拦截）
    private val HARD_PROMO_KEYWORDS = listOf(
        // 券/折扣/满减
        "voucher", "baucar", "coupon", "kupon", "promo code", "use code", "tebus", "rebut",
        "penebusan", "claim now", "claim your", "claim voucher", "redeem now", "redeem great rewards",
        "% off", "off!", "diskaun", "discount", "min. spend", "min spend", "minimum spend",
        "no min. spend", "no min spend", "tiada perbelanjaan minimum", "belanja minimum",
        "with min ", "优惠券", "代金券", "领券", "满减", "立减", "折扣", "免费领",
        // 抽奖/活动/拉新
        "win a ", "win up to", "to win ", "win big", "win exciting", "stand a chance", "chance to win",
        "berpeluang", "menang", "lucky draw", "cabutan bertuah", "giveaway", "contest", "spin & win",
        "shake & win", "jackpot", "cash prize", "prizes", "invite friend", "invite a friend",
        "referral", "refer & earn", "邀请好友", "分享领", "抽奖", "赢取",
        // 电商促销/购物车/直播/行动号召
        "flash sale", "shocking sale", "super brand day", "brand day", "payday sale",
        "mega sale", "big sale", "raya sale", "cny sale", "free shipping", "free delivery",
        "penghantaran percuma", "add to cart", "in your cart", "left in your cart",
        "wishlist", "price drop", "back in stock", "live stream", "livestream",
        "shop now", "order now", "buy now", "book now", "checkout now", "grab yours",
        "limited time offer", "limited offer", "limited redemption", "limited redemptions",
        "limited slots", "penebusan terhad", "exclusive offer", "special offer", "special deal",
        "hot deal", "great deal", "don't miss", "dont miss", "hurry", "last chance",
        "ends today", "ends soon", "today only", "while stocks last", "valid until", "valid till",
        "t&cs apply", "t&c apply", "terms and conditions", "tap here", "tap to ", "click here",
        "learn more", "find out more", "expiring soon", "expires soon", "waiting for you",
        "unclaimed", "free gift", "hadiah", "percuma", "限时", "特惠", "大促", "包邮", "秒杀", "好礼",
        // 诱导支付/充值/借贷/理财广告
        "top up now", "reload now", "pay now", "pay with duitnow", "pay with shopeepay",
        "pay with grabpay", "pay with tng", "pay with", "bayar guna", "bayar dengan",
        "apply online", "apply & get", "apply for", "apply now", "cash loan", "personal loan",
        "credit cardmember", "eligible for", "double cashback", "0% interest", "p.a.",
        "interest rate", "returns up to", "bonus interest", "fixed deposit", "fd rate",
        "充值返", "立即充值", "申请贷款", "贷款",
        // 安全验证码/非交易通知
        "otp", "tac", "one-time password", "verification code", "reset password",
        "login alert", "new login", "验证码", "登录提醒"
    )

    // Layer A 正则：动态金额型促销文案（如 "Get RM15", "Up to RM88", "RM10 off", "Spend RM50"）
    private val HARD_PROMO_PATTERNS = listOf(
        Regex("""\b(?:up to|sehingga|save up to|save|jimat|get up to|get|dapatkan|enjoy up to|enjoy|earn up to|earn|win|menang|unlock|claim)\s+(?:RM|MYR|\$)\s*[0-9]""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\b(?:RM|MYR|\$)\s*[0-9]+(?:\.[0-9]{1,2})?\s+off\b""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\b(?:when you|once you|if you)\s+(?:spend|pay|transfer|reload|top up|order|shop|use)\b""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\b(?:spend|belanja|fuel up|refuel)\s+(?:RM|MYR|\$)\s*[0-9]""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\boff\s+your\s+(?:next|first|order|purchase|ride|meal)\b""", setOf(RegexOption.IGNORE_CASE))
    )

    // Layer B: 弱营销信号词（若通知中缺少真实已完成动账凭证，则按广告拦截；真实收据尾部附带积分提示则放行）
    private val SOFT_PROMO_KEYWORDS = listOf(
        "just for you", "cashback", "rebate", "rewards", "reward", "points", "coins",
        "promo", "offer", "deal", "sale", "bonus", "exclusive", "surprise", "unlock", "collect",
        "pulangan tunai", "ganjaran", "mata ganjaran", "promosi", "tawaran"
    )

    // 明确的已完成动账凭证（过去式/已扣款/已入账，用于与弱营销信号区分）
    private val STRICT_COMPLETED_PATTERNS = listOf(
        Regex("""\b(?:you have paid|you've paid|you paid|paid\s+(?:RM|MYR)|payment\s+of\s+(?:RM|MYR)|has been deducted|have been deducted|debited|charged\s+(?:RM|MYR)|transferred\s+(?:RM|MYR)|has been transferred|received\s+(?:RM|MYR)|credited\s+(?:RM|MYR)|has been credited|has been refunded|refunded\s+(?:RM|MYR)|using your (?:cd|card)|card transaction alert)\b""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\b(?:RM|MYR)\s*[0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?\s+(?:paid to|spent|deducted|debited|transferred to|has been deducted|has been transferred|credited|refunded)\b""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\b(?:bayaran|transaksi)\b.*\bberjaya\b|\bberjaya\b.*\b(?:bayaran|transaksi)\b""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""(?:已支付|成功支付|成功转账|成功扣款|已扣款|已到账|已入账|已退回|已退款)""")
    )

    // Shopee 非支付类噪音词（卖家聊天、物流配送、好评提醒等，防止 com.shopee.my 误记）
    private val SHOPEE_NOISE_KEYWORDS = listOf(
        "replied", "chat", "parcel", "shipped", "delivered", "out for delivery",
        "seller", "rating", "review", "has arrived", "to ship", "tracking",
        "已发货", "已送达", "派送中", "卖家", "物流", "包裹", "评价"
    )

    /**
     * 跨应用同金额防重器：防止银行 App 推送 + 银行 SMS 短信在短时间内对同一笔扣款重复入账。
     * 默认窗口 3 分钟（180 秒），按 (type, amountCents) 跨包名去重。
     */
    class CrossAppDeduplicator(private val windowMs: Long = 180_000L) {
        private val recentKeysState = MutableStateFlow<Map<String, Long>>(emptyMap())

        fun isDuplicateAndRecord(
            type: String,
            amount: Double,
            nowMs: Long = currentEpochMillis()
        ): Boolean {
            if (amount <= 0.0) return false
            val cents = floor(amount * 100.0 + 0.5).toLong()
            val key = "$type:$cents"
            while (true) {
                val current = recentKeysState.value
                val lastSeen = current[key]
                if (lastSeen != null && (nowMs - lastSeen) in 0 until windowMs) {
                    return true
                }
                val next = LinkedHashMap<String, Long>(current.size + 1)
                if (current.size >= 200) {
                    val cutoff = nowMs - (windowMs * 2)
                    for ((k, v) in current) {
                        if (v >= cutoff) next[k] = v
                    }
                } else {
                    next.putAll(current)
                }
                next[key] = nowMs
                if (recentKeysState.compareAndSet(current, next)) {
                    return false
                }
            }
        }
    }

    /**
     * 统一广告/营销推广判定器（供 NotificationService Phase-1 与 LocalNotificationParser 共用，保证双层规则严格一致）
     */
    fun isPromotionalOrAd(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        // 1. 强广告/促销/验证码词库或动态金额促销句式 -> 无条件拦截
        if (HARD_PROMO_KEYWORDS.any { lower.contains(it) }) return true
        if (HARD_PROMO_PATTERNS.any { it.containsMatchIn(trimmed) }) return true

        // 2. 弱营销词：只有明确存在真实已完成动账结构（如收据附带“赚取积分”）才放行，否则拦截
        if (SOFT_PROMO_KEYWORDS.any { lower.contains(it) }) {
            val hasStrictCompleted = STRICT_COMPLETED_PATTERNS.any { it.containsMatchIn(trimmed) }
            if (!hasStrictCompleted) return true
        }

        return false
    }

    private val INTERNAL_TRANSFER_KEYWORDS = listOf(
        "into your go+ account", "into your go+", "cashed in", "cash in successful",
        "reload successful", "top up successful", "转存进入", "钱包充值成功",
        "reload touch n go", "top up touch n go", "grab top up", "grabpay reload"
    )

    private val TNG_PAYMENT_PATTERN = Regex(
        """(?:paid|payment of|spent)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TNG_TRANSFER_OUT_PATTERN = Regex(
        """(?:transfer of|transferred|transfer)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|has been transferred to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TNG_TRANSFER_OUT_ALT_PATTERN = Regex(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:has been transferred to|transferred to|transfer to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TNG_DEDUCTED_PATTERN = Regex(
        """Payment\s+To:\s*(.*?)(?::\s*|\s+)(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+has\s+been\s+deducted""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TNG_TRANSFER_IN_PATTERN = Regex(
        """(?:received|transfer from|duitnow transfer from)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:from)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})?""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val REFUND_REBATE_PATTERN = Regex(
        """(?:refund of|refunded|payment refunded|rebate of|cashback of|rebate|cashback)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?:\s+(?:from|for)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40}))?""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val MAYBANK_PAYMENT_PATTERN = Regex(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:paid to|transferred to|transfer to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val MAYBANK_TRANSFER_OUT_PATTERN = Regex(
        """(?:transfer of|transferred|transfer|duitnow transfer to|duitnow transfer)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|kepada)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val MAYBANK_CARD_PATTERN = Regex(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+at\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val GRAB_PAYMENT_PATTERN = Regex(
        """(?:you paid|payment of)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val PBB_CARD_PATTERN = Regex(
        """using\s+your\s+(?:cd|card)(?:\s+ending\s+\d+)?\s+(?:@|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})\s+(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val PBB_CARD_PATTERN_ALT = Regex(
        """using\s+your\s+(?:cd|card)(?:\s+ending\s+\d+)?\s+(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:@|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val PBB_PAYMENT_PATTERN = Regex(
        """(?:payment|transfer)\s+of\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+to\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,40})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val GENERIC_RM_PATTERN = Regex(
        """(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val GENERIC_MERCHANT_PATTERN = Regex(
        """(?:\b(?:paid to|transfer to|transferred to|transfer of|payment to|payment at|spent at|purchase at|bayaran di|transaksi di|kepada|to|at|di|for)|@|转账给|付款给)\s+([A-Za-z0-9\u4e00-\u9fa5\s&'._-]{2,35})""",
        setOf(RegexOption.IGNORE_CASE)
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

    private fun MatchResult.groupOrNull(index: Int): String? = groups[index]?.value

    fun parse(text: String, packageName: String? = null): LocalParsedResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // 1. 营销广告过滤（统一强/弱广告规则引擎）
        if (isPromotionalOrAd(trimmed)) {
            return LocalParsedResult(0.0, "expense", "广告过滤", "营销推广", "命中营销广告词库", "", getToday(), isPromo = true)
        }

        // 2. 内部划转与充值过滤
        if (INTERNAL_TRANSFER_KEYWORDS.any { lower.contains(it) }) {
            val hasExternal = listOf("from ", "received from", "duitnow from", "dari ").any { lower.contains(it) }
            if (!hasExternal) {
                return LocalParsedResult(0.0, "expense", "内部划转", "钱包充值/划转", "已自动忽略钱包内部充值", "", getToday(), isInternalTransfer = true)
            }
        }

        val pkg = packageName?.lowercase() ?: ""

        // 1.5 Shopee 专属严控：仅允许明确涉及 ShopeePay / SPayLater / 支付退款的通知，拦截卖家聊天/物流/降价提醒
        if (pkg.contains("shopee")) {
            val isShopeeNoise = SHOPEE_NOISE_KEYWORDS.any { lower.contains(it) }
            val hasShopeePaySignal = listOf("shopeepay", "spaylater", "paid", "payment", "refund", "已支付", "退款").any { lower.contains(it) }
            if (isShopeeNoise || !hasShopeePaySignal) {
                return LocalParsedResult(0.0, "expense", "广告过滤", "Shopee非支付通知", "已过滤Shopee非支付消息", "", getToday(), isPromo = true)
            }
        }

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
            TNG_PAYMENT_PATTERN.find(trimmed)?.let { mTngPay ->
                val amt = parseAmount(mTngPay.groupOrNull(1))
                val rawMerchant = cleanMerchant(mTngPay.groupOrNull(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Touch 'n Go", today)
                }
            }
            TNG_TRANSFER_OUT_PATTERN.find(trimmed)?.let { mTngOut ->
                val amt = parseAmount(mTngOut.groupOrNull(1))
                val rawMerchant = cleanMerchant(mTngOut.groupOrNull(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "expense", predictCategory(rawMerchant, lower), rawMerchant, "转账给 $rawMerchant", "Touch 'n Go", today)
                }
            }
            TNG_TRANSFER_OUT_ALT_PATTERN.find(trimmed)?.let { mTngOutAlt ->
                val amt = parseAmount(mTngOutAlt.groupOrNull(1))
                val rawMerchant = cleanMerchant(mTngOutAlt.groupOrNull(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "expense", predictCategory(rawMerchant, lower), rawMerchant, "转账给 $rawMerchant", "Touch 'n Go", today)
                }
            }
            TNG_DEDUCTED_PATTERN.find(trimmed)?.let { mTngDed ->
                val rawMerchant = cleanMerchant(mTngDed.groupOrNull(1) ?: "")
                val amt = parseAmount(mTngDed.groupOrNull(2))
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Touch 'n Go", today)
                }
            }
            TNG_TRANSFER_IN_PATTERN.find(trimmed)?.let { mTngIn ->
                val amt = parseAmount(mTngIn.groupOrNull(1))
                val sender = cleanMerchant(mTngIn.groupOrNull(2) ?: "朋友转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "income", "其他收入", sender, "收到 $sender 转账", "Touch 'n Go", today)
                }
            }
        }

        // 4. Grab 解析
        if (isGrab) {
            GRAB_PAYMENT_PATTERN.find(trimmed)?.let { mGrab ->
                val amt = parseAmount(mGrab.groupOrNull(1))
                val rawMerchant = cleanMerchant(mGrab.groupOrNull(2) ?: "Grab")
                if (amt > 0) {
                    val cat = if (lower.contains("grabfood")) "餐饮" else if (lower.contains("grabcar") || lower.contains("ride")) "交通" else predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Grab", today)
                }
            }
        }

        // 5. Maybank 解析
        if (isMaybank) {
            MAYBANK_PAYMENT_PATTERN.find(trimmed)?.let { mMay ->
                val amt = parseAmount(mMay.groupOrNull(1))
                val rawMerchant = cleanMerchant(mMay.groupOrNull(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Maybank", today)
                }
            }
            MAYBANK_TRANSFER_OUT_PATTERN.find(trimmed)?.let { mMayOut ->
                val amt = parseAmount(mMayOut.groupOrNull(1))
                val rawMerchant = cleanMerchant(mMayOut.groupOrNull(2) ?: "转账")
                if (amt > 0) {
                    return LocalParsedResult(amt, "expense", predictCategory(rawMerchant, lower), rawMerchant, "转账给 $rawMerchant", "Maybank", today)
                }
            }
            MAYBANK_CARD_PATTERN.find(trimmed)?.let { mCard ->
                val amt = parseAmount(mCard.groupOrNull(1))
                val rawMerchant = cleanMerchant(mCard.groupOrNull(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Maybank", today)
                }
            }
        }

        // 6. Public Bank 解析
        if (isPublicBank) {
            PBB_CARD_PATTERN.find(trimmed)?.let { mCard ->
                val rawMerchant = cleanMerchant(mCard.groupOrNull(1) ?: "")
                val amt = parseAmount(mCard.groupOrNull(2))
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
            PBB_CARD_PATTERN_ALT.find(trimmed)?.let { mCardAlt ->
                val amt = parseAmount(mCardAlt.groupOrNull(1))
                val rawMerchant = cleanMerchant(mCardAlt.groupOrNull(2) ?: "")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
            PBB_PAYMENT_PATTERN.find(trimmed)?.let { mPbbPay ->
                val amt = parseAmount(mPbbPay.groupOrNull(1))
                val rawMerchant = cleanMerchant(mPbbPay.groupOrNull(2) ?: "Public Bank支出")
                if (amt > 0) {
                    val cat = predictCategory(rawMerchant, lower)
                    return LocalParsedResult(amt, "expense", cat, rawMerchant, rawMerchant, "Public Bank", today)
                }
            }
        }

        // 7. 通用兜底解析 (Setel, DuitNow QR, CIMB, Public Bank, 银行短信等)
        // 严禁仅凭孤立名词 (如 fuel/petrol/transfer/bayar) 触发，必须具备明确已完成/过去式动账动作词，防止广告漏网
        val isOutgoingTransfer = listOf("transferred to", "has been transferred to", "duitnow transfer to", "转账给", "转账成功").any { lower.contains(it) }
        val isIncome = !isOutgoingTransfer && listOf(
            "received from", "you have received", "you've received", "credited",
            "duitnow transfer from", "transfer from", "已收款", "已到账", "已入账", "收款", "转入"
        ).any { lower.contains(it) }
        val isExpense = isOutgoingTransfer || listOf(
            "paid", "spent", "payment to", "payment of", "deducted", "debited", "charged",
            "transfer of", "transferred", "successful", "completed", "berjaya",
            "已支付", "已付款", "已扣款", "扣款", "支出",
            "using your cd", "using your card", "card transaction"
        ).any { lower.contains(it) }

        if (isExpense || isIncome) {
            GENERIC_RM_PATTERN.find(trimmed)?.let { mRm ->
                val amt = parseAmount(mRm.groupOrNull(1))
                if (amt > 0) {
                    val type = if (isIncome) "income" else "expense"
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
                    val mMcht = GENERIC_MERCHANT_PATTERN.find(trimmed)
                    val regexMerchant = if (mMcht != null) cleanMerchant(mMcht.groupOrNull(1) ?: "", "") else ""
                    val rawMerchant = when {
                        regexMerchant.isNotBlank() -> regexMerchant
                        type == "income" -> "$channel 入账"
                        isOutgoingTransfer -> "$channel 转账"
                        else -> inferBrandFromText(lower) ?: if (channel != "银行/卡") "$channel 消费" else "日常消费"
                    }
                    val cat = when {
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
        val isRefund = listOf("refunded", "refund of", "payment refunded", "has been refunded", "退款", "退回", "pulangan").any { lower.contains(it) }
        if (!isRebate && !isRefund) return null

        // 返现/回扣必须明确包含已入账/已发放动词，严禁把“消费满50返10元”类广告当成真实收入
        if (isRebate && !isRefund) {
            val hasSettledRebate = listOf(
                "credited", "received", "has been credited", "was credited", "into your", "to your",
                "dikreditkan", "diterima", "已到账", "已入账", "已返现", "已发放"
            ).any { lower.contains(it) }
            if (!hasSettledRebate) return null
        }

        var amt = 0.0
        var rawMerchant = ""
        REFUND_REBATE_PATTERN.find(trimmed)?.let { mRef ->
            amt = parseAmount(mRef.groupOrNull(1))
            rawMerchant = cleanMerchant(mRef.groupOrNull(2) ?: "", "")
        }
        if (amt <= 0.0) {
            GENERIC_RM_PATTERN.find(trimmed)?.let { mRm ->
                amt = parseAmount(mRm.groupOrNull(1))
            }
        }
        if (amt <= 0.0) return null

        if (rawMerchant.isBlank()) {
            GENERIC_MERCHANT_PATTERN.find(trimmed)?.let { mMcht ->
                val candidate = cleanMerchant(mMcht.groupOrNull(1) ?: "", "")
                if (candidate.isNotBlank() && !candidate.lowercase().startsWith("your ")) {
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

    private val CLEAN_MERCHANT_SPLIT_REGEX = Regex(
        """[\.\r\n]|\s+(?:on|via|ref|using|with|at|date|txid|claim|get|earn|for)\b""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val TRAILING_DIGITS_REGEX = Regex("""\s+#?\d{3,10}$""")

    private fun cleanMerchant(raw: String, fallback: String = "日常消费"): String {
        var cleaned = raw.split(CLEAN_MERCHANT_SPLIT_REGEX)[0].trim()
        cleaned = cleaned.trim(' ', '.', '-', ':', ',', '@')
        cleaned = cleaned.replace(TRAILING_DIGITS_REGEX, "").trim()
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size >= 2 && words.size % 2 == 0) {
            val half = words.size / 2
            val firstHalf = words.subList(0, half).joinToString(" ").lowercase()
            val secondHalf = words.subList(half, words.size).joinToString(" ").lowercase()
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
        val mLower = merchant.lowercase()
        for ((cat, keywords) in CATEGORY_RULES) {
            if (keywords.any { kw -> matchesKeyword(mLower, kw) || matchesKeyword(fullTextLower, kw) }) {
                return cat
            }
        }
        return "其他"
    }

    private fun getToday(): String = todayDateString()
}
