package com.siangloh.ledger.ui.compose.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.ui.compose.theme.Slate900
import java.text.DecimalFormat
import java.util.Locale

/**
 * 高性能数字翻滚平滑滚动文本组件 (Animated Amount Text)
 *
 * 当月份切换、筛选或账目变动时，金额数字以物理惯性丝滑滑入滑出，
 * 避免生硬突兀的文本跳闪，带来顶级金融应用级视觉质感。
 */
@Composable
fun AnimatedAmountText(
    amount: Double,
    currency: String = "RM",
    fontSize: TextUnit = 20.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = Slate900,
    prefix: String = "",
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    val formatter = remember { DecimalFormat("#,##0.00") }
    val formattedStr = remember(amount, currency, prefix) {
        val sign = if (amount < 0) "-" else prefix
        val absAmt = Math.abs(amount)
        "$sign$currency ${formatter.format(absAmt)}"
    }

    AnimatedContent(
        targetState = formattedStr,
        transitionSpec = {
            val enterAnim = slideInVertically(initialOffsetY = { height -> height / 2 }) + fadeIn()
            val exitAnim = slideOutVertically(targetOffsetY = { height -> -height / 2 }) + fadeOut()
            enterAnim togetherWith exitAnim
        },
        label = "AnimatedAmountText",
        modifier = modifier
    ) { text ->
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

