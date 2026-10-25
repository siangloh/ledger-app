package com.siangloh.ledger.ui.compose.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R

/**
 * 渠道品牌与来源徽标辅助工具与组件。
 * 针对 Maybank、Public Bank、Touch 'n Go、Grab、Shopee、CIMB 等金融/支付渠道展示专属官方高精矢量 Logo，
 * 彻底替换传统的单一颜色圆点，极大提升辨识度与现代质感。
 */
object SourceBrandBadgeHelper {

    data class BrandBadgeConfig(
        val label: String,
        val emoji: String,
        val containerColor: Color,
        val contentColor: Color,
        val borderColor: Color,
        @DrawableRes val iconRes: Int? = null
    )

    fun normalizeChannel(rawChannel: String?, note: String = ""): String? {
        val c = (rawChannel ?: "").trim().lowercase()
        val n = note.trim().lowercase()

        // 1. 先匹配明确的渠道参数
        val directMatch = when {
            c == "maybank" || c.contains("mae") || c.contains("m2u") -> "maybank"
            c == "tng" || c.contains("touch") || c.contains("ewallet") -> "tng"
            c == "public_bank" || c.contains("public") || c.contains("mypb") || c.contains("pbb") -> "public_bank"
            c == "grab" || c.contains("grab") -> "grab"
            c == "shopee" || c.contains("shopee") -> "shopee"
            c == "cimb" || c.contains("cimb") -> "cimb"
            c == "duitnow" || c.contains("duitnow") -> "duitnow"
            c == "rhb" || c.contains("rhb") -> "rhb"
            c == "hlb" || c.contains("hong leong") || c.contains("hongleong") -> "hlb"
            c.contains("bank") || c.contains("card") || c.contains("sms") -> "bank_card"
            c.isNotEmpty() && c != "generic" && c != "notification" -> c
            else -> null
        }
        if (directMatch != null) return directMatch

        // 2. 若渠道未指定或为通用通知，根据备注关键字推断银行/钱包品牌
        if (n.isNotEmpty()) {
            val inferred = when {
                n.contains("maybank") || n.contains("mae") || n.contains("m2u") -> "maybank"
                n.contains("tng") || n.contains("touchngo") || n.contains("touch n go") || n.contains("touch 'n go") || n.contains("toll payment") || n.contains("paydirect") || n.contains("ewallet") -> "tng"
                n.contains("public bank") || n.contains("mypb") || n.contains("pbb") || n.contains("publicbank") -> "public_bank"
                n.contains("grab") || n.contains("grabpay") -> "grab"
                n.contains("shopee") || n.contains("shopeepay") -> "shopee"
                n.contains("cimb") -> "cimb"
                n.contains("duitnow") -> "duitnow"
                n.contains("rhb") -> "rhb"
                n.contains("hong leong") || n.contains("hongleong") || n.contains("hlb") -> "hlb"
                else -> null
            }
            if (inferred != null) return inferred
        }

        return if (c == "generic" || c == "notification") "generic" else null
    }

    @Composable
    fun getBadgeConfig(
        source: String,
        channel: String?,
        note: String = "",
        fallbackBadgeText: String = "",
        fallbackBadgeEmoji: String = "",
        fallbackBadgeColor: String = ""
    ): BrandBadgeConfig {
        val normChannel = normalizeChannel(channel, note)
        val normSource = source.trim().lowercase()

        // 1. 若为明确的金融/电子钱包渠道，优先呈现高辨识度专属官方真实品牌 Logo 照片
        when (normChannel) {
            "maybank" -> return BrandBadgeConfig(
                label = "Maybank",
                emoji = "🟡",
                containerColor = Color(0xFFFEF9C3), // Yellow 100
                contentColor = Color(0xFF854D0E),   // Yellow 800
                borderColor = Color(0xFFFDE047),    // Yellow 300
                iconRes = R.drawable.logo_maybank
            )
            "tng" -> return BrandBadgeConfig(
                label = "TNG",
                emoji = "🔵",
                containerColor = Color(0xFFE0F2FE), // Sky 100
                contentColor = Color(0xFF0369A1),   // Sky 700
                borderColor = Color(0xFFBAE6FD),    // Sky 200
                iconRes = R.drawable.logo_tng
            )
            "public_bank" -> return BrandBadgeConfig(
                label = "PB",
                emoji = "🔴",
                containerColor = Color(0xFFFEE2E2), // Red 100
                contentColor = Color(0xFFB91C1C),   // Red 700
                borderColor = Color(0xFFFECACA),    // Red 200
                iconRes = R.drawable.logo_public_bank
            )
            "grab" -> return BrandBadgeConfig(
                label = "Grab",
                emoji = "🟢",
                containerColor = Color(0xFFDCFCE7), // Green 100
                contentColor = Color(0xFF15803D),   // Green 700
                borderColor = Color(0xFFBBF7D0),    // Green 200
                iconRes = R.drawable.logo_grab
            )
            "shopee" -> return BrandBadgeConfig(
                label = "Shopee",
                emoji = "🟠",
                containerColor = Color(0xFFFFEDD5), // Orange 100
                contentColor = Color(0xFFC2410C),   // Orange 700
                borderColor = Color(0xFFFED7AA),    // Orange 200
                iconRes = R.drawable.logo_shopee
            )
            "cimb" -> return BrandBadgeConfig(
                label = "CIMB",
                emoji = "🔴",
                containerColor = Color(0xFFFFE4E6), // Rose 100
                contentColor = Color(0xFFBE123C),   // Rose 700
                borderColor = Color(0xFFFECDD3),    // Rose 200
                iconRes = R.drawable.logo_cimb
            )
            "duitnow" -> return BrandBadgeConfig(
                label = "DuitNow",
                emoji = "🌸",
                containerColor = Color(0xFFFCE7F3), // Pink 100
                contentColor = Color(0xFFBE185D),   // Pink 700
                borderColor = Color(0xFFFBCFE8),    // Pink 200
                iconRes = R.drawable.logo_duitnow
            )
            "rhb" -> return BrandBadgeConfig(
                label = "RHB",
                emoji = "🔵",
                containerColor = Color(0xFFEFF6FF), // Blue 50
                contentColor = Color(0xFF1D4ED8),   // Blue 700
                borderColor = Color(0xFFBFDBFE),    // Blue 200
                iconRes = R.drawable.logo_rhb
            )
            "hlb" -> return BrandBadgeConfig(
                label = "HLB",
                emoji = "🔴",
                containerColor = Color(0xFFFEF2F2), // Red 50
                contentColor = Color(0xFFB91C1C),   // Red 700
                borderColor = Color(0xFFFECACA),    // Red 200
                iconRes = R.drawable.logo_hlb
            )
            "bank_card" -> return BrandBadgeConfig(
                label = stringResource(R.string.source_label_notification),
                emoji = "💳",
                containerColor = Color(0xFFEEF2FF), // Indigo 50
                contentColor = Color(0xFF4338CA),   // Indigo 700
                borderColor = Color(0xFFE0E7FF),    // Indigo 100
                iconRes = R.drawable.ic_brand_card
            )
        }

        // 2. 根据来源进行多语言本地化映射
        return when (normSource) {
            "notification", "auto_track", "auto_track_confirmed" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_notification),
                emoji = "📱",
                containerColor = com.siangloh.ledger.ui.compose.theme.PrimaryTealSubtle,
                contentColor = com.siangloh.ledger.ui.compose.theme.PrimaryTeal,
                borderColor = com.siangloh.ledger.ui.compose.theme.Teal100
            )
            "manual" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_manual),
                emoji = "✍️",
                containerColor = Color(0xFFF1F5F9), // Slate 100
                contentColor = Color(0xFF475569),   // Slate 600
                borderColor = Color(0xFFE2E8F0)    // Slate 200
            )
            "import" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_import),
                emoji = "📁",
                containerColor = Color(0xFFFEF3C7), // Amber 100
                contentColor = Color(0xFFB45309),   // Amber 700
                borderColor = Color(0xFFFDE68A)    // Amber 200
            )
            "api" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_api),
                emoji = "⚙️",
                containerColor = Color(0xFFEEF2FF), // Indigo 50
                contentColor = Color(0xFF4F46E5),   // Indigo 600
                borderColor = Color(0xFFE0E7FF)    // Indigo 100
            )
            "nlp" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_nlp),
                emoji = "🤖",
                containerColor = Color(0xFFF5F3FF), // Purple 50
                contentColor = Color(0xFF7C3AED),   // Purple 600
                borderColor = Color(0xFFEDE9FE)    // Purple 100
            )
            "recurring" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_recurring),
                emoji = "🔁",
                containerColor = Color(0xFFE0F2FE), // Sky 100
                contentColor = Color(0xFF0284C7),   // Sky 600
                borderColor = Color(0xFFBAE6FD)    // Sky 200
            )
            "split_bill" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_split_bill),
                emoji = "👥",
                containerColor = Color(0xFFFDF2F8), // Pink 50
                contentColor = Color(0xFFDB2777),   // Pink 600
                borderColor = Color(0xFFFCE7F3)    // Pink 100
            )
            "reconciliation" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_reconciliation),
                emoji = "⚖️",
                containerColor = Color(0xFFECFDF5), // Emerald 50
                contentColor = Color(0xFF059669),   // Emerald 600
                borderColor = Color(0xFFD1FAE5)    // Emerald 100
            )
            else -> {
                val label = fallbackBadgeText.ifBlank { stringResource(R.string.source_label_unknown) }
                val emoji = fallbackBadgeEmoji.ifBlank { "❓" }
                BrandBadgeConfig(
                    label = label,
                    emoji = emoji,
                    containerColor = Color(0xFFF8FAFC),
                    contentColor = Color(0xFF64748B),
                    borderColor = Color(0xFFE2E8F0)
                )
            }
        }
    }

    /**
     * 针对账户名称与渠道进行智能合并与品牌化识别，
     * 避免因同时显示渠道徽标与账户名称而导致诸如 "TNG" 与 "Touch 'n Go" 重复展示的问题。
     */
    fun getAccountBrandConfig(accountName: String, channel: String?, note: String = ""): BrandBadgeConfig {
        val normCh = normalizeChannel(channel, note)
        val accLower = accountName.trim().lowercase()

        val matchedBrand = when {
            accLower.contains("tng") || accLower.contains("touch") || normCh == "tng" -> "tng"
            accLower.contains("maybank") || accLower.contains("mae") || accLower.contains("m2u") || normCh == "maybank" -> "maybank"
            accLower.contains("public") || accLower.contains("pb") || normCh == "public_bank" -> "public_bank"
            accLower.contains("grab") || normCh == "grab" -> "grab"
            accLower.contains("shopee") || normCh == "shopee" -> "shopee"
            accLower.contains("cimb") || normCh == "cimb" -> "cimb"
            accLower.contains("duitnow") || normCh == "duitnow" -> "duitnow"
            accLower.contains("rhb") || normCh == "rhb" -> "rhb"
            accLower.contains("hong leong") || accLower.contains("hongleong") || accLower.contains("hlb") || normCh == "hlb" -> "hlb"
            accLower.contains("cash") || accLower.contains("现金") -> "cash"
            accLower.contains("card") || accLower.contains("卡") || accLower.contains("credit") -> "card"
            else -> null
        }

        return when (matchedBrand) {
            "tng" -> BrandBadgeConfig(
                label = accountName.ifBlank { "TNG" },
                emoji = "🔵",
                containerColor = Color(0xFFF0F9FF), // Sky 50
                contentColor = Color(0xFF0369A1),   // Sky 700
                borderColor = Color(0xFFBAE6FD),    // Sky 200
                iconRes = R.drawable.logo_tng
            )
            "maybank" -> BrandBadgeConfig(
                label = accountName.ifBlank { "Maybank" },
                emoji = "🟡",
                containerColor = Color(0xFFFEFCE8), // Yellow 50
                contentColor = Color(0xFF854D0E),   // Yellow 800
                borderColor = Color(0xFFFEF08A),    // Yellow 200
                iconRes = if (accLower.contains("mae") && !accLower.contains("maybank")) R.drawable.logo_mae else R.drawable.logo_maybank
            )
            "public_bank" -> BrandBadgeConfig(
                label = accountName.ifBlank { "PB" },
                emoji = "🔴",
                containerColor = Color(0xFFFEF2F2), // Red 50
                contentColor = Color(0xFFB91C1C),   // Red 700
                borderColor = Color(0xFFFECACA),    // Red 200
                iconRes = R.drawable.logo_public_bank
            )
            "grab" -> BrandBadgeConfig(
                label = accountName.ifBlank { "Grab" },
                emoji = "🟢",
                containerColor = Color(0xFFF0FDF4), // Green 50
                contentColor = Color(0xFF15803D),   // Green 700
                borderColor = Color(0xFFBBF7D0),    // Green 200
                iconRes = R.drawable.logo_grab
            )
            "shopee" -> BrandBadgeConfig(
                label = accountName.ifBlank { "Shopee" },
                emoji = "🟠",
                containerColor = Color(0xFFFFF7ED), // Orange 50
                contentColor = Color(0xFFC2410C),   // Orange 700
                borderColor = Color(0xFFFED7AA),    // Orange 200
                iconRes = R.drawable.logo_shopee
            )
            "cimb" -> BrandBadgeConfig(
                label = accountName.ifBlank { "CIMB" },
                emoji = "🔴",
                containerColor = Color(0xFFFFF1F2), // Rose 50
                contentColor = Color(0xFFBE123C),   // Rose 700
                borderColor = Color(0xFFFECDD3),    // Rose 200
                iconRes = R.drawable.logo_cimb
            )
            "duitnow" -> BrandBadgeConfig(
                label = accountName.ifBlank { "DuitNow" },
                emoji = "🌸",
                containerColor = Color(0xFFFCE7F3), // Pink 50
                contentColor = Color(0xFFBE185D),   // Pink 700
                borderColor = Color(0xFFFBCFE8),    // Pink 200
                iconRes = R.drawable.logo_duitnow
            )
            "rhb" -> BrandBadgeConfig(
                label = accountName.ifBlank { "RHB" },
                emoji = "🔵",
                containerColor = Color(0xFFEFF6FF), // Blue 50
                contentColor = Color(0xFF1D4ED8),   // Blue 700
                borderColor = Color(0xFFBFDBFE),    // Blue 200
                iconRes = R.drawable.logo_rhb
            )
            "hlb" -> BrandBadgeConfig(
                label = accountName.ifBlank { "HLB" },
                emoji = "🔴",
                containerColor = Color(0xFFFEF2F2), // Red 50
                contentColor = Color(0xFFB91C1C),   // Red 700
                borderColor = Color(0xFFFECACA),    // Red 200
                iconRes = R.drawable.logo_hlb
            )
            "cash" -> BrandBadgeConfig(
                label = accountName.ifBlank { "现金" },
                emoji = "💵",
                containerColor = Color(0xFFECFDF5), // Emerald 50
                contentColor = Color(0xFF047857),   // Emerald 700
                borderColor = Color(0xFFA7F3D0),    // Emerald 200
                iconRes = R.drawable.ic_brand_cash
            )
            "card" -> BrandBadgeConfig(
                label = accountName.ifBlank { "银行卡" },
                emoji = "💳",
                containerColor = Color(0xFFF1F5F9), // Slate 100
                contentColor = Color(0xFF334155),   // Slate 700
                borderColor = Color(0xFFCBD5E1),    // Slate 300
                iconRes = R.drawable.ic_brand_card
            )
            else -> BrandBadgeConfig(
                label = accountName,
                emoji = "",
                containerColor = Color(0xFFF1F5F9), // Slate 100
                contentColor = Color(0xFF475569),   // Slate 600
                borderColor = Color.Transparent
            )
        }
    }
}

/**
 * 高辨识度的品牌专属矢量图标/徽标组件。
 * 针对 Maybank、TNG、Public Bank、CIMB、Grab 等优先渲染真实品牌 Logo，而非单调颜色圆点。
 */
@Composable
fun BrandIcon(
    config: SourceBrandBadgeHelper.BrandBadgeConfig,
    modifier: Modifier = Modifier.size(13.dp)
) {
    if (config.iconRes != null) {
        Image(
            painter = painterResource(id = config.iconRes),
            contentDescription = config.label,
            modifier = modifier
        )
    } else if (config.emoji.isNotBlank()) {
        Text(
            text = config.emoji,
            fontSize = 11.sp
        )
    }
}

/**
 * 来源与渠道品牌 Badge 组件。
 * 紧凑、微型、高辨识度，绝不挤压金额与商户。
 */
@Composable
fun SourceBrandBadge(
    source: String,
    channel: String?,
    note: String = "",
    isEdited: Boolean = false,
    fallbackBadgeText: String = "",
    fallbackBadgeEmoji: String = "",
    fallbackBadgeColor: String = "",
    modifier: Modifier = Modifier
) {
    val config = SourceBrandBadgeHelper.getBadgeConfig(
        source = source,
        channel = channel,
        note = note,
        fallbackBadgeText = fallbackBadgeText,
        fallbackBadgeEmoji = fallbackBadgeEmoji,
        fallbackBadgeColor = fallbackBadgeColor
    )

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = config.containerColor,
        border = BorderStroke(0.5.dp, config.borderColor),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
        ) {
            BrandIcon(config = config, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(3.5.dp))
            Text(
                text = config.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = config.contentColor
            )
            if (isEdited) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "✎",
                    fontSize = 9.sp,
                    color = config.contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}
