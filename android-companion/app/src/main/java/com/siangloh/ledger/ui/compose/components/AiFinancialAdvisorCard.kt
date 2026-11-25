package com.siangloh.ledger.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.LocalFinancialAdvisor
import com.siangloh.ledger.ui.compose.theme.PrimaryTeal
import com.siangloh.ledger.ui.compose.theme.Slate200
import com.siangloh.ledger.ui.compose.theme.Slate400
import com.siangloh.ledger.ui.compose.theme.Slate500
import com.siangloh.ledger.ui.compose.theme.Slate600
import com.siangloh.ledger.ui.compose.theme.Slate700
import com.siangloh.ledger.ui.compose.theme.Slate800
import com.siangloh.ledger.ui.compose.theme.Slate900
import com.siangloh.ledger.ui.compose.theme.Teal50

@Composable
fun AiFinancialAdvisorCard(
    report: LocalFinancialAdvisor.FinancialHealthReport,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    val (badgeBg, badgeText) = when {
        report.score >= 85 -> Pair(Color(0xFFD1FAE5), Color(0xFF059669)) // Emerald Green
        report.score >= 70 -> Pair(Color(0xFFCCFBF1), Color(0xFF0F766E)) // Teal
        report.score >= 55 -> Pair(Color(0xFFFEF3C7), Color(0xFFD97706)) // Amber
        else -> Pair(Color(0xFFFEE2E2), Color(0xFFDC2626))               // Red
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Teal50),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.ai_advisor_card_title).replace(Regex("^[\\p{So}\\p{Sk}\\uFE0F\\s]+"), ""),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.ai_advisor_score_label) + ": ${report.score} / 100",
                            fontSize = 11.sp,
                            color = Slate500
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Score Grade Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = "${report.score} · ${report.scoreGrade}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    // Refresh Button
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.ai_advisor_reanalyze_btn),
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Expand / Collapse Chevron
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Summary Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Teal50.copy(alpha = 0.6f)
            ) {
                Text(
                    text = report.summary,
                    fontSize = 12.sp,
                    color = Slate700,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            // 3-KPI Financial Snapshot Strip
            val currencyLabel = report.categoryHealth.firstOrNull()?.currency ?: "RM"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdvisorKpiChip(
                    label = stringResource(R.string.ai_advisor_kpi_daily_safe),
                    value = "$currencyLabel ${String.format(java.util.Locale.US, "%.0f", report.dailySafeAllowance)}",
                    valueColor = PrimaryTeal,
                    modifier = Modifier.weight(1f)
                )
                val projPositive = report.projectedMonthEndNet >= 0.0
                val projValue = if (report.hasProjection) {
                    val sign = if (projPositive) "+" else "-"
                    "$sign$currencyLabel ${String.format(java.util.Locale.US, "%.0f", kotlin.math.abs(report.projectedMonthEndNet))}"
                } else {
                    String.format(java.util.Locale.US, "%.1f%%", report.savingsRatePct)
                }
                AdvisorKpiChip(
                    label = if (report.hasProjection) stringResource(R.string.ai_advisor_kpi_proj_net)
                    else stringResource(R.string.ai_advisor_kpi_savings_rate),
                    value = projValue,
                    valueColor = if (!report.hasProjection || projPositive) Color(0xFF059669) else Color(0xFFDC2626),
                    modifier = Modifier.weight(1f)
                )
                val showEstSavedInThird = report.hasProjection
                val showSavingsInThird = report.runwayMonths == null && report.hasProjection
                val thirdVal = when {
                    showEstSavedInThird -> {
                        val savedPos = report.projectedTotalSaved >= 0.0
                        val sign = if (savedPos) "" else "-"
                        "$sign$currencyLabel ${String.format(java.util.Locale.US, "%,.0f", kotlin.math.abs(report.projectedTotalSaved))}"
                    }
                    report.runwayMonths != null -> stringResource(R.string.ai_advisor_kpi_runway_fmt, report.runwayMonths)
                    showSavingsInThird -> String.format(java.util.Locale.US, "%.1f%%", report.savingsRatePct)
                    else -> String.format(java.util.Locale.US, "%.0f%%", report.wantsPct)
                }
                val thirdColor = when {
                    showEstSavedInThird -> if (report.projectedTotalSaved >= 0.0) PrimaryTeal else Color(0xFFDC2626)
                    report.runwayMonths == null && showSavingsInThird -> PrimaryTeal
                    report.runwayMonths == null -> Color(0xFFD97706)
                    report.runwayMonths >= 6.0 -> Color(0xFF059669)
                    report.runwayMonths < 3.0 -> Color(0xFFD97706)
                    else -> PrimaryTeal
                }
                AdvisorKpiChip(
                    label = when {
                        showEstSavedInThird -> stringResource(R.string.ai_advisor_kpi_est_saved)
                        report.runwayMonths != null -> stringResource(R.string.ai_advisor_kpi_runway)
                        showSavingsInThird -> stringResource(R.string.ai_advisor_kpi_savings_rate)
                        else -> stringResource(R.string.ai_advisor_tag_optimize)
                    },
                    value = thirdVal,
                    valueColor = thirdColor,
                    modifier = Modifier.weight(1f)
                )
            }

            if (report.hasProjection && report.savingsDeposited > 0.0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = com.siangloh.ledger.ui.compose.theme.Slate50,
                    border = BorderStroke(1.dp, Slate200)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Savings,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(15.dp)
                        )
                        val totalSavedStr = "$currencyLabel ${String.format(java.util.Locale.US, "%,.0f", report.projectedTotalSaved)}"
                        val depositedStr = "$currencyLabel ${String.format(java.util.Locale.US, "%,.0f", report.savingsDeposited)}"
                        val remainingStr = "$currencyLabel ${String.format(java.util.Locale.US, "%,.0f", report.projectedMonthEndNet)}"
                        Text(
                            text = stringResource(R.string.ai_advisor_saved_breakdown_fmt, totalSavedStr, depositedStr, remainingStr),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate700,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 50/30/20 Spend Mix Bar
            val mixTotal = (report.needsPct + report.wantsPct + report.savedPct)
            if (mixTotal > 0.5) {
                SpendStructureBar(
                    needsPct = report.needsPct,
                    wantsPct = report.wantsPct,
                    savedPct = report.savedPct
                )
            }

            // Expandable Insight Items List
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                ) {
                    report.insights.forEach { item ->
                        InsightRow(item = item)
                    }
                    if (report.categoryHealth.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ai_advisor_cat_health_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        report.categoryHealth.forEach { catItem ->
                            CategoryHealthRow(item = catItem)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHealthRow(item: LocalFinancialAdvisor.CategoryHealthItem) {
    val (bg, fg) = when {
        !item.isOk -> Pair(Color(0xFFFEE2E2), Color(0xFFDC2626))
        item.isFixed -> Pair(Color(0xFFF1F5F9), Slate600)
        else -> Pair(Color(0xFFD1FAE5), Color(0xFF059669))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.localizedName,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Slate800,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${item.currency} ${String.format(java.util.Locale.US, "%,.2f", item.amount)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Slate600
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = bg
                ) {
                    Text(
                        text = item.statusLabel,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightRow(item: LocalFinancialAdvisor.FinancialInsightItem) {
    val (dotColor, tagBg, tagColor) = when (item.type) {
        LocalFinancialAdvisor.InsightType.WARNING -> Triple(Color(0xFFEF4444), Color(0xFFFEE2E2), Color(0xFFDC2626))
        LocalFinancialAdvisor.InsightType.POSITIVE -> Triple(Color(0xFF10B981), Color(0xFFD1FAE5), Color(0xFF059669))
        LocalFinancialAdvisor.InsightType.TIP -> Triple(PrimaryTeal, Teal50, PrimaryTeal)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = tagBg
                    ) {
                        Text(
                            text = item.tag,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = tagColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = item.title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate800
                    )
                }

                Text(
                    text = item.description,
                    fontSize = 11.5.sp,
                    color = Slate600,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun AdvisorKpiChip(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = Slate500,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpendStructureBar(
    needsPct: Double,
    wantsPct: Double,
    savedPct: Double
) {
    val total = (needsPct + wantsPct + savedPct).coerceAtLeast(1.0)
    val needsWeight = (needsPct / total).toFloat().coerceAtLeast(0.01f)
    val wantsWeight = (wantsPct / total).toFloat().coerceAtLeast(0.01f)
    val savedWeight = (savedPct / total).toFloat().coerceAtLeast(0.01f)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ai_advisor_mix_title),
            fontSize = 10.5.sp,
            color = Slate500,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Slate200)
        ) {
            if (needsPct > 0.0) {
                Box(modifier = Modifier.weight(needsWeight).height(8.dp).background(Color(0xFF64748B)))
            }
            if (wantsPct > 0.0) {
                Box(modifier = Modifier.weight(wantsWeight).height(8.dp).background(Color(0xFFF59E0B)))
            }
            if (savedPct > 0.0) {
                Box(modifier = Modifier.weight(savedWeight).height(8.dp).background(PrimaryTeal))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MixLegendDot(Color(0xFF64748B), stringResource(R.string.ai_advisor_mix_needs_fmt, needsPct))
            MixLegendDot(Color(0xFFF59E0B), stringResource(R.string.ai_advisor_mix_wants_fmt, wantsPct))
            MixLegendDot(PrimaryTeal, stringResource(R.string.ai_advisor_mix_save_fmt, savedPct))
        }
    }
}

@Composable
private fun MixLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text = label, fontSize = 10.5.sp, color = Slate700, fontWeight = FontWeight.SemiBold)
    }
}

