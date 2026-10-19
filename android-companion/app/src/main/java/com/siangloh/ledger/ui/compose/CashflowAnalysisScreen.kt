package com.siangloh.ledger.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
private fun amount(data: JSONObject, key: String): String =
    data.optString("currency") + " " + DecimalFormat("#,##0.00").format(data.optDouble(key, 0.0))
private fun formatMoney(currency: String, value: Double): String =
    currency + " " + DecimalFormat("#,##0.00").format(value)
private fun ratio(data: JSONObject, key: String): String =
    if (data.isNull(key)) "—" else DecimalFormat("0.0").format(data.optDouble(key)) + "%"
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
// 色彩与图表数据结构
// =========================================================================
private val ChartPalette = listOf(
    Color(0xFF0F766E), // Teal700
    Color(0xFF0284C7), // Sky600
    Color(0xFF6366F1), // Indigo500
    Color(0xFF8B5CF6), // Violet500
    Color(0xFFD946EF), // Fuchsia500
    Color(0xFFF43F5E), // Rose500
    Color(0xFFEA580C), // Orange600
    Color(0xFFD97706), // Amber600
    Color(0xFF10B981), // Emerald500
    Color(0xFF14B8A6), // Teal500
    Color(0xFF64748B), // Slate500
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

enum class CashflowDashboardTab {
    ALL,
    OVERVIEW,
    EXPENSE,
    INCOME,
    BILLS
}

// =========================================================================
// 环形/饼图核心渲染组件 (Native Compose Canvas Donut Chart)
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

    Box(
        modifier = modifier.size(chartSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(strokeWidth / 2)
        ) {
            val strokePx = strokeWidth.toPx()
            val canvasSize = size.minDimension
            val arcSize = Size(canvasSize, canvasSize)
            val topLeft = Offset(
                (size.width - canvasSize) / 2f,
                (size.height - canvasSize) / 2f
            )

            if (totalAmount <= 0.0 || slices.isEmpty()) {
                drawArc(
                    color = Color(0xFFE2E8F0),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx)
                )
            } else {
                var currentAngle = -90f
                val gap = if (slices.count { it.amount > 0 } > 1) 2.5f else 0f
                slices.forEachIndexed { index, slice ->
                    val sweep = ((slice.amount.coerceAtLeast(0.0) / totalAmount) * 360.0).toFloat()
                    if (sweep > 0f) {
                        val isSelected = selectedSliceIndex == index
                        val currentStroke = if (isSelected) strokePx * 1.25f else strokePx
                        val arcSweep = (sweep - gap).coerceAtLeast(0.5f)
                        drawArc(
                            color = slice.color,
                            startAngle = currentAngle + (gap / 2f),
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = currentStroke, cap = StrokeCap.Round)
                        )
                        currentAngle += sweep
                    }
                }
            }
        }

        // 中心指标内容
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(strokeWidth + 4.dp)
        ) {
            Text(
                text = centerTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = centerValue,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!centerSubtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = centerSubtitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

// =========================================================================
// 仪表盘核心 KPI 网格卡片
// =========================================================================
@Composable
private fun CashflowKpiDashboard(data: JSONObject, modifier: Modifier = Modifier) {
    val income = data.optDouble("income", 0.0)
    val expense = data.optDouble("expense", 0.0)
    val net = data.optDouble("net", 0.0)
    val reserved = data.optDouble("reserved", 0.0)
    val available = data.optDouble("available", 0.0)
    val deficit = data.optDouble("deficit", 0.0)
    val currency = data.optString("currency")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 第一行：实际收入 & 实际总支出
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)), // Emerald50
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFDCFCE7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓", color = IncomeGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.cf_income),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF166534)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatMoney(currency, income),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF14532D)
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2)), // Rose50
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFFE4E6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↑", color = ExpenseRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.cf_expense),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9F1239)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatMoney(currency, expense),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF881337)
                    )
                }
            }
        }

        // 第二行：结余 & 待付预留 & 预计可用
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.cf_net),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatMoney(currency, net),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (net >= 0) IncomeGreen else ExpenseRed
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)), // Amber50
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.cf_reserved),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF92400E)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatMoney(currency, reserved),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFB45309)
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1.1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (deficit > 0) Color(0xFFFEF2F2) else Color(0xFFF0FDFA) // Red50 or Teal50
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(if (deficit > 0) R.string.cf_deficit else R.string.cf_available),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (deficit > 0) Color(0xFF991B1B) else Color(0xFF115E59)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatMoney(currency, if (deficit > 0) deficit else available),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (deficit > 0) Color(0xFFB91C1C) else Color(0xFF0F766E)
                    )
                }
            }
        }
    }
}

// =========================================================================
// 模块 1: 收支资金分配环形图 (Cashflow Allocation Donut)
// =========================================================================
@Composable
private fun CashflowAllocationCard(data: JSONObject, modifier: Modifier = Modifier) {
    val income = data.optDouble("income", 0.0)
    val expense = data.optDouble("expense", 0.0).coerceAtLeast(0.0)
    val reserved = data.optDouble("reserved", 0.0).coerceAtLeast(0.0)
    val available = data.optDouble("available", 0.0).coerceAtLeast(0.0)
    val deficit = data.optDouble("deficit", 0.0)
    val currency = data.optString("currency")

    val slices = listOf(
        DonutSliceData(
            label = stringResource(R.string.cf_expense),
            amount = expense,
            percent = data.optDouble("expense_percent", 0.0),
            color = Color(0xFF0F766E) // Teal700
        ),
        DonutSliceData(
            label = stringResource(R.string.cf_pending),
            amount = reserved,
            percent = data.optDouble("reserve_percent", 0.0),
            color = Color(0xFFD97706) // Amber600
        ),
        DonutSliceData(
            label = stringResource(R.string.cf_available),
            amount = available,
            percent = data.optDouble("available_percent", 0.0),
            color = Color(0xFF10B981) // Emerald500
        )
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.cf_usage),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (income > 0) {
                    Text(
                        stringResource(R.string.cf_income) + ": " + formatMoney(currency, income),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (income > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CashflowDonutChart(
                        slices = slices,
                        centerTitle = if (deficit > 0) stringResource(R.string.cf_deficit) else stringResource(R.string.cf_available),
                        centerValue = formatMoney(currency, if (deficit > 0) deficit else available),
                        centerSubtitle = if (deficit > 0) "—" else ratio(data, "available_percent"),
                        chartSize = 180.dp,
                        strokeWidth = 24.dp
                    )
                }

                // 分类比例指示条
                val denominator = Math.max(income, expense + reserved)
                if (denominator > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                    ) {
                        listOf(
                            expense to Color(0xFF0F766E),
                            reserved to Color(0xFFD97706),
                            available to Color(0xFF10B981)
                        ).forEach { (value, color) ->
                            if (value > 0) {
                                Box(
                                    Modifier
                                        .weight((value / denominator).toFloat())
                                        .fillMaxHeight()
                                        .background(color)
                                )
                            }
                        }
                    }
                }

                // 统计图例 Pills
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    slices.forEach { slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
                            Spacer(Modifier.width(5.dp))
                            Column {
                                Text(slice.label, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    formatPct(slice.percent),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = slice.color
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.cf_no_ratio),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (deficit > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.cf_deficit) + ": " + formatMoney(currency, deficit),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 模块 2: 支出分类分布饼图 (Expense Categories Breakdown)
// =========================================================================
@Composable
private fun ExpenseCategoriesPieCard(
    data: JSONObject,
    onCategoryClick: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currency = data.optString("currency")
    val totalExpense = data.optDouble("expense", 0.0)
    val categories = data.rows("expense_categories")

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val slices = categories.mapIndexed { idx, group ->
        val rawLabel = group.optString("label")
        val label = if (rawLabel.isBlank() || rawLabel == "null") stringResource(R.string.cf_unknown)
                    else CategoryUiHelper.getLocalizedCategoryName(context, rawLabel)
        val amt = group.optDouble("amount", 0.0)
        val pct = group.optDouble("total_percent", 0.0)
        val sliceColor = if (rawLabel.isNotBlank()) CategoryUiHelper.getCategoryColor(rawLabel)
                         else ChartPalette[idx % ChartPalette.size]
        val emoji = CategoryUiHelper.getCategoryEmoji(rawLabel, "expense")
        val ids = group.optJSONArray("transaction_ids")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        DonutSliceData(label, amt, pct, sliceColor, emoji, ids)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.cf_expense_categories),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    stringResource(R.string.cf_donut_center_expense) + " " + formatMoney(currency, totalExpense),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (slices.isEmpty()) {
                Text(stringResource(R.string.cf_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                val activeSlice = selectedIndex?.let { slices.getOrNull(it) }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CashflowDonutChart(
                        slices = slices,
                        centerTitle = activeSlice?.label ?: stringResource(R.string.cf_donut_center_expense),
                        centerValue = formatMoney(currency, activeSlice?.amount ?: totalExpense),
                        centerSubtitle = activeSlice?.let { formatPct(it.percent) } ?: "${slices.size} 类",
                        chartSize = 200.dp,
                        strokeWidth = 26.dp,
                        selectedSliceIndex = selectedIndex
                    )
                }

                Text(
                    "分类支出占比排行",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEachIndexed { idx, slice ->
                        val isSelected = selectedIndex == idx
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedIndex = if (isSelected) null else idx
                                    if (slice.transactionIds.isNotEmpty()) {
                                        onCategoryClick(slice.transactionIds)
                                    }
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(slice.color.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(slice.iconEmoji.ifEmpty { "🏷️" }, fontSize = 14.sp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                slice.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                stringResource(R.string.cf_total_percent) + " " + formatPct(slice.percent),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            formatMoney(currency, slice.amount),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            "查看明细 →",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // 进度条指示
                                LinearProgressIndicator(
                                    progress = { (slice.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = slice.color,
                                    trackColor = Color(0xFFE2E8F0)
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
// 模块 3: 收入来源平台分布饼图 (Income Platforms Breakdown)
// =========================================================================
@Composable
private fun IncomePlatformsPieCard(
    data: JSONObject,
    onPlatformClick: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val currency = data.optString("currency")
    val totalIncome = data.optDouble("income", 0.0)
    val platforms = data.rows("income_platforms")

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val slices = platforms.mapIndexed { idx, group ->
        val rawLabel = group.optString("label")
        val label = if (rawLabel.isBlank() || rawLabel == "null") stringResource(R.string.cf_unknown) else rawLabel
        val amt = group.optDouble("amount", 0.0)
        val pct = group.optDouble("income_percent", 0.0)
        val sliceColor = ChartPalette[idx % ChartPalette.size]
        val emoji = CategoryUiHelper.getCategoryEmoji(rawLabel, "income")
        val ids = group.optJSONArray("transaction_ids")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        DonutSliceData(label, amt, pct, sliceColor, emoji, ids)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.cf_income_platforms),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    stringResource(R.string.cf_donut_center_income) + " " + formatMoney(currency, totalIncome),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (slices.isEmpty()) {
                Text(stringResource(R.string.cf_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                val activeSlice = selectedIndex?.let { slices.getOrNull(it) }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CashflowDonutChart(
                        slices = slices,
                        centerTitle = activeSlice?.label ?: stringResource(R.string.cf_donut_center_income),
                        centerValue = formatMoney(currency, activeSlice?.amount ?: totalIncome),
                        centerSubtitle = activeSlice?.let { formatPct(it.percent) } ?: "${slices.size} 渠道",
                        chartSize = 190.dp,
                        strokeWidth = 24.dp,
                        selectedSliceIndex = selectedIndex
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEachIndexed { idx, slice ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedIndex = if (selectedIndex == idx) null else idx
                                    if (slice.transactionIds.isNotEmpty()) onPlatformClick(slice.transactionIds)
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(24.dp).clip(CircleShape).background(slice.color),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(slice.iconEmoji.ifEmpty { "💰" }, fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            slice.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            stringResource(R.string.cf_income_percent) + " " + formatPct(slice.percent),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    formatMoney(currency, slice.amount),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
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
// 模块 4: 固定支出履行进度环 (Fixed Obligations Progress Card)
// =========================================================================
@Composable
private fun FixedObligationsProgressCard(
    data: JSONObject,
    onBillClick: (JSONObject) -> Unit,
    onRestoreBill: (String) -> Unit,
    isOnline: Boolean,
    isBusy: Boolean,
    modifier: Modifier = Modifier
) {
    val currency = data.optString("currency")
    val planned = data.optDouble("planned", 0.0)
    val paid = data.optDouble("paid", 0.0)
    val pending = data.optDouble("reserved", 0.0)
    val paymentPercent = data.optDouble("payment_percent", 0.0)
    val bills = data.rows("bills")
    val overdue = data.rows("prior_overdue")
    val inactive = data.rows("inactive_bills")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.cf_fixed),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            // 进度仪表卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 圆形进度指示
                    Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { (paymentPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 8.dp,
                            color = Teal700,
                            trackColor = Color(0xFFE2E8F0),
                            strokeCap = StrokeCap.Round
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatPct(paymentPercent),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Teal800
                            )
                            Text(
                                stringResource(R.string.cf_fixed_progress),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // 右侧指标详情
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_planned), style = MaterialTheme.typography.labelSmall)
                            Text(formatMoney(currency, planned), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_paid), style = MaterialTheme.typography.labelSmall, color = IncomeGreen)
                            Text(formatMoney(currency, paid), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = IncomeGreen)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_pending), style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                            Text(formatMoney(currency, pending), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = WarningAmber)
                        }
                    }
                }
            }

            // 本月应付账单列表
            if (bills.isNotEmpty()) {
                Text(
                    "本月固定账单清单",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                bills.forEach { bill ->
                    val isPaid = bill.optDouble("pending", 0.0) <= 0.0
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isOnline && !isBusy) { onBillClick(bill) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    bill.optString("title"),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    "到期: " + bill.optString("due_date") + " · 已付: " + formatMoney(currency, bill.optDouble("paid", 0.0)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatMoney(currency, bill.optDouble("amount", 0.0)),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isPaid) Color(0xFFDCFCE7) else Color(0xFFFEF3C7))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (isPaid) stringResource(R.string.cf_paid) else stringResource(R.string.cf_pending),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isPaid) Color(0xFF166534) else Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 历史逾期账单警示
            if (overdue.isNotEmpty()) {
                Text(
                    stringResource(R.string.cf_prior),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error
                )
                overdue.forEach { bill ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isOnline && !isBusy) { onBillClick(bill) },
                        color = Color(0xFFFEF2F2),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    bill.optString("title") + " · " + bill.optString("due_date"),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF991B1B)
                                )
                                Text(
                                    stringResource(R.string.cf_overdue) + " · 待付: " + formatMoney(currency, bill.optDouble("pending", 0.0)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB91C1C)
                                )
                            }
                            Text(
                                "管理 →",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 已停用/恢复账单
            if (inactive.isNotEmpty()) {
                Text(
                    stringResource(R.string.cf_inactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                inactive.forEach { bill ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            bill.optString("title") + " · " + bill.optString("due_date"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            enabled = isOnline && !isBusy,
                            onClick = { onRestoreBill(bill.optString("bill_key")) }
                        ) {
                            Text(stringResource(R.string.cf_restore))
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 概览页嵌入小部件卡片 (Preserved for OverviewScreen)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Teal50),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📊", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cf_title) + " · " + month,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.cf_open))
                }
            }

            data?.let { result ->
                if (result.cached) {
                    Text(
                        stringResource(R.string.cf_cached),
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningAmber
                    )
                }
                CashflowKpiDashboard(result.data)
            } ?: Text(
                stringResource(if (failed) R.string.cf_error else R.string.cf_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// =========================================================================
// 主屏幕: 收支分析 Dashboard (CashflowAnalysisScreen)
// =========================================================================
@OptIn(ExperimentalLayoutApi::class)
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

    var selectedIds by remember { mutableStateOf<List<Int>?>(null) }
    var selectedBill by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var correctedAmount by remember { mutableStateOf("") }

    var selectedTab by rememberSaveable { mutableStateOf(CashflowDashboardTab.ALL) }

    LaunchedEffect(month, currency, refresh) {
        loading = true
        failed = false
        result = null
        selectedIds = null
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶栏与月份/币种选择器
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.cf_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = { refresh++ }, enabled = !loading) {
                        Text("🔄", fontSize = 16.sp)
                    }
                }

                // 月份切换栏
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { changeMonth(-1) }) {
                            Text("← " + stringResource(R.string.prev_month))
                        }
                        Text(
                            month,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        TextButton(onClick = { changeMonth(1) }) {
                            Text(stringResource(R.string.next_month) + " →")
                        }
                    }
                }

                // 币种选择 Chips
                result?.data?.optJSONArray("currencies")?.let { codes ->
                    if (codes.length() > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until codes.length()).map { codes.getString(it) }) { code ->
                                val isSelected = (code == (currency.ifEmpty { result?.data?.optString("currency") ?: "" }))
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { currency = code },
                                    label = { Text(code) }
                                )
                            }
                        }
                    }
                }

                // 离线缓存提示
                if (result?.cached == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.cf_cached),
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningAmber
                    )
                }

                if (loading) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = Teal700)
                    }
                }

                if (failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.cf_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            result?.let { report ->
                val data = report.data
                val isOnline = !report.cached

                // Dashboard 分类切换选项卡 (Tabs)
                item {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        Tab(
                            selected = selectedTab == CashflowDashboardTab.ALL,
                            onClick = { selectedTab = CashflowDashboardTab.ALL },
                            text = { Text(stringResource(R.string.cf_tab_all)) }
                        )
                        Tab(
                            selected = selectedTab == CashflowDashboardTab.OVERVIEW,
                            onClick = { selectedTab = CashflowDashboardTab.OVERVIEW },
                            text = { Text(stringResource(R.string.cf_tab_overview)) }
                        )
                        Tab(
                            selected = selectedTab == CashflowDashboardTab.EXPENSE,
                            onClick = { selectedTab = CashflowDashboardTab.EXPENSE },
                            text = { Text(stringResource(R.string.cf_tab_expense)) }
                        )
                        Tab(
                            selected = selectedTab == CashflowDashboardTab.INCOME,
                            onClick = { selectedTab = CashflowDashboardTab.INCOME },
                            text = { Text(stringResource(R.string.cf_tab_income)) }
                        )
                        Tab(
                            selected = selectedTab == CashflowDashboardTab.BILLS,
                            onClick = { selectedTab = CashflowDashboardTab.BILLS },
                            text = { Text(stringResource(R.string.cf_tab_bills)) }
                        )
                    }
                }

                // 核心收支 KPI 统计卡片 (Always visible or in Overview/All)
                if (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.OVERVIEW) {
                    item {
                        CashflowKpiDashboard(data)
                    }
                    item {
                        CashflowAllocationCard(data)
                    }
                }

                // 支出分类饼图模块
                if (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.EXPENSE) {
                    item {
                        ExpenseCategoriesPieCard(
                            data = data,
                            onCategoryClick = { ids -> selectedIds = ids }
                        )
                    }
                }

                // 收入来源分布饼图模块
                if (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.INCOME) {
                    item {
                        IncomePlatformsPieCard(
                            data = data,
                            onPlatformClick = { ids -> selectedIds = ids }
                        )
                    }
                }

                // 固定开销履约进度与账单卡片
                if (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.BILLS) {
                    item {
                        FixedObligationsProgressCard(
                            data = data,
                            onBillClick = { bill ->
                                selectedBill = bill
                                correctedAmount = bill.optString("amount")
                            },
                            onRestoreBill = { billKey ->
                                update(JSONObject().put("action", "bill").put("bill_key", billKey))
                            },
                            isOnline = isOnline,
                            isBusy = busy
                        )
                    }
                }

                // 自动生成待确认定期交易 (Scheduled unconfirmed transactions)
                if (data.rows("scheduled_transactions").isNotEmpty() &&
                    (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.BILLS)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.cf_scheduled),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                data.rows("scheduled_transactions").forEach { tx ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "#${tx.optInt("id")} · ${tx.optString("date")} · ${tx.optString("note")}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                formatMoney(data.optString("currency"), tx.optDouble("amount", 0.0)),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                        TextButton(
                                            enabled = isOnline && !busy,
                                            onClick = {
                                                update(
                                                    JSONObject()
                                                        .put("action", "confirm")
                                                        .put("transaction_id", tx.optInt("id"))
                                                )
                                            }
                                        ) {
                                            Text(stringResource(R.string.cf_confirm))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 已确认定期交易撤销操作
                if (data.rows("confirmed_transactions").isNotEmpty() &&
                    (selectedTab == CashflowDashboardTab.ALL || selectedTab == CashflowDashboardTab.BILLS)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "已确认实际发生的定期交易",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                data.rows("confirmed_transactions").forEach { tx ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "#${tx.optInt("id")} · ${tx.optString("note")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = isOnline && !busy,
                                            onClick = {
                                                update(
                                                    JSONObject()
                                                        .put("action", "confirm")
                                                        .put("transaction_id", tx.optInt("id"))
                                                        .put("undo", true)
                                                )
                                            }
                                        ) {
                                            Text(stringResource(R.string.cf_undo_confirm))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 报表免责与时间说明
                item {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.cf_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (data.optBoolean("schedule_estimated")) {
                            Text(
                                stringResource(R.string.cf_estimate),
                                style = MaterialTheme.typography.bodySmall,
                                color = WarningAmber
                            )
                        }
                        Text(
                            stringResource(R.string.cf_updated) + ": " +
                                reportTime(data.optString("updated_at"), LocalContext.current.resources.configuration.locales[0]),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // 交易明细弹窗
    selectedIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { selectedIds = null },
            title = { Text(stringResource(R.string.cf_transactions)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val txs = result?.data?.rows("transactions")?.filter { it.optInt("id") in ids } ?: emptyList()
                    if (txs.isEmpty()) {
                        item { Text(stringResource(R.string.cf_empty)) }
                    } else {
                        items(txs) { tx ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        "#${tx.optInt("id")} · ${tx.optString("date")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        tx.optString("note").ifEmpty { tx.optString("category") },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        formatMoney(result?.data?.optString("currency") ?: "", tx.optDouble("amount", 0.0)),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (tx.optString("type") == "income") IncomeGreen else ExpenseRed
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedIds = null }) {
                    Text(stringResource(R.string.cf_back))
                }
            }
        )
    }

    // 账单详情与操作弹窗
    selectedBill?.let { bill ->
        val billKey = bill.optString("bill_key")
        val isOnline = !(result?.cached ?: false)

        AlertDialog(
            onDismissRequest = { if (!busy) selectedBill = null },
            title = { Text(bill.optString("title")) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (failed) {
                        item {
                            Text(stringResource(R.string.cf_error), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.cf_link),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    val candidateExpenses = ((result?.data?.rows("transactions") ?: emptyList()) +
                        (result?.data?.rows("scheduled_transactions") ?: emptyList()))
                        .filter { it.optString("type") == "expense" }
                    candidateExpenses.forEach { tx ->
                        item {
                            TextButton(
                                enabled = isOnline && !busy,
                                onClick = {
                                    update(
                                        JSONObject()
                                            .put("bill_key", billKey)
                                            .put("transaction_id", tx.optInt("id"))
                                    )
                                }
                            ) {
                                Text("#${tx.optInt("id")} · ${tx.optString("date")} · ${tx.optString("note")} · ${tx.optString("amount")}")
                            }
                        }
                    }

                    // 取消关联列表
                    val linked = result?.data?.rows("payment_links")?.filter { it.optString("bill_key") == billKey } ?: emptyList()
                    if (linked.isNotEmpty()) {
                        item {
                            Text(
                                "已关联支付",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        linked.forEach { link ->
                            item {
                                TextButton(
                                    enabled = isOnline && !busy,
                                    onClick = {
                                        update(
                                            JSONObject()
                                                .put("bill_key", billKey)
                                                .put("transaction_id", link.optInt("transaction_id"))
                                                .put("unlink", true)
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.cf_unlink) + " #" + link.optInt("transaction_id"))
                                }
                            }
                        }
                    }

                    // 关联退款
                    item {
                        Text(
                            stringResource(R.string.cf_refund),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    val candidateRefunds = result?.data?.rows("transactions")?.filter {
                        it.optString("type") == "income" && it.optDouble("amount") > 0
                    } ?: emptyList()
                    candidateRefunds.forEach { tx ->
                        item {
                            TextButton(
                                enabled = isOnline && !busy,
                                onClick = {
                                    update(
                                        JSONObject()
                                            .put("bill_key", billKey)
                                            .put("transaction_id", tx.optInt("id"))
                                            .put("refund", true)
                                    )
                                }
                            ) {
                                Text("#${tx.optInt("id")} · ${tx.optString("note")} · ${tx.optString("amount")}")
                            }
                        }
                    }

                    // 修改金额
                    item {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = correctedAmount,
                            onValueChange = { correctedAmount = it },
                            label = { Text(stringResource(R.string.cf_edit_amount)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            enabled = isOnline && !busy && correctedAmount.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true,
                            onClick = {
                                update(
                                    JSONObject()
                                        .put("action", "bill")
                                        .put("bill_key", billKey)
                                        .put("amount", correctedAmount)
                                )
                            }
                        ) {
                            Text(stringResource(R.string.cf_edit_amount))
                        }
                    }

                    // 标记重复
                    item {
                        Text(
                            stringResource(R.string.cf_duplicate),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    result?.data?.rows("bills")?.filter { it.optString("bill_key") != billKey }?.forEach { other ->
                        item {
                            TextButton(
                                enabled = isOnline && !busy,
                                onClick = {
                                    update(
                                        JSONObject()
                                            .put("action", "bill")
                                            .put("bill_key", billKey)
                                            .put("duplicate_of", other.optString("bill_key"))
                                    )
                                }
                            ) {
                                Text(other.optString("title") + " · " + other.optString("due_date"))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { selectedBill = null }) {
                    Text(stringResource(R.string.cf_back))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = isOnline && !busy,
                    onClick = {
                        update(
                            JSONObject()
                                .put("action", "bill")
                                .put("bill_key", billKey)
                                .put("cancelled", true)
                        )
                    }
                ) {
                    Text(stringResource(R.string.cf_cancel))
                }
            }
        )
    }
}
