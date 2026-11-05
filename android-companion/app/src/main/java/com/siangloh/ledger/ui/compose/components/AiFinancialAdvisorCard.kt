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
                        Text(text = "✨", fontSize = 16.sp)
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.ai_advisor_card_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
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

