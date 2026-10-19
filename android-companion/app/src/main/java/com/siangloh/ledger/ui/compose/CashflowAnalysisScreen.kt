@file:OptIn(ExperimentalMaterial3Api::class)

package com.siangloh.ledger.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.CashflowRepository
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// =========================================================================
// 工具函数
// =========================================================================
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
private fun formatMoney(currency: String, value: Double): String =
    currency + " " + DecimalFormat("#,##0.00").format(value)
private fun formatPct(value: Double?): String =
    if (value == null) "—" else DecimalFormat("0.0").format(value) + "%"

private fun reportTime(raw: String, locale: Locale): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val time = parser.parse(raw.take(19))
    if (time == null) "—" else java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale
    ).format(time)
} catch (error: java.text.ParseException) {
    android.util.Log.w("Cashflow", "Invalid report timestamp", error)
    "—"
}

// =========================================================================
// 色彩与数据结构
// =========================================================================
private val ExpenseColor = Color(0xFF0F766E)
private val ReservedColor = Color(0xFFD97706)
private val AvailableColor = Color(0xFF10B981)
private val DangerColor = Color(0xFFDC2626)
private val TrackColor = Color(0xFFE2E8F0)

private val ChartPalette = listOf(
    Color(0xFF0F766E), Color(0xFF0284C7), Color(0xFF6366F1), Color(0xFF8B5CF6),
    Color(0xFFD946EF), Color(0xFFF43F5E), Color(0xFFEA580C), Color(0xFFD97706),
    Color(0xFF10B981), Color(0xFF14B8A6), Color(0xFF64748B), Color(0xFF84CC16)
)

data class DonutSliceData(
    val label: String,
    val amount: Double,
    val percent: Double,
    val color: Color,
    val iconEmoji: String = "",
    val transactionIds: List<Int> = emptyList()
)

enum class CashflowDashboardTab { OVERVIEW, EXPENSE, INCOME, BILLS }

private data class TxSelection(val title: String, val ids: List<Int>)

@Composable
private fun expenseSlices(data: JSONObject): List<DonutSliceData> {
    val context = LocalContext.current
    val unknown = stringResource(R.string.cf_unknown)
    return data.rows("expense_categories").mapIndexed { idx, group ->
        val raw = group.optString("label")
        val blank = raw.isBlank() || raw == "null"
        DonutSliceData(
            label = if (blank) unknown else CategoryUiHelper.getLocalizedCategoryName(context, raw),
            amount = group.optDouble("amount", 0.0),
            percent = group.optDouble("total_percent", 0.0),
            color = if (!blank) CategoryUiHelper.getCategoryColor(raw) else ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "expense"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        )
    }.sortedByDescending { it.amount }
}

@Composable
private fun incomeSlices(data: JSONObject): List<DonutSliceData> {
    val unknown = stringResource(R.string.cf_unknown)
    return data.rows("income_platforms").mapIndexed { idx, group ->
        val raw = group.optString("label")
        DonutSliceData(
            label = if (raw.isBlank() || raw == "null") unknown else raw,
            amount = group.optDouble("amount", 0.0),
            percent = group.optDouble("income_percent", 0.0),
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "income"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        )
    }.sortedByDescending { it.amount }
}

// =========================================================================
// 通用小组件：进度条 / 分配条 / 卡片
// =========================================================================
@Composable
private fun BarTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    track: Color = TrackColor
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}

/** 收入去向分配条：支出 / 待付 / 可用 */
@Composable
private fun AllocationBar(
    income: Double,
    expense: Double,
    reserved: Double,
    available: Double,
    modifier: Modifier = Modifier,
    barHeight: Dp = 14.dp,
    track: Color = TrackColor
) {
    val e = expense.coerceAtLeast(0.0)
    val r = reserved.coerceAtLeast(0.0)
    val a = available.coerceAtLeast(0.0)
    val denom = maxOf(income, e + r)
    Row(
        modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(RoundedCornerShape(barHeight / 2))
            .background(track)
    ) {
        if (denom > 0) {
            listOf(e to ExpenseColor, r to ReservedColor, a to AvailableColor).forEach { (v, c) ->
                if (v > 0) Box(Modifier.weight((v / denom).toFloat()).fillMaxHeight().background(c))
            }
            val rest = denom - e - r - a
            if (rest > denom * 0.001) Spacer(Modifier.weight((rest / denom).toFloat()))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = 11.sp, color = labelColor)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                if (trailing != null) {
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (onTrailingClick != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (onTrailingClick != null) Modifier.clickable { onTrailingClick() } else Modifier
                    )
                }
            }
            content()
        }
    }
}

/** 一行：圆点 + 名称 + 金额 + 占比 + 进度条（支出/收入排行共用） */
@Composable
private fun RankedBarRow(
    rank: Int,
    slice: DonutSliceData,
    currency: String,
    maxAmount: Double,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(slice.color.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Text(slice.iconEmoji.ifEmpty { "🏷️" }, fontSize = 15.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "#$rank · " + formatPct(slice.percent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    formatMoney(currency, slice.amount),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (onClick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            BarTrack(
                fraction = if (maxAmount > 0) (slice.amount / maxAmount).toFloat() else 0f,
                color = slice.color,
                height = 6.dp
            )
        }
    }
}

// =========================================================================
// 环形图
// =========================================================================
@Composable
private fun CashflowDonutChart(
    slices: List<DonutSliceData>,
    centerTitle: String,
    centerValue: String,
    centerSubtitle: String? = null,
    modifier: Modifier = Modifier,
    chartSize: Dp = 190.dp,
    strokeWidth: Dp = 26.dp,
    selectedSliceIndex: Int? = null
) {
    val totalAmount = slices.sumOf { it.amount.coerceAtLeast(0.0) }
    Box(modifier.size(chartSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(strokeWidth / 2 + 4.dp)) {
            val strokePx = strokeWidth.toPx()
            val canvasSize = size.minDimension
            val arcSize = Size(canvasSize, canvasSize)
            val topLeft = Offset((size.width - canvasSize) / 2f, (size.height - canvasSize) / 2f)
            if (totalAmount <= 0.0 || slices.isEmpty()) {
                drawArc(TrackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = strokePx))
            } else {
                var currentAngle = -90f
                val gap = if (slices.count { it.amount > 0 } > 1) 2.5f else 0f
                slices.forEachIndexed { index, slice ->
                    val sweep = ((slice.amount.coerceAtLeast(0.0) / totalAmount) * 360.0).toFloat()
                    if (sweep > 0f) {
                        val isSel = selectedSliceIndex == index
                        val dim = selectedSliceIndex != null && !isSel
                        drawArc(
                            color = slice.color.copy(alpha = if (dim) 0.35f else 1f),
                            startAngle = currentAngle + gap / 2f,
                            sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = if (isSel) strokePx * 1.25f else strokePx, cap = StrokeCap.Butt)
                        )
                        currentAngle += sweep
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(strokeWidth + 8.dp)
        ) {
            Text(
                centerTitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                centerValue,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!centerSubtitle.isNullOrBlank()) {
                Text(
                    centerSubtitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary, maxLines = 1
                )
            }
        }
    }
}

// =========================================================================
// 概览页嵌入卡片 (OverviewScreen 使用，一眼看懂本月状况)
// =========================================================================
@Composable
fun CashflowSummaryCard(month: String, revision: Int = 0, onOpen: () -> Unit) {
    val context = LocalContext.current
    var data by remember(month) { mutableStateOf<CashflowRepository.Result?>(null) }
    var failed by remember(month) { mutableStateOf(false) }

    LaunchedEffect(month, revision) {
        data = null
        failed = false
        try {
            data = CashflowRepository.load(context, month)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("Cashflow", "Summary unavailable", error)
            failed = true
        }
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Teal50),
                        contentAlignment = Alignment.Center
                    ) { Text("📊", fontSize = 15.sp) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cf_title) + " · " + month,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Text(
                    stringResource(R.string.cf_open) + " →",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val result = data
            if (result == null) {
                Text(
                    stringResource(if (failed) R.string.cf_error else R.string.cf_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val d = result.data
                val currency = d.optString("currency")
                val income = d.optDouble("income", 0.0)
                val expense = d.optDouble("expense", 0.0)
                val reserved = d.optDouble("reserved", 0.0)
                val available = d.optDouble("available", 0.0)
                val deficit = d.optDouble("deficit", 0.0)
                val planned = d.optDouble("planned", 0.0)
                val paid = d.optDouble("paid", 0.0)
                val topCats = expenseSlices(d).take(3)

                if (result.cached) {
                    Text(stringResource(R.string.cf_cached), style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                }

                // 主数字：可用 / 赤字
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            stringResource(if (deficit > 0) R.string.cf_deficit else R.string.cf_available),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            formatMoney(currency, if (deficit > 0) deficit else available),
                            fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (deficit > 0) DangerColor else ExpenseColor
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.cf_income) + " " + formatMoney(currency, income),
                            fontSize = 11.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.cf_expense) + " " + formatMoney(currency, expense),
                            fontSize = 11.sp, color = ExpenseRed, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 收入分配条
                if (income > 0) {
                    AllocationBar(income, expense, reserved, available)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LegendDot(ExpenseColor, stringResource(R.string.cf_expense), formatPct(d.optDouble("expense_percent", 0.0)),
                            MaterialTheme.colorScheme.onSurfaceVariant, ExpenseColor)
                        LegendDot(ReservedColor, stringResource(R.string.cf_pending), formatPct(d.optDouble("reserve_percent", 0.0)),
                            MaterialTheme.colorScheme.onSurfaceVariant, ReservedColor)
                        LegendDot(AvailableColor, stringResource(R.string.cf_available), formatPct(d.optDouble("available_percent", 0.0)),
                            MaterialTheme.colorScheme.onSurfaceVariant, AvailableColor)
                    }
                } else {
                    Text(
                        stringResource(R.string.cf_no_ratio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Top 3 支出分类
                if (topCats.isNotEmpty()) {
                    HorizontalDivider(color = TrackColor)
                    Text(
                        stringResource(R.string.cf_expense_categories),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val maxAmt = topCats.maxOf { it.amount }
                    topCats.forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.iconEmoji.ifEmpty { "🏷️" } + " " + c.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatMoney(currency, c.amount) + " · " + formatPct(c.percent), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            BarTrack(if (maxAmt > 0) (c.amount / maxAmt).toFloat() else 0f, c.color, height = 6.dp)
                        }
                    }
                }

                // 固定账单进度
                if (planned > 0) {
                    HorizontalDivider(color = TrackColor)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_fixed), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            formatMoney(currency, paid) + " / " + formatMoney(currency, planned),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BarTrack((paid / planned).toFloat(), Teal700, height = 8.dp)
                }
            }
        }
    }
}

// =========================================================================
// 概览 Tab 组件
// =========================================================================
@Composable
private fun SummaryHeroCard(data: JSONObject) {
    val currency = data.optString("currency")
    val income = data.optDouble("income", 0.0)
    val expense = data.optDouble("expense", 0.0)
    val reserved = data.optDouble("reserved", 0.0)
    val available = data.optDouble("available", 0.0)
    val deficit = data.optDouble("deficit", 0.0)
    val net = data.optDouble("net", 0.0)
    val usedPct = if (income > 0) (expense + reserved) / income * 100.0 else 0.0

    val statusColor = when {
        deficit > 0 || usedPct > 100 -> Color(0xFFFCA5A5)
        usedPct >= 80 -> Color(0xFFFCD34D)
        else -> Color(0xFF6EE7B7)
    }
    val insight = when {
        income <= 0 -> "本月暂无收入记录，仅显示支出情况"
        deficit > 0 -> "⚠️ 支出与待付已超出收入 " + formatMoney(currency, deficit)
        usedPct >= 80 -> "已使用 " + formatPct(usedPct) + " 的收入，请留意开销"
        else -> "状况良好，已使用 " + formatPct(usedPct) + " 的收入"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF134E4A))))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(if (deficit > 0) R.string.cf_deficit else R.string.cf_available),
                color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Text(
                formatMoney(currency, if (deficit > 0) deficit else available),
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold
            )
            Text(insight, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

            if (income > 0) {
                // 收入使用进度
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_usage), color = Color(0xFFCBD5E1), fontSize = 11.sp)
                        Text(formatPct(usedPct), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    AllocationBar(income, expense, reserved, available, barHeight = 16.dp, track = Color(0xFF334155))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LegendDot(ExpenseColor, stringResource(R.string.cf_expense), formatPct(data.optDouble("expense_percent", 0.0)), Color(0xFF94A3B8), Color.White)
                    LegendDot(ReservedColor, stringResource(R.string.cf_pending), formatPct(data.optDouble("reserve_percent", 0.0)), Color(0xFF94A3B8), Color.White)
                    LegendDot(AvailableColor, stringResource(R.string.cf_available), formatPct(data.optDouble("available_percent", 0.0)), Color(0xFF94A3B8), Color.White)
                }
            }

            HorizontalDivider(color = Color(0xFF334155))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroStat(stringResource(R.string.cf_income), formatMoney(currency, income), Color(0xFF6EE7B7))
                HeroStat(stringResource(R.string.cf_expense), formatMoney(currency, expense), Color(0xFFFCA5A5))
                HeroStat(stringResource(R.string.cf_net), formatMoney(currency, net), if (net >= 0) Color(0xFF6EE7B7) else Color(0xFFFCA5A5))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 柱状图：收入 / 支出 / 待付 / 可用 一眼对比 */
@Composable
private fun ComparisonBarChart(data: JSONObject) {
    val currency = data.optString("currency")
    val bars = listOf(
        Triple(stringResource(R.string.cf_income), data.optDouble("income", 0.0).coerceAtLeast(0.0), IncomeGreen),
        Triple(stringResource(R.string.cf_expense), data.optDouble("expense", 0.0).coerceAtLeast(0.0), ExpenseColor),
        Triple(stringResource(R.string.cf_pending), data.optDouble("reserved", 0.0).coerceAtLeast(0.0), ReservedColor),
        Triple(stringResource(R.string.cf_available), data.optDouble("available", 0.0).coerceAtLeast(0.0), AvailableColor)
    )
    val maxV = bars.maxOf { it.second }
    SectionCard(title = "资金对比") {
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEach { (label, value, color) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(
                        DecimalFormat("#,##0").format(value),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    val frac = if (maxV > 0) (value / maxV).toFloat() else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((100f * frac).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(color)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Text(currency, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
    }
}

@Composable
private fun TopExpensePreviewCard(data: JSONObject, onSeeAll: () -> Unit, onClick: (TxSelection) -> Unit) {
    val currency = data.optString("currency")
    val slices = expenseSlices(data).take(5)
    SectionCard(
        title = "支出 Top " + slices.size.coerceAtLeast(1),
        trailing = if (slices.isNotEmpty()) "查看全部 →" else null,
        onTrailingClick = onSeeAll
    ) {
        if (slices.isEmpty()) {
            Text(stringResource(R.string.cf_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val maxAmt = slices.maxOf { it.amount }
            slices.forEachIndexed { i, s ->
                RankedBarRow(i + 1, s, currency, maxAmt, onClick = {
                    if (s.transactionIds.isNotEmpty()) onClick(TxSelection(s.label, s.transactionIds))
                })
            }
        }
    }
}

@Composable
private fun BillsSnapshotCard(data: JSONObject, onOpen: () -> Unit) {
    val currency = data.optString("currency")
    val planned = data.optDouble("planned", 0.0)
    val paid = data.optDouble("paid", 0.0)
    val pending = data.optDouble("reserved", 0.0)
    val overdueCount = data.rows("prior_overdue").size
    if (planned <= 0 && overdueCount == 0) return
    SectionCard(title = stringResource(R.string.cf_fixed), trailing = "查看账单 →", onTrailingClick = onOpen) {
        BarTrack((if (planned > 0) paid / planned else 0.0).toFloat(), Teal700, height = 10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.cf_paid) + " " + formatMoney(currency, paid), fontSize = 12.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.cf_pending) + " " + formatMoney(currency, pending), fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.SemiBold)
        }
        if (overdueCount > 0) {
            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFEF2F2)) {
                Text(
                    "⚠️ " + stringResource(R.string.cf_prior) + "：$overdueCount",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 12.sp, color = Color(0xFF991B1B), fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// =========================================================================
// 支出 / 收入 Tab
// =========================================================================
@Composable
private fun BreakdownCard(
    title: String,
    totalLabel: String,
    total: Double,
    currency: String,
    slices: List<DonutSliceData>,
    unitLabel: String,
    onItemClick: (TxSelection) -> Unit
) {
    var selectedIndex by remember(slices.size) { mutableStateOf<Int?>(null) }
    SectionCard(title = title, trailing = totalLabel + " " + formatMoney(currency, total)) {
        if (slices.isEmpty()) {
            Text(stringResource(R.string.cf_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val active = selectedIndex?.let { slices.getOrNull(it) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CashflowDonutChart(
                slices = slices,
                centerTitle = active?.label ?: totalLabel,
                centerValue = formatMoney(currency, active?.amount ?: total),
                centerSubtitle = active?.let { formatPct(it.percent) } ?: "${slices.size} $unitLabel",
                chartSize = 210.dp,
                strokeWidth = 26.dp,
                selectedSliceIndex = selectedIndex
            )
        }
        val maxAmt = slices.maxOf { it.amount }
        slices.forEachIndexed { idx, s ->
            RankedBarRow(
                rank = idx + 1, slice = s, currency = currency, maxAmount = maxAmt,
                selected = selectedIndex == idx,
                onClick = {
                    selectedIndex = if (selectedIndex == idx) null else idx
                    if (s.transactionIds.isNotEmpty()) onItemClick(TxSelection(s.label, s.transactionIds))
                }
            )
        }
    }
}

// =========================================================================
// 账单 Tab
// =========================================================================
@Composable
private fun BillProgressHero(data: JSONObject) {
    val currency = data.optString("currency")
    val planned = data.optDouble("planned", 0.0)
    val paid = data.optDouble("paid", 0.0)
    val pending = data.optDouble("reserved", 0.0)
    val percent = data.optDouble("payment_percent", 0.0)
    SectionCard(title = stringResource(R.string.cf_fixed)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 1f }, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 10.dp, color = TrackColor, trackColor = Color.Transparent, strokeCap = StrokeCap.Round
                )
                CircularProgressIndicator(
                    progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 10.dp, color = Teal700, trackColor = Color.Transparent, strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatPct(percent), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Teal800)
                    Text(stringResource(R.string.cf_fixed_progress), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BillStatLine(stringResource(R.string.cf_planned), formatMoney(currency, planned), MaterialTheme.colorScheme.onSurface)
                BillStatLine(stringResource(R.string.cf_paid), formatMoney(currency, paid), IncomeGreen)
                BillStatLine(stringResource(R.string.cf_pending), formatMoney(currency, pending), WarningAmber)
            }
        }
    }
}

@Composable
private fun BillStatLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun BillRow(bill: JSONObject, currency: String, overdue: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val amount = bill.optDouble("amount", 0.0)
    val paid = bill.optDouble("paid", 0.0)
    val pending = bill.optDouble("pending", 0.0)
    val isPaid = pending <= 0.0
    val accent = when {
        overdue -> DangerColor
        isPaid -> IncomeGreen
        else -> WarningAmber
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onClick() },
        color = accent.copy(alpha = 0.07f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        bill.optString("title"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "到期 " + bill.optString("due_date"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) DangerColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(currency, amount), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            when {
                                overdue -> stringResource(R.string.cf_overdue)
                                isPaid -> stringResource(R.string.cf_paid)
                                else -> stringResource(R.string.cf_pending)
                            },
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent
                        )
                    }
                }
            }
            BarTrack(if (amount > 0) (paid / amount).toFloat() else 0f, accent, height = 6.dp, track = Color(0xFFE2E8F0))
            if (!isPaid) {
                Text(
                    "已付 " + formatMoney(currency, paid) + " · 待付 " + formatMoney(currency, pending),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BillGroup(
    title: String,
    bills: List<JSONObject>,
    currency: String,
    overdue: Boolean,
    enabled: Boolean,
    onBillClick: (JSONObject) -> Unit
) {
    if (bills.isEmpty()) return
    SectionCard(title = title + " (${bills.size})") {
        bills.forEach { BillRow(it, currency, overdue, enabled) { onBillClick(it) } }
    }
}

// =========================================================================
// 主屏幕
// =========================================================================
@Composable
fun CashflowAnalysisScreen(initialMonth: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var currency by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<CashflowRepository.Result?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    var selection by remember { mutableStateOf<TxSelection?>(null) }
    var selectedBill by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var correctedAmount by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(CashflowDashboardTab.OVERVIEW) }

    LaunchedEffect(month, currency, refresh) {
        loading = true
        failed = false
        result = null
        selection = null
        try {
            result = CashflowRepository.load(context, month, currency)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("Cashflow", "Analysis unavailable", error)
            failed = true
        } finally {
            loading = false
        }
    }

    fun changeMonth(delta: Int) {
        val format = SimpleDateFormat("yyyy-MM", Locale.US)
        val calendar = Calendar.getInstance().apply {
            time = format.parse(month)!!
            add(Calendar.MONTH, delta)
        }
        month = format.format(calendar.time)
    }

    fun update(payload: JSONObject) {
        scope.launch {
            busy = true
            try {
                CashflowRepository.update(context, payload.put("month", month))
                selectedBill = null
                refresh++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("Cashflow", "Payment update rejected", error)
                failed = true
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 顶栏 + 月份 + 币种 ----------
            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cf_back)) }
                    Text(stringResource(R.string.cf_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = null) }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { changeMonth(-1) }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.prev_month)) }
                        Text(month, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        IconButton(onClick = { changeMonth(1) }) { Icon(Icons.Default.ChevronRight, stringResource(R.string.next_month)) }
                    }
                }

                result?.data?.optJSONArray("currencies")?.let { codes ->
                    if (codes.length() > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until codes.length()).map { codes.getString(it) }) { code ->
                                FilterChip(
                                    selected = code == currency.ifEmpty { result?.data?.optString("currency") ?: "" },
                                    onClick = { currency = code },
                                    label = { Text(code) }
                                )
                            }
                        }
                    }
                }

                if (result?.cached == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.cf_cached), style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                }
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = Teal700)
                }
                if (failed) {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFEF2F2), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh++ }) { Text("重试") }
                        }
                    }
                }
            }

            result?.let { report ->
                val data = report.data
                val cur = data.optString("currency")
                val isOnline = !report.cached

                // ---------- Tabs ----------
                item {
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        listOf(
                            CashflowDashboardTab.OVERVIEW to R.string.cf_tab_overview,
                            CashflowDashboardTab.EXPENSE to R.string.cf_tab_expense,
                            CashflowDashboardTab.INCOME to R.string.cf_tab_income,
                            CashflowDashboardTab.BILLS to R.string.cf_tab_bills
                        ).forEach { (tab, label) ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(stringResource(label), maxLines = 1) }
                            )
                        }
                    }
                }

                when (selectedTab) {
                    // ================= 概览 =================
                    CashflowDashboardTab.OVERVIEW -> {
                        item { SummaryHeroCard(data) }
                        item { ComparisonBarChart(data) }
                        item {
                            TopExpensePreviewCard(
                                data = data,
                                onSeeAll = { selectedTab = CashflowDashboardTab.EXPENSE },
                                onClick = { selection = it }
                            )
                        }
                        item { BillsSnapshotCard(data, onOpen = { selectedTab = CashflowDashboardTab.BILLS }) }
                    }

                    // ================= 支出 =================
                    CashflowDashboardTab.EXPENSE -> {
                        item {
                            BreakdownCard(
                                title = stringResource(R.string.cf_expense_categories),
                                totalLabel = stringResource(R.string.cf_donut_center_expense),
                                total = data.optDouble("expense", 0.0),
                                currency = cur,
                                slices = expenseSlices(data),
                                unitLabel = "类",
                                onItemClick = { selection = it }
                            )
                        }
                    }

                    // ================= 收入 =================
                    CashflowDashboardTab.INCOME -> {
                        item {
                            BreakdownCard(
                                title = stringResource(R.string.cf_income_platforms),
                                totalLabel = stringResource(R.string.cf_donut_center_income),
                                total = data.optDouble("income", 0.0),
                                currency = cur,
                                slices = incomeSlices(data),
                                unitLabel = "渠道",
                                onItemClick = { selection = it }
                            )
                        }
                    }

                    // ================= 账单 =================
                    CashflowDashboardTab.BILLS -> {
                        item { BillProgressHero(data) }

                        val overdue = data.rows("prior_overdue")
                        val bills = data.rows("bills")
                        val unpaid = bills.filter { it.optDouble("pending", 0.0) > 0.0 }
                        val settled = bills.filter { it.optDouble("pending", 0.0) <= 0.0 }
                        val pick: (JSONObject) -> Unit = { bill ->
                            selectedBill = bill
                            correctedAmount = bill.optString("amount")
                        }

                        item { BillGroup(stringResource(R.string.cf_prior), overdue, cur, true, isOnline && !busy, pick) }
                        item { BillGroup(stringResource(R.string.cf_pending), unpaid, cur, false, isOnline && !busy, pick) }
                        item { BillGroup(stringResource(R.string.cf_paid), settled, cur, false, isOnline && !busy, pick) }

                        if (bills.isEmpty() && overdue.isEmpty()) {
                            item {
                                SectionCard(title = stringResource(R.string.cf_fixed)) {
                                    Text(stringResource(R.string.cf_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        val scheduled = data.rows("scheduled_transactions")
                        if (scheduled.isNotEmpty()) {
                            item {
                                SectionCard(title = stringResource(R.string.cf_scheduled)) {
                                    scheduled.forEach { tx ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(tx.optString("date") + " · " + formatMoney(cur, tx.optDouble("amount", 0.0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            FilledTonalButton(
                                                enabled = isOnline && !busy,
                                                onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id"))) }
                                            ) { Text(stringResource(R.string.cf_confirm), fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }
                        }

                        val confirmed = data.rows("confirmed_transactions")
                        if (confirmed.isNotEmpty()) {
                            item {
                                SectionCard(title = "已确认的定期交易") {
                                    confirmed.forEach { tx ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            TextButton(
                                                enabled = isOnline && !busy,
                                                onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id")).put("undo", true)) }
                                            ) { Text(stringResource(R.string.cf_undo_confirm), fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }
                        }

                        val inactive = data.rows("inactive_bills")
                        if (inactive.isNotEmpty()) {
                            item {
                                SectionCard(title = stringResource(R.string.cf_inactive)) {
                                    inactive.forEach { bill ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(bill.optString("title") + " · " + bill.optString("due_date"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                            TextButton(enabled = isOnline && !busy, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key"))) }) {
                                                Text(stringResource(R.string.cf_restore))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------- 页脚说明 ----------
                item {
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.cf_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (data.optBoolean("schedule_estimated")) {
                            Text(stringResource(R.string.cf_estimate), style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                        }
                        Text(
                            stringResource(R.string.cf_updated) + ": " + reportTime(data.optString("updated_at"), LocalContext.current.resources.configuration.locales[0]),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // =========================================================================
    // 交易明细 Bottom Sheet
    // =========================================================================
    selection?.let { sel ->
        val cur = result?.data?.optString("currency") ?: ""
        val txs = result?.data?.rows("transactions")?.filter { it.optInt("id") in sel.ids } ?: emptyList()
        val sum = txs.sumOf { it.optDouble("amount", 0.0) }
        ModalBottomSheet(onDismissRequest = { selection = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(sel.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text("${txs.size} 笔交易", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatMoney(cur, sum), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.primary)
                }
                if (txs.isEmpty()) {
                    Text(stringResource(R.string.cf_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val maxTx = txs.maxOf { it.optDouble("amount", 0.0) }
                    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(txs.sortedByDescending { it.optDouble("amount", 0.0) }) { tx ->
                            val isIncome = tx.optString("type") == "income"
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                tx.optString("note").ifEmpty { tx.optString("category") },
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(tx.optString("date") + " · #" + tx.optInt("id"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            (if (isIncome) "+" else "-") + formatMoney(cur, tx.optDouble("amount", 0.0)),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isIncome) IncomeGreen else ExpenseRed
                                        )
                                    }
                                    BarTrack(
                                        if (maxTx > 0) (tx.optDouble("amount", 0.0) / maxTx).toFloat() else 0f,
                                        if (isIncome) IncomeGreen else ExpenseRed, height = 4.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 账单详情 / 操作 Bottom Sheet
    // =========================================================================
    selectedBill?.let { bill ->
        val billKey = bill.optString("bill_key")
        val isOnline = !(result?.cached ?: false)
        val cur = result?.data?.optString("currency") ?: ""
        val amount = bill.optDouble("amount", 0.0)
        val paid = bill.optDouble("paid", 0.0)
        val pending = bill.optDouble("pending", 0.0)

        ModalBottomSheet(onDismissRequest = { if (!busy) selectedBill = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 头部
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.optString("title"), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Text("到期 " + bill.optString("due_date"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatMoney(cur, amount), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                    }
                    BarTrack(if (amount > 0) (paid / amount).toFloat() else 0f, Teal700, height = 10.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_paid) + " " + formatMoney(cur, paid), fontSize = 12.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.cf_pending) + " " + formatMoney(cur, pending), fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (failed) Text(stringResource(R.string.cf_error), color = MaterialTheme.colorScheme.error)

                // 已关联的支付
                val linked = result?.data?.rows("payment_links")?.filter { it.optString("bill_key") == billKey } ?: emptyList()
                if (linked.isNotEmpty()) {
                    SheetSection("已关联支付") {
                        linked.forEach { link ->
                            SheetActionRow(
                                text = "#" + link.optInt("transaction_id"),
                                action = stringResource(R.string.cf_unlink),
                                enabled = isOnline && !busy
                            ) {
                                update(JSONObject().put("bill_key", billKey).put("transaction_id", link.optInt("transaction_id")).put("unlink", true))
                            }
                        }
                    }
                }

                // 关联支出
                val candidateExpenses = ((result?.data?.rows("transactions") ?: emptyList()) +
                    (result?.data?.rows("scheduled_transactions") ?: emptyList()))
                    .filter { it.optString("type") == "expense" }
                if (candidateExpenses.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_link)) {
                        candidateExpenses.take(20).forEach { tx ->
                            SheetActionRow(
                                text = tx.optString("date") + " · " + tx.optString("note").ifEmpty { "#" + tx.optInt("id") },
                                sub = formatMoney(cur, tx.optDouble("amount", 0.0)),
                                action = stringResource(R.string.cf_link),
                                enabled = isOnline && !busy
                            ) {
                                update(JSONObject().put("bill_key", billKey).put("transaction_id", tx.optInt("id")))
                            }
                        }
                    }
                }

                // 关联退款
                val candidateRefunds = result?.data?.rows("transactions")?.filter {
                    it.optString("type") == "income" && it.optDouble("amount") > 0
                } ?: emptyList()
                if (candidateRefunds.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_refund)) {
                        candidateRefunds.take(20).forEach { tx ->
                            SheetActionRow(
                                text = tx.optString("date") + " · " + tx.optString("note").ifEmpty { "#" + tx.optInt("id") },
                                sub = formatMoney(cur, tx.optDouble("amount", 0.0)),
                                action = stringResource(R.string.cf_link),
                                enabled = isOnline && !busy
                            ) {
                                update(JSONObject().put("bill_key", billKey).put("transaction_id", tx.optInt("id")).put("refund", true))
                            }
                        }
                    }
                }

                // 修改金额
                SheetSection(stringResource(R.string.cf_edit_amount)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = correctedAmount,
                            onValueChange = { correctedAmount = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            enabled = isOnline && !busy && correctedAmount.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true,
                            onClick = {
                                update(JSONObject().put("action", "bill").put("bill_key", billKey).put("amount", correctedAmount))
                            }
                        ) { Text("保存") }
                    }
                }

                // 标记重复
                val others = result?.data?.rows("bills")?.filter { it.optString("bill_key") != billKey } ?: emptyList()
                if (others.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_duplicate)) {
                        others.forEach { other ->
                            SheetActionRow(
                                text = other.optString("title") + " · " + other.optString("due_date"),
                                action = "标记",
                                enabled = isOnline && !busy
                            ) {
                                update(JSONObject().put("action", "bill").put("bill_key", billKey).put("duplicate_of", other.optString("bill_key")))
                            }
                        }
                    }
                }

                // 取消账单
                OutlinedButton(
                    enabled = isOnline && !busy,
                    onClick = { update(JSONObject().put("action", "bill").put("bill_key", billKey).put("cancelled", true)) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.cf_cancel)) }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun SheetActionRow(
    text: String,
    action: String,
    enabled: Boolean,
    sub: String? = null,
    onClick: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = enabled, onClick = onClick) { Text(action, fontSize = 12.sp) }
        }
    }
}