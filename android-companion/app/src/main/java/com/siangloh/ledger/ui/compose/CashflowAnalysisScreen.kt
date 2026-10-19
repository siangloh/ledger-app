@file:OptIn(ExperimentalMaterial3Api::class)

package com.siangloh.ledger.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
// 工具函数与格式化
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
// 色彩规范与数据模型 (严格符合主 App 设计规范)
// =========================================================================
private val ExpenseColor = PrimaryTeal
private val ReservedColor = WarningAmber
private val AvailableColor = IncomeGreen
private val DangerColor = ExpenseRed
private val TrackColor = Slate200

private val ChartPalette = listOf(
    PrimaryTeal,
    Color(0xFF0284C7), // Sky600
    Color(0xFF6366F1), // Indigo500
    Color(0xFF8B5CF6), // Violet500
    Color(0xFFD946EF), // Fuchsia500
    Color(0xFFF43F5E), // Rose500
    Color(0xFFEA580C), // Orange600
    Color(0xFFD97706), // Amber600
    Color(0xFF10B981), // Emerald500
    Color(0xFF14B8A6), // Teal500
    Slate600,
    Color(0xFF84CC16)  // Lime600
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
// 通用小组件：进度条 / 分配条 / 标头 / 卡片 (完全对齐主 App)
// =========================================================================
@Composable
private fun BarTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
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

/** 收入分配条：实际支出 / 待付预留 / 可用结余 */
@Composable
private fun AllocationBar(
    income: Double,
    expense: Double,
    reserved: Double,
    available: Double,
    modifier: Modifier = Modifier,
    barHeight: Dp = 10.dp,
    track: Color = Slate200
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
private fun LegendDot(color: Color, label: String, value: String, labelColor: Color = Slate500, valueColor: Color = Slate900) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = 11.sp, color = labelColor)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

/** 统一卡片容器 (对齐 OverviewScreen 的 Card 样式) */
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                if (trailing != null) {
                    Text(
                        text = trailing,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (onTrailingClick != null) PrimaryTeal else Slate500,
                        modifier = if (onTrailingClick != null) Modifier.clickable { onTrailingClick() } else Modifier
                    )
                }
            }
            content()
        }
    }
}

/** 分类/渠道 排行项行 */
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
        color = if (selected) Teal50 else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(slice.color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { Text(slice.iconEmoji.ifEmpty { "🏷️" }, fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Slate900,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "#$rank · " + formatPct(slice.percent),
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
                Text(
                    formatMoney(currency, slice.amount),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                if (onClick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = Slate400)
                }
            }
            BarTrack(
                fraction = if (maxAmount > 0) (slice.amount / maxAmount).toFloat() else 0f,
                color = slice.color,
                height = 4.dp,
                track = Slate100
            )
        }
    }
}

// =========================================================================
// 环形图核心绘制组件
// =========================================================================
@Composable
private fun CashflowDonutChart(
    slices: List<DonutSliceData>,
    centerTitle: String,
    centerValue: String,
    centerSubtitle: String? = null,
    modifier: Modifier = Modifier,
    chartSize: Dp = 190.dp,
    strokeWidth: Dp = 22.dp,
    selectedSliceIndex: Int? = null
) {
    val totalAmount = slices.sumOf { it.amount.coerceAtLeast(0.0) }
    Box(modifier.size(chartSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(strokeWidth / 2 + 2.dp)) {
            val strokePx = strokeWidth.toPx()
            val canvasSize = size.minDimension
            val arcSize = Size(canvasSize, canvasSize)
            val topLeft = Offset((size.width - canvasSize) / 2f, (size.height - canvasSize) / 2f)
            if (totalAmount <= 0.0 || slices.isEmpty()) {
                drawArc(Slate100, 0f, 360f, false, topLeft, arcSize, style = Stroke(width = strokePx))
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
                            style = Stroke(width = if (isSel) strokePx * 1.2f else strokePx, cap = StrokeCap.Butt)
                        )
                        currentAngle += sweep
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(strokeWidth + 4.dp)
        ) {
            Text(
                centerTitle, fontSize = 11.sp,
                color = Slate500, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                centerValue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!centerSubtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    centerSubtitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryTeal,
                    maxLines = 1
                )
            }
        }
    }
}

// =========================================================================
// 概览页嵌入卡片 (OverviewScreen 使用，风格 100% 贴合主页)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
                Text(
                    stringResource(R.string.cf_open),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryTeal
                )
            }

            val result = data
            if (result == null) {
                Text(
                    stringResource(if (failed) R.string.cf_error else R.string.cf_loading),
                    fontSize = 12.sp,
                    color = if (failed) ExpenseRed else Slate500
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
                    Text(stringResource(R.string.cf_cached), fontSize = 11.sp, color = WarningAmber)
                }

                // 主数字：可用 / 赤字
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            stringResource(if (deficit > 0) R.string.cf_deficit else R.string.cf_available),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate500
                        )
                        Text(
                            formatMoney(currency, if (deficit > 0) deficit else available),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (deficit > 0) DangerColor else PrimaryTeal
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
                        LegendDot(ExpenseColor, stringResource(R.string.cf_expense), formatPct(d.optDouble("expense_percent", 0.0)))
                        LegendDot(ReservedColor, stringResource(R.string.cf_pending), formatPct(d.optDouble("reserve_percent", 0.0)))
                        LegendDot(AvailableColor, stringResource(R.string.cf_available), formatPct(d.optDouble("available_percent", 0.0)))
                    }
                } else {
                    Text(
                        stringResource(R.string.cf_no_ratio),
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }

                // Top 3 支出分类
                if (topCats.isNotEmpty()) {
                    HorizontalDivider(color = Slate100)
                    Text(
                        stringResource(R.string.cf_expense_categories),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate700
                    )
                    val maxAmt = topCats.maxOf { it.amount }
                    topCats.forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.iconEmoji.ifEmpty { "🏷️" } + " " + c.label, fontSize = 12.sp, color = Slate800, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatMoney(currency, c.amount) + " · " + formatPct(c.percent), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                            }
                            BarTrack(if (maxAmt > 0) (c.amount / maxAmt).toFloat() else 0f, c.color, height = 4.dp, track = Slate100)
                        }
                    }
                }

                // 固定账单进度
                if (planned > 0) {
                    HorizontalDivider(color = Slate100)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_fixed), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate700)
                        Text(
                            formatMoney(currency, paid) + " / " + formatMoney(currency, planned),
                            fontSize = 11.sp, color = Slate500, fontWeight = FontWeight.Medium
                        )
                    }
                    BarTrack((paid / planned).toFloat(), PrimaryTeal, height = 6.dp, track = Slate100)
                }
            }
        }
    }
}

// =========================================================================
// 概览 Tab: 暗黑渐变 Hero 核心看板 (完美复刻 SpendingHeroCard)
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
        income <= 0 -> "本月暂无正向收入，仅呈现支出与预留情况"
        deficit > 0 -> "⚠️ 支出与待付已超出收入 " + formatMoney(currency, deficit)
        usedPct >= 80 -> "已使用 " + formatPct(usedPct) + " 收入，请控制后续开支"
        else -> "财务状况健康，已使用 " + formatPct(usedPct) + " 收入"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B))))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(if (deficit > 0) R.string.cf_deficit else R.string.cf_available).uppercase(),
                    color = Slate400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = insight,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = formatMoney(currency, if (deficit > 0) deficit else available),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )

            if (income > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_usage), color = Slate400, fontSize = 11.sp)
                        Text(formatPct(usedPct), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    AllocationBar(income, expense, reserved, available, barHeight = 10.dp, track = Slate700)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LegendDot(Teal300, stringResource(R.string.cf_expense), formatPct(data.optDouble("expense_percent", 0.0)), Slate400, Color.White)
                    LegendDot(Amber500, stringResource(R.string.cf_pending), formatPct(data.optDouble("reserve_percent", 0.0)), Slate400, Color.White)
                    LegendDot(Emerald400, stringResource(R.string.cf_available), formatPct(data.optDouble("available_percent", 0.0)), Slate400, Color.White)
                }
            }

            HorizontalDivider(color = Slate800)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroStat(stringResource(R.string.cf_income), formatMoney(currency, income), Emerald400)
                HeroStat(stringResource(R.string.cf_expense), formatMoney(currency, expense), Color(0xFFF87171))
                HeroStat(stringResource(R.string.cf_net), formatMoney(currency, net), if (net >= 0) Emerald400 else Color(0xFFF87171))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Slate400, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 资金对比迷你柱状图 */
@Composable
private fun ComparisonBarChart(data: JSONObject) {
    val currency = data.optString("currency")
    val bars = listOf(
        Triple(stringResource(R.string.cf_income), data.optDouble("income", 0.0).coerceAtLeast(0.0), IncomeGreen),
        Triple(stringResource(R.string.cf_expense), data.optDouble("expense", 0.0).coerceAtLeast(0.0), PrimaryTeal),
        Triple(stringResource(R.string.cf_pending), data.optDouble("reserved", 0.0).coerceAtLeast(0.0), WarningAmber),
        Triple(stringResource(R.string.cf_available), data.optDouble("available", 0.0).coerceAtLeast(0.0), Emerald500)
    )
    val maxV = bars.maxOf { it.second }
    SectionCard(title = "资金流向对比", trailing = currency) {
        Row(
            Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            .height((80f * frac).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(color)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(label, fontSize = 11.sp, color = Slate600, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TopExpensePreviewCard(data: JSONObject, onSeeAll: () -> Unit, onClick: (TxSelection) -> Unit) {
    val currency = data.optString("currency")
    val slices = expenseSlices(data).take(5)
    SectionCard(
        title = "主要支出分类 Top " + slices.size.coerceAtLeast(1),
        trailing = if (slices.isNotEmpty()) "查看全部 →" else null,
        onTrailingClick = onSeeAll
    ) {
        if (slices.isEmpty()) {
            Text(stringResource(R.string.cf_empty), fontSize = 12.sp, color = Slate500)
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
    SectionCard(title = stringResource(R.string.cf_fixed), trailing = "管理账单 →", onTrailingClick = onOpen) {
        BarTrack((if (planned > 0) paid / planned else 0.0).toFloat(), PrimaryTeal, height = 8.dp, track = Slate100)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.cf_paid) + " " + formatMoney(currency, paid), fontSize = 12.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.cf_pending) + " " + formatMoney(currency, pending), fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.SemiBold)
        }
        if (overdueCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFEF2F2),
                border = BorderStroke(1.dp, Color(0xFFFCA5A5))
            ) {
                Text(
                    "⚠️ " + stringResource(R.string.cf_prior) + "：$overdueCount 笔",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 11.sp, color = Color(0xFF991B1B), fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// =========================================================================
// 支出 / 收入 Tab 组件
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
            Text(stringResource(R.string.cf_empty), fontSize = 12.sp, color = Slate500)
            return@SectionCard
        }
        val active = selectedIndex?.let { slices.getOrNull(it) }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            CashflowDonutChart(
                slices = slices,
                centerTitle = active?.label ?: totalLabel,
                centerValue = formatMoney(currency, active?.amount ?: total),
                centerSubtitle = active?.let { formatPct(it.percent) } ?: "${slices.size} $unitLabel",
                chartSize = 200.dp,
                strokeWidth = 24.dp,
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
// 账单 Tab 组件
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
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 1f }, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp, color = Slate100, trackColor = Color.Transparent, strokeCap = StrokeCap.Round
                )
                CircularProgressIndicator(
                    progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp, color = PrimaryTeal, trackColor = Color.Transparent, strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatPct(percent), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Slate900)
                    Text(stringResource(R.string.cf_fixed_progress), fontSize = 9.sp, color = Slate500)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BillStatLine(stringResource(R.string.cf_planned), formatMoney(currency, planned), Slate900)
                BillStatLine(stringResource(R.string.cf_paid), formatMoney(currency, paid), IncomeGreen)
                BillStatLine(stringResource(R.string.cf_pending), formatMoney(currency, pending), WarningAmber)
            }
        }
    }
}

@Composable
private fun BillStatLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = Slate500)
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        color = Slate50,
        border = BorderStroke(1.dp, if (overdue) Color(0xFFFCA5A5) else Slate200),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        bill.optString("title"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Slate900,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "到期 " + bill.optString("due_date"),
                        fontSize = 11.sp,
                        color = if (overdue) DangerColor else Slate500
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(currency, amount), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp)
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
            BarTrack(if (amount > 0) (paid / amount).toFloat() else 0f, accent, height = 4.dp, track = Slate200)
            if (!isPaid) {
                Text(
                    "已付 " + formatMoney(currency, paid) + " · 待付 " + formatMoney(currency, pending),
                    fontSize = 11.sp, color = Slate500
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
// 主屏幕: CashflowAnalysisScreen (对齐 ExpenseMY / OverviewScreen 设计风格)
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

    Surface(Modifier.fillMaxSize(), color = Slate50) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 顶栏: 返回 + 标题 + 刷新 ----------
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cf_back), tint = Slate800)
                    }
                    Text(
                        stringResource(R.string.cf_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    IconButton(onClick = { refresh++ }, enabled = !loading, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = PrimaryTeal)
                    }
                }

                // ---------- 月份切换器 (与 PeriodSelector 视觉完全统一) ----------
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { changeMonth(-1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.prev_month), tint = Slate600)
                        }
                        Text(
                            month,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Slate900
                        )
                        IconButton(onClick = { changeMonth(1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.next_month), tint = Slate600)
                        }
                    }
                }

                // ---------- 币种选择 Chips ----------
                result?.data?.optJSONArray("currencies")?.let { codes ->
                    if (codes.length() > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until codes.length()).map { codes.getString(it) }) { code ->
                                val isSelected = code == currency.ifEmpty { result?.data?.optString("currency") ?: "" }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Teal50 else Color.White,
                                    border = BorderStroke(1.dp, if (isSelected) PrimaryTeal else Slate200),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { currency = code }
                                ) {
                                    Text(
                                        text = code,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) PrimaryTeal else Slate600
                                    )
                                }
                            }
                        }
                    }
                }

                if (result?.cached == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.cf_cached), fontSize = 11.sp, color = WarningAmber)
                }
                if (loading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = PrimaryTeal, trackColor = Slate200)
                }
                if (failed) {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFEF2F2), border = BorderStroke(1.dp, Color(0xFFFCA5A5)), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_error), color = ExpenseRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh++ }) { Text("重试", color = ExpenseRed) }
                        }
                    }
                }
            }

            result?.let { report ->
                val data = report.data
                val cur = data.optString("currency")
                val isOnline = !report.cached

                // ---------- Segmented Tabs (完全复刻 PeriodSelector 的精致分段药丸) ----------
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Slate100,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                CashflowDashboardTab.OVERVIEW to R.string.cf_tab_overview,
                                CashflowDashboardTab.EXPENSE to R.string.cf_tab_expense,
                                CashflowDashboardTab.INCOME to R.string.cf_tab_income,
                                CashflowDashboardTab.BILLS to R.string.cf_tab_bills
                            ).forEach { (tab, label) ->
                                val isSelected = selectedTab == tab
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shadowElevation = if (isSelected) 1.5.dp else 0.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectedTab = tab }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text = stringResource(label),
                                            color = if (isSelected) PrimaryTeal else Slate600,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when (selectedTab) {
                    // ================= 概览 Tab =================
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

                    // ================= 支出 Tab =================
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

                    // ================= 收入 Tab =================
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

                    // ================= 账单 Tab =================
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
                                    Text(stringResource(R.string.cf_empty), fontSize = 12.sp, color = Slate500)
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
                                                Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(tx.optString("date") + " · " + formatMoney(cur, tx.optDouble("amount", 0.0)), fontSize = 11.sp, color = Slate500)
                                            }
                                            Button(
                                                enabled = isOnline && !busy,
                                                onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id"))) },
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) { Text(stringResource(R.string.cf_confirm), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
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
                                            Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, fontSize = 12.sp, color = Slate800, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            TextButton(
                                                enabled = isOnline && !busy,
                                                onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id")).put("undo", true)) }
                                            ) { Text(stringResource(R.string.cf_undo_confirm), fontSize = 11.sp, color = Slate600) }
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
                                            Text(bill.optString("title") + " · " + bill.optString("due_date"), fontSize = 12.sp, color = Slate500, modifier = Modifier.weight(1f))
                                            TextButton(enabled = isOnline && !busy, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key"))) }) {
                                                Text(stringResource(R.string.cf_restore), fontSize = 11.sp, color = PrimaryTeal)
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
                        Text(stringResource(R.string.cf_disclaimer), fontSize = 11.sp, color = Slate400)
                        if (data.optBoolean("schedule_estimated")) {
                            Text(stringResource(R.string.cf_estimate), fontSize = 11.sp, color = WarningAmber)
                        }
                        Text(
                            stringResource(R.string.cf_updated) + ": " + reportTime(data.optString("updated_at"), LocalContext.current.resources.configuration.locales[0]),
                            fontSize = 10.sp, color = Slate400
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
        ModalBottomSheet(onDismissRequest = { selection = null }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(sel.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Text("${txs.size} 笔交易", fontSize = 12.sp, color = Slate500)
                    }
                    Text(formatMoney(cur, sum), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryTeal)
                }
                if (txs.isEmpty()) {
                    Text(stringResource(R.string.cf_empty), fontSize = 12.sp, color = Slate500)
                } else {
                    val maxTx = txs.maxOf { it.optDouble("amount", 0.0) }
                    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(txs.sortedByDescending { it.optDouble("amount", 0.0) }) { tx ->
                            val isIncome = tx.optString("type") == "income"
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Slate50,
                                border = BorderStroke(1.dp, Slate200)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                tx.optString("note").ifEmpty { tx.optString("category") },
                                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate900,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(tx.optString("date") + " · #" + tx.optInt("id"), fontSize = 11.sp, color = Slate400)
                                        }
                                        Text(
                                            (if (isIncome) "+" else "-") + formatMoney(cur, tx.optDouble("amount", 0.0)),
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                            color = if (isIncome) IncomeGreen else ExpenseRed
                                        )
                                    }
                                    BarTrack(
                                        if (maxTx > 0) (tx.optDouble("amount", 0.0) / maxTx).toFloat() else 0f,
                                        if (isIncome) IncomeGreen else ExpenseRed, height = 4.dp, track = Slate200
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

        ModalBottomSheet(onDismissRequest = { if (!busy) selectedBill = null }, containerColor = Color.White) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 头部
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.optString("title"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            Text("到期 " + bill.optString("due_date"), fontSize = 12.sp, color = Slate500)
                        }
                        Text(formatMoney(cur, amount), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Slate900)
                    }
                    BarTrack(if (amount > 0) (paid / amount).toFloat() else 0f, PrimaryTeal, height = 8.dp, track = Slate100)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_paid) + " " + formatMoney(cur, paid), fontSize = 12.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.cf_pending) + " " + formatMoney(cur, pending), fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (failed) Text(stringResource(R.string.cf_error), color = ExpenseRed, fontSize = 12.sp)

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
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("保存", fontWeight = FontWeight.Bold) }
                    }
                }

                // 标记重复
                val others = result?.data?.rows("bills")?.filter { it.optString("bill_key") != billKey } ?: emptyList()
                if (others.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_duplicate)) {
                        others.forEach { other ->
                            SheetActionRow(
                                text = other.optString("title") + " · " + other.optString("due_date"),
                                action = stringResource(R.string.cf_duplicate),
                                enabled = isOnline && !busy
                            ) {
                                update(JSONObject().put("action", "bill").put("bill_key", billKey).put("duplicate_of", other.optString("bill_key")))
                            }
                        }
                    }
                }

                // 取消账单按钮
                Button(
                    enabled = isOnline && !busy,
                    onClick = {
                        update(JSONObject().put("action", "bill").put("bill_key", billKey).put("cancelled", true))
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF2F2), contentColor = ExpenseRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.cf_cancel), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Slate700
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
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, fontSize = 11.sp, color = Slate500)
            }
            TextButton(enabled = enabled, onClick = onClick) {
                Text(action, fontSize = 12.sp, color = PrimaryTeal, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}