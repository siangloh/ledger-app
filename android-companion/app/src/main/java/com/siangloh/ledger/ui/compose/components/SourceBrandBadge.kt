package com.siangloh.ledger.ui.compose.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R

/**
 * 渠道品牌与来源徽标辅助工具与组件。
 * 针对 Maybank、Public Bank、Touch 'n Go、Grab、Shopee 等金融/支付渠道展示专属品牌色与 Logo 胶囊，
 * 同时对标准来源（手动、通知、导入等）严格按照多语言资源进行动态映射，绝不混入突兀的写死中文。
 */
object SourceBrandBadgeHelper {

    data class BrandBadgeConfig(
        val label: String,
        val emoji: String,
        val containerColor: Color,
        val contentColor: Color,
        val borderColor: Color
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

        // 1. 若为明确的金融/电子钱包渠道，优先呈现高辨识度品牌徽标
        when (normChannel) {
            "maybank" -> return BrandBadgeConfig(
                label = "Maybank",
                emoji = "🟡",
                containerColor = Color(0xFFFEF9C3), // Yellow 100
                contentColor = Color(0xFF854D0E),   // Yellow 800
                borderColor = Color(0xFFFDE047)    // Yellow 300
            )
            "tng" -> return BrandBadgeConfig(
                label = "TNG",
                emoji = "🔵",
                containerColor = Color(0xFFE0F2FE), // Sky 100
                contentColor = Color(0xFF0369A1),   // Sky 700
                borderColor = Color(0xFFBAE6FD)    // Sky 200
            )
            "public_bank" -> return BrandBadgeConfig(
                label = "PB",
                emoji = "🔴",
                containerColor = Color(0xFFFEE2E2), // Red 100
                contentColor = Color(0xFFB91C1C),   // Red 700
                borderColor = Color(0xFFFECACA)    // Red 200
            )
            "grab" -> return BrandBadgeConfig(
                label = "Grab",
                emoji = "🟢",
                containerColor = Color(0xFFDCFCE7), // Green 100
                contentColor = Color(0xFF15803D),   // Green 700
                borderColor = Color(0xFFBBF7D0)    // Green 200
            )
            "shopee" -> return BrandBadgeConfig(
                label = "Shopee",
                emoji = "🟠",
                containerColor = Color(0xFFFFEDD5), // Orange 100
                contentColor = Color(0xFFC2410C),   // Orange 700
                borderColor = Color(0xFFFED7AA)    // Orange 200
            )
            "cimb" -> return BrandBadgeConfig(
                label = "CIMB",
                emoji = "🔴",
                containerColor = Color(0xFFFFE4E6), // Rose 100
                contentColor = Color(0xFFBE123C),   // Rose 700
                borderColor = Color(0xFFFECDD3)    // Rose 200
            )
            "duitnow" -> return BrandBadgeConfig(
                label = "DuitNow",
                emoji = "🌸",
                containerColor = Color(0xFFFCE7F3), // Pink 100
                contentColor = Color(0xFFBE185D),   // Pink 700
                borderColor = Color(0xFFFBCFE8)    // Pink 200
            )
            "bank_card" -> return BrandBadgeConfig(
                label = stringResource(R.string.source_label_notification),
                emoji = "💳",
                containerColor = Color(0xFFEEF2FF), // Indigo 50
                contentColor = Color(0xFF4338CA),   // Indigo 700
                borderColor = Color(0xFFE0E7FF)    // Indigo 100
            )
        }

        // 2. 根据来源进行多语言本地化映射
        return when (normSource) {
            "notification", "auto_track", "auto_track_confirmed" -> BrandBadgeConfig(
                label = stringResource(R.string.source_label_notification),
                emoji = "📱",
                containerColor = com.siangloh.ledger.ui.compose.theme.PrimaryTealSubtle, // PrimaryTealSubtle
                contentColor = com.siangloh.ledger.ui.compose.theme.PrimaryTeal,         // PrimaryTeal
                borderColor = com.siangloh.ledger.ui.compose.theme.Teal100               // Teal 100
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
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            if (config.emoji.isNotBlank()) {
                Text(
                    text = config.emoji,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 3.dp)
                )
            }
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
