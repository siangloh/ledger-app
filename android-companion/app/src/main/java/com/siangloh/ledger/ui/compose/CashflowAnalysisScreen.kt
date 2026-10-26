@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.siangloh.ledger.ui.compose

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
private const val TRANSFER_LABEL = "转账"

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

private fun shiftMonth(month: String, delta: Int): String {
    val ymFormat = SimpleDateFormat("yyyy-MM", Locale.US)
    val c = Calendar.getInstance().apply {
        time = ymFormat.parse(month)!!
        add(Calendar.MONTH, delta)
    }
    return ymFormat.format(c.time)
}

private fun shiftDays(dateStr: String, deltaDays: Int): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { time = sdf.parse(dateStr)!! }
        cal.add(Calendar.DAY_OF_YEAR, deltaDays)
        sdf.format(cal.time)
    } catch (_: Exception) {
        dateStr
    }
}

private fun pctChange(cur: Double, prev: Double?): Double? =
    if (prev == null || prev <= 0.0) null else (cur - prev) / prev * 100.0

/** 当月返回今天是几号，否则 null */
private fun todayDayIfCurrent(month: String): Int? {
    val now = Calendar.getInstance()
    val cur = SimpleDateFormat("yyyy-MM", Locale.US).format(now.time)
    return if (cur == month) now.get(Calendar.DAY_OF_MONTH) else null
}

private fun daysInMonth(month: String): Int = try {
    Calendar.getInstance().apply { time = SimpleDateFormat("yyyy-MM", Locale.US).parse(month)!! }
        .getActualMaximum(Calendar.DAY_OF_MONTH)
} catch (e: Exception) { 30 }

/** 每日支出（不含转账），下标 0 = 1 号 */
private fun dailyExpense(data: JSONObject, month: String): DoubleArray {
    val arr = DoubleArray(daysInMonth(month))
    data.rows("transactions").forEach { tx ->
        if (tx.optString("type") != "expense" || tx.optString("category") == TRANSFER_LABEL) return@forEach
        val date = tx.optString("date")
        if (date.length < 10 || !date.startsWith(month)) return@forEach
        val d = date.substring(8, 10).toIntOrNull() ?: return@forEach
        if (d in 1..arr.size) arr[d - 1] += tx.optDouble("amount", 0.0)
    }
    return arr
}

private fun transferAmount(data: JSONObject): Double =
    data.rows("expense_categories")
        .filter { it.optString("label") == TRANSFER_LABEL }
        .sumOf { it.optDouble("amount", 0.0) }

private fun prevCategoryAmounts(prev: JSONObject?): Map<String, Double> =
    prev?.rows("expense_categories")?.associate { it.optString("label") to it.optDouble("amount", 0.0) } ?: emptyMap()

// =========================================================================
// 色彩与数据结构
// =========================================================================
private val ExpenseColor = PrimaryTeal
private val ReservedColor = Color(0xFFD97706)
private val AvailableColor = Color(0xFF10B981)
private val DangerColor = Color(0xFFDC2626)
private val TrackColor = Color(0xFFF1F5F9)

private val ChartPalette = listOf(
    PrimaryTeal, Color(0xFFF97316), Color(0xFF6366F1), Color(0xFF0EA5E9),
    Color(0xFFD946EF), Color(0xFFF43F5E), Color(0xFF84CC16), Color(0xFFD97706),
    Color(0xFF10B981), Color(0xFF8B5CF6), Color(0xFF14B8A6), Color(0xFF64748B)
)

data class DonutSliceData(
    val label: String,
    val amount: Double,
    val percent: Double,
    val color: Color,
    val iconEmoji: String = "",
    val transactionIds: List<Int> = emptyList(),
    val key: String = "",
    val incomePercent: Double? = null
)

enum class CashflowDashboardTab { OVERVIEW, EXPENSE, INCOME, SAVINGS, BILLS }

private data class TxSelection(val title: String, val ids: List<Int>)

@Composable
private fun expenseSlices(data: JSONObject, includeTransfer: Boolean): List<DonutSliceData> {
    val context = LocalContext.current
    val unknown = stringResource(R.string.cf_unknown)
    val rows = data.rows("expense_categories")
        .filter { includeTransfer || it.optString("label") != TRANSFER_LABEL }
        .sortedByDescending { it.optDouble("amount", 0.0) }
    val total = rows.sumOf { it.optDouble("amount", 0.0).coerceAtLeast(0.0) }
    return rows.mapIndexed { idx, group ->
        val raw = group.optString("label")
        val blank = raw.isBlank() || raw == "null"
        val amt = group.optDouble("amount", 0.0)
        DonutSliceData(
            label = if (blank) unknown else CategoryUiHelper.getLocalizedCategoryName(context, raw),
            amount = amt,
            percent = if (total > 0) amt / total * 100.0 else 0.0,
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "expense"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            key = raw,
            incomePercent = if (group.isNull("income_percent")) null else group.optDouble("income_percent")
        )
    }
}

@Composable
private fun expensePlatformSlices(data: JSONObject): List<DonutSliceData> {
    val unknown = stringResource(R.string.cf_unknown)
    val rows = data.rows("expense_platforms").sortedByDescending { it.optDouble("amount", 0.0) }
    val total = rows.sumOf { it.optDouble("amount", 0.0).coerceAtLeast(0.0) }
    return rows.mapIndexed { idx, group ->
        val raw = group.optString("label")
        val amt = group.optDouble("amount", 0.0)
        val pct = if (total > 0) amt / total * 100.0 else 0.0
        DonutSliceData(
            label = if (raw.isBlank() || raw == "null") unknown else raw,
            amount = amt,
            percent = pct,
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "expense"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            key = raw,
            incomePercent = if (group.isNull("income_percent")) null else group.optDouble("income_percent")
        )
    }
}

@Composable
private fun incomeSlices(data: JSONObject): List<DonutSliceData> {
    val unknown = stringResource(R.string.cf_unknown)
    return data.rows("income_platforms").sortedByDescending { it.optDouble("amount", 0.0) }.mapIndexed { idx, group ->
        val raw = group.optString("label")
        val pct = if (group.has("income_percent")) group.optDouble("income_percent", 0.0) else group.optDouble("total_percent", 0.0)
        DonutSliceData(
            label = if (raw.isBlank() || raw == "null") unknown else raw,
            amount = group.optDouble("amount", 0.0),
            percent = pct,
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "income"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            key = raw
        )
    }
}

@Composable
private fun savingsSlices(data: JSONObject): List<DonutSliceData> {
    val unknown = stringResource(R.string.cf_regular_savings)
    val rows = data.rows("savings_categories").sortedByDescending { it.optDouble("amount", 0.0) }
    val total = rows.sumOf { it.optDouble("amount", 0.0).coerceAtLeast(0.0) }
    return rows.mapIndexed { idx, group ->
        val raw = group.optString("label")
        val amt = group.optDouble("amount", 0.0)
        DonutSliceData(
            label = if (raw.isBlank() || raw == "null") unknown else raw,
            amount = amt,
            percent = if (total > 0) amt / total * 100.0 else 0.0,
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = CategoryUiHelper.getCategoryEmoji(raw, "savings"),
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            key = raw,
            incomePercent = if (group.isNull("income_percent")) null else group.optDouble("income_percent")
        )
    }
}

@Composable
private fun savingsPlatformSlices(data: JSONObject): List<DonutSliceData> {
    val unknown = stringResource(R.string.cf_unspecified_account)
    val rows = data.rows("savings_platforms").sortedByDescending { it.optDouble("amount", 0.0) }
    val total = rows.sumOf { it.optDouble("amount", 0.0).coerceAtLeast(0.0) }
    return rows.mapIndexed { idx, group ->
        val raw = group.optString("label")
        val amt = group.optDouble("amount", 0.0)
        DonutSliceData(
            label = if (raw.isBlank() || raw == "null") unknown else raw,
            amount = amt,
            percent = if (total > 0) amt / total * 100.0 else 0.0,
            color = ChartPalette[idx % ChartPalette.size],
            iconEmoji = "🏦",
            transactionIds = group.optJSONArray("transaction_ids")
                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            key = raw,
            incomePercent = if (group.isNull("income_percent")) null else group.optDouble("income_percent")
        )
    }
}

private data class PlatformFlowItem(
    val platform: String,
    val inflow: Double,
    val outflow: Double,
    val net: Double,
    val inTxIds: List<Int>,
    val outTxIds: List<Int>
)

@Composable
private fun PlatformCashflowCard(
    data: JSONObject,
    currency: String,
    onItemClick: (TxSelection) -> Unit
) {
    val incRows = data.rows("income_platforms")
    val expRows = data.rows("expense_platforms")
    val allNames = (incRows.map { it.optString("label") } + expRows.map { it.optString("label") })
        .filter { it.isNotBlank() && it != "null" }
        .distinct()

    if (allNames.isEmpty()) return

    val incMap = incRows.associateBy { it.optString("label") }
    val expMap = expRows.associateBy { it.optString("label") }

    val items = allNames.map { name ->
        val incObj = incMap[name]
        val expObj = expMap[name]
        val inflow = incObj?.optDouble("amount", 0.0) ?: 0.0
        val outflow = expObj?.optDouble("amount", 0.0) ?: 0.0
        val inIds = incObj?.optJSONArray("transaction_ids")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        val outIds = expObj?.optJSONArray("transaction_ids")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        PlatformFlowItem(
            platform = name,
            inflow = inflow,
            outflow = outflow,
            net = inflow - outflow,
            inTxIds = inIds,
            outTxIds = outIds
        )
    }.sortedByDescending { it.inflow + it.outflow }

    SectionCard(
        title = stringResource(R.string.cf_platform_summary),
        trailing = stringResource(R.string.cf_platform_count, items.size)
    ) {
        Text(stringResource(R.string.cf_platform_summary_hint), fontSize = 12.sp, color = Slate500)
        items.forEach { item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onItemClick(TxSelection(item.platform, item.inTxIds + item.outTxIds))
                    },
                color = Slate50,
                border = BorderStroke(1.dp, Slate200),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Teal50),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(CategoryUiHelper.getCategoryEmoji(item.platform, "platform").ifEmpty { "💳" }, fontSize = 16.sp)
                            }
                            Column {
                                Text(item.platform, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val netSign = if (item.net >= 0) "+" else ""
                                Text(
                                    stringResource(R.string.cf_net_flow) + ": " + netSign + formatMoney(currency, item.net),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (item.net >= 0) IncomeGreen else ExpenseRed
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.cf_money_in), fontSize = 11.sp, color = Slate400)
                                Text("+" + formatMoney(currency, item.inflow), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = IncomeGreen)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.cf_money_out), fontSize = 11.sp, color = Slate400)
                                Text("-" + formatMoney(currency, item.outflow), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ExpenseRed)
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = Slate400)
                    }

                    val totalFlow = item.inflow + item.outflow
                    if (totalFlow > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Slate200)
                        ) {
                            if (item.inflow > 0) {
                                Box(
                                    Modifier
                                        .weight((item.inflow / totalFlow).toFloat().coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(IncomeGreen)
                                )
                            }
                            if (item.outflow > 0) {
                                Box(
                                    Modifier
                                        .weight((item.outflow / totalFlow).toFloat().coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(ExpenseRed)
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
// 通用小组件
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
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(height / 2)).background(color)
        )
    }
}

/** 涨跌文字：↑12.3% / ↓5% */
@Composable
private fun DeltaText(
    change: Double?,
    upIsBad: Boolean,
    suffix: String = "",
    fontSize: TextUnit = 11.sp,
    onDark: Boolean = false
) {
    if (change == null) return
    val flat = kotlin.math.abs(change) < 0.5
    val up = change > 0
    val bad = if (up) upIsBad else !upIsBad
    val color = when {
        flat -> Slate400
        bad -> if (onDark) Color(0xFFFCA5A5) else DangerColor
        else -> if (onDark) Color(0xFF6EE7B7) else IncomeGreen
    }
    val arrow = if (flat) "→" else if (up) "↑" else "↓"
    Text(
        arrow + DecimalFormat("0.#").format(kotlin.math.abs(change)) + "%" + suffix,
        color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold
    )
}

/** 收入分配条：支出 / 待付 / 可用 */
@Composable
private fun AllocationBar(
    income: Double, expense: Double, reserved: Double, available: Double,
    modifier: Modifier = Modifier, barHeight: Dp = 14.dp, track: Color = TrackColor
) {
    val e = expense.coerceAtLeast(0.0)
    val r = reserved.coerceAtLeast(0.0)
    val a = available.coerceAtLeast(0.0)
    val denom = maxOf(income, e + r)
    Row(
        modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(barHeight / 2)).background(track)
    ) {
        if (denom > 0) {
            if (available < 0 && income > 0) {
                val base = minOf(income, e + r)
                val over = (e + r - income).coerceAtLeast(0.0)
                if (base > 0) {
                    Box(Modifier.weight((base / denom).toFloat()).fillMaxHeight().background(ExpenseColor))
                }
                if (over > 0) {
                    Box(Modifier.weight((over / denom).toFloat()).fillMaxHeight().background(DangerColor))
                }
            } else {
                listOf(e to ExpenseColor, r to ReservedColor, a to AvailableColor).forEach { (v, c) ->
                    if (v > 0) Box(Modifier.weight((v / denom).toFloat()).fillMaxHeight().background(c))
                }
                val rest = denom - e - r - a
                if (rest > denom * 0.001) Spacer(Modifier.weight((rest / denom).toFloat()))
            }
        }
    }
}

/** 账单进度：已付(绿) + 待付(橙) + 剩余(灰)，没付款时也能看到橙色待付 */
@Composable
private fun BillSegmentBar(paid: Double, pending: Double, planned: Double, height: Dp = 10.dp) {
    val p = paid.coerceAtLeast(0.0)
    val q = pending.coerceAtLeast(0.0)
    val denom = maxOf(planned, p + q)
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(TrackColor)) {
        if (denom > 0) {
            if (p > 0) Box(Modifier.weight((p / denom).toFloat()).fillMaxHeight().background(IncomeGreen))
            if (q > 0) Box(Modifier.weight((q / denom).toFloat()).fillMaxHeight().background(ReservedColor))
            val rest = denom - p - q
            if (rest > denom * 0.001) Spacer(Modifier.weight((rest / denom).toFloat()))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, value: String, labelColor: Color = Slate500, valueColor: Color = Slate900) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = labelColor)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
                if (trailing != null) {
                    if (onTrailingClick != null) {
                        TextButton(onClick = onTrailingClick, contentPadding = PaddingValues(0.dp)) {
                            Text(trailing, fontSize = 13.sp, color = PrimaryTeal)
                        }
                    } else Text(trailing, fontSize = 13.sp, color = Slate500)
                }
            }
            content()
        }
    }
}

/** 排行行：图标 + 名称 + 金额 + 占比 + 较上月 + 进度条 */
@Composable
private fun RankedBarRow(
    slice: DonutSliceData,
    currency: String,
    maxAmount: Double,
    change: Double? = null,
    selected: Boolean = false,
    showIncomeShare: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = if (selected) Teal50 else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(slice.color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text(slice.iconEmoji.ifEmpty { "🏷️" }, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        slice.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPct(slice.percent), fontSize = 11.sp, color = Slate500)
                        DeltaText(change, upIsBad = true, suffix = stringResource(R.string.cfu_vs_month), fontSize = 10.sp, onDark = false)
                    }
                }
                Text(formatMoney(currency, slice.amount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                if (onClick != null) {
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = Slate400)
                }
            }
            if (showIncomeShare) Text(stringResource(R.string.cfd_income_share, formatPct(slice.incomePercent)), fontSize = 12.sp, color = Slate600)
            BarTrack(if (maxAmount > 0) (slice.amount / maxAmount).toFloat() else 0f, slice.color, height = 6.dp, track = Slate100)
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
                            useCenter = false, topLeft = topLeft, size = arcSize,
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
            Text(centerTitle, fontSize = 11.sp, color = Slate500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(centerValue, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!centerSubtitle.isNullOrBlank()) {
                Text(centerSubtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PrimaryTeal, maxLines = 1)
            }
        }
    }
}

// =========================================================================
// 概览页嵌入卡片 (OverviewScreen 使用)
// =========================================================================
@Composable
fun CashflowSummaryCard(month: String, revision: Int = 0, onOpen: () -> Unit) {
    val context = LocalContext.current
    var result by remember(month) { mutableStateOf(CashflowRepository.getCached(context, month)) }
    var failed by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month, revision) {
        result = CashflowRepository.getCached(context, month)
        failed = false
        try { result = CashflowRepository.load(context, month, forceRefresh = true) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            android.util.Log.w("Cashflow", "Summary unavailable", error)
            failed = result == null
        }
    }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Slate200)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cfd_summary_title) + " · " + month, fontWeight = FontWeight.Bold, color = Slate900)
            val report = result
            if (report == null) {
                Text(stringResource(if (failed) R.string.cf_error else R.string.cf_loading), color = Slate500)
            } else {
                val data = report.data
                val currency = data.optString("currency")
                val income = data.optDouble("income")
                val expense = data.optDouble("expense")
                val reserved = data.optDouble("reserved")
                val available = data.optDouble("available")
                Text(stringResource(R.string.cf_available), fontSize = 12.sp, color = Slate500)
                Text(formatMoney(currency, available), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (available < 0) ExpenseRed else PrimaryTeal)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cf_income), fontSize = 12.sp, color = Slate500)
                        Text(formatMoney(currency, income), fontWeight = FontWeight.SemiBold, color = Slate900)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cf_expense), fontSize = 12.sp, color = Slate500)
                        Text(formatMoney(currency, expense), fontWeight = FontWeight.SemiBold, color = Slate900)
                    }
                }
                if (reserved > 0) {
                    LegendDot(ReservedColor, stringResource(R.string.cf_reserved), formatMoney(currency, reserved))
                }
                if (income > 0 || expense > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(ExpenseColor))
                                Text(stringResource(R.string.cf_used_prefix) + formatMoney(currency, expense), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Slate800)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (available < 0) DangerColor else AvailableColor))
                                Text((if (available < 0) stringResource(R.string.cf_deficit_prefix) else stringResource(R.string.cf_remaining_prefix)) + formatMoney(currency, available),
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (available < 0) ExpenseRed else AvailableColor)
                            }
                        }
                        AllocationBar(income, expense, reserved, available, track = Slate100, barHeight = 14.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.cf_expended_prefix) + formatPct(data.optDouble("expense_percent")), fontSize = 11.sp, color = Slate500)
                            if (reserved > 0) {
                                Text(stringResource(R.string.cf_reserved_prefix) + formatPct(data.optDouble("reserve_percent")), fontSize = 11.sp, color = Slate500)
                            }
                            Text((if (available < 0) stringResource(R.string.cf_deficit_prefix) else stringResource(R.string.cf_available_prefix)) + formatPct(data.optDouble("available_percent")),
                                fontSize = 11.sp, color = if (available < 0) ExpenseRed else AvailableColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else Text(stringResource(R.string.cf_no_ratio), fontSize = 11.sp, color = Slate500)
                Text(stringResource(R.string.cfd_estimate_note), fontSize = 11.sp, color = Slate500)
                if (report.cached) Text(stringResource(R.string.cf_cached), fontSize = 11.sp, color = WarningAmber)
            }
            Text(stringResource(R.string.cf_open), color = PrimaryTeal, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 每日支出柱状图（可点击 / 拖动查看某天） */
@Composable
private fun DailySpendingCard(daily: DoubleArray, currency: String, todayDay: Int?) {
    val total = daily.sum()
    var sel by remember(total) { mutableStateOf<Int?>(null) }
    val maxV = daily.maxOrNull() ?: 0.0
    val maxDay = if (maxV > 0) daily.indexOfFirst { it == maxV } + 1 else null
    val elapsed = todayDay ?: daily.size
    val avg = if (elapsed > 0) total / elapsed else 0.0
    val primary = ExpenseColor
    val peak = Color(0xFFF97316)
    val future = Slate200

    SectionCard(
        title = stringResource(R.string.cfu_daily),
        trailing = sel?.let { stringResource(R.string.cfu_day, it + 1) + " " + formatMoney(currency, daily[it]) } ?: maxDay?.let { stringResource(R.string.cfu_peak_day, it) }
    ) {
        if (total <= 0) {
            Text(stringResource(R.string.cf_empty), fontSize = 13.sp, color = Slate500)
            return@SectionCard
        }
        val n = daily.size
        Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .pointerInput(daily) {
                    detectTapGestures { off ->
                        val idx = (off.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                        sel = if (sel == idx) null else idx
                    }
                }
                .pointerInput(daily) {
                    detectDragGestures(
                        onDragStart = { off -> sel = (off.x / (size.width / n)).toInt().coerceIn(0, n - 1) },
                        onDrag = { change, _ -> sel = (change.position.x / (size.width / n)).toInt().coerceIn(0, n - 1) }
                    )
                }
        ) {
            val slot = size.width / n
            val barW = slot * 0.62f
            val usable = size.height - 4.dp.toPx()
            for (i in 0 until n) {
                val v = daily[i]
                val h = if (maxV > 0) (v / maxV * usable).toFloat() else 0f
                val isFuture = todayDay != null && i + 1 > todayDay
                val color = when {
                    sel == i -> primary
                    v > 0 && v == maxV -> peak
                    isFuture -> future
                    v > 0 -> primary.copy(alpha = 0.55f)
                    else -> future
                }
                val barH = if (v > 0) h.coerceAtLeast(3.dp.toPx()) else 2.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }
            // 日均线
            if (avg > 0) {
                val y = size.height - (avg / maxV * usable).toFloat()
                drawLine(
                    color = peak, start = Offset(0f, y), end = Offset(size.width, y),
                    strokeWidth = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1, 8, 15, 22, n).forEach { Text(stringResource(R.string.cfu_day, it), fontSize = 10.sp, color = Slate500) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.cfu_avg_label, formatMoney(currency, avg)), fontSize = 11.sp, color = peak, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.cfu_active_days, daily.count { it > 0 }), fontSize = 11.sp, color = Slate500)
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
    currency: String,
    slices: List<DonutSliceData>,
    unitLabel: String,
    showIncomeShare: Boolean = false,
    prevMap: Map<String, Double> = emptyMap(),
    headerExtra: (@Composable ColumnScope.() -> Unit)? = null,
    onItemClick: (TxSelection) -> Unit
) {
    var selectedIndex by remember(slices.size) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { it.amount.coerceAtLeast(0.0) }
    SectionCard(title = title, trailing = totalLabel + " " + formatMoney(currency, total)) {
        headerExtra?.invoke(this)
        if (slices.isEmpty()) {
            Text(stringResource(R.string.cf_empty), style = MaterialTheme.typography.bodyMedium, color = Slate500)
            return@SectionCard
        }

        // 常驻在顶部展示占比图，无需用户额外点击展开
        val active = selectedIndex?.let { slices.getOrNull(it) }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
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

        Text(stringResource(if (showIncomeShare) R.string.cfd_expense_basis else R.string.cfd_income_basis), fontSize = 12.sp, color = Slate600)
        Text(stringResource(R.string.cfu_tap_details), fontSize = 12.sp, color = Slate500)
        val maxAmt = slices.maxOf { it.amount }
        slices.forEachIndexed { idx, s ->
            RankedBarRow(
                slice = s, currency = currency, maxAmount = maxAmt,
                change = pctChange(s.amount, prevMap[s.key]),
                selected = selectedIndex == idx,
                showIncomeShare = showIncomeShare,
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
                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), strokeWidth = 10.dp, color = TrackColor, trackColor = Color.Transparent, strokeCap = StrokeCap.Round)
                CircularProgressIndicator(progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 10.dp, color = Teal700, trackColor = Color.Transparent, strokeCap = StrokeCap.Round)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatPct(percent), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Teal800)
                    Text(stringResource(R.string.cf_fixed_progress), fontSize = 9.sp, color = Slate500)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BillStatLine(stringResource(R.string.cf_planned), formatMoney(currency, planned), Slate900)
                BillStatLine(stringResource(R.string.cf_paid), formatMoney(currency, paid), IncomeGreen)
                BillStatLine(stringResource(R.string.cf_pending), formatMoney(currency, pending), WarningAmber)
            }
        }
        BillSegmentBar(paid, pending, planned)
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
    val accent = when { overdue -> DangerColor; isPaid -> IncomeGreen; else -> WarningAmber }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onClick() },
        color = Slate50,
        border = BorderStroke(1.dp, Slate200),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bill.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.cfu_due) + bill.optString("due_date"), fontSize = 11.sp, color = if (overdue) DangerColor else Slate500)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(currency, amount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(
                            when { overdue -> stringResource(R.string.cf_overdue); isPaid -> stringResource(R.string.cf_paid); else -> stringResource(R.string.cf_pending) },
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent
                        )
                    }
                }
            }
            BarTrack(if (amount > 0) (paid / amount).toFloat() else 0f, accent, height = 6.dp, track = Slate200)
            if (!isPaid) {
                Text(
                    if (paid <= 0) stringResource(R.string.cfu_awaiting) + formatMoney(currency, pending)
                    else stringResource(R.string.cfu_paid) + formatMoney(currency, paid) + stringResource(R.string.cfu_pending) + formatMoney(currency, pending),
                    fontSize = 11.sp, color = Slate500
                )
            }
        }
    }
}

@Composable
private fun BillGroup(title: String, bills: List<JSONObject>, currency: String, overdue: Boolean, enabled: Boolean, onBillClick: (JSONObject) -> Unit) {
    if (bills.isEmpty()) return
    SectionCard(title = title + " (${bills.size})") {
        bills.forEach { BillRow(it, currency, overdue, enabled) { onBillClick(it) } }
    }
}

// =========================================================================
// 主屏幕
// =========================================================================
@Composable
fun CashflowAnalysisScreen(
    initialMonth: String,
    initialStartDate: String? = null,
    initialEndDate: String? = null,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var isCustomRange by rememberSaveable { mutableStateOf(!initialStartDate.isNullOrBlank() && !initialEndDate.isNullOrBlank()) }
    val todayCal = remember { Calendar.getInstance() }
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(todayCal.time) }
    var startDate by rememberSaveable { mutableStateOf(initialStartDate ?: "$initialMonth-01") }
    var endDate by rememberSaveable { mutableStateOf(initialEndDate ?: todayStr) }
    var currency by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf(CashflowRepository.getCached(context, initialMonth, currency)) }
    var prevData by remember { mutableStateOf(CashflowRepository.getCached(context, shiftMonth(initialMonth, -1), currency)?.data) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(result == null) }

    var selection by remember { mutableStateOf<TxSelection?>(null) }
    var selectedBill by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var correctedAmount by remember { mutableStateOf("") }
    val detailPages = listOf(
        CashflowDashboardTab.EXPENSE,
        CashflowDashboardTab.INCOME,
        CashflowDashboardTab.SAVINGS,
        CashflowDashboardTab.BILLS
    )
    val pager = rememberPagerState(pageCount = { detailPages.size })
    var includeTransfer by rememberSaveable { mutableStateOf(false) }
    var showCharts by rememberSaveable { mutableStateOf(false) }
    var expenseViewMode by rememberSaveable { mutableIntStateOf(0) }
    var savingsViewMode by rememberSaveable { mutableIntStateOf(0) }

    fun pickStartDate() {
        val cal = Calendar.getInstance()
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(startDate)
            if (d != null) cal.time = d
        } catch (_: Exception) {}
        DatePickerDialog(
            context,
            { _, year, m, dayOfMonth ->
                val chosen = String.format(Locale.US, "%04d-%02d-%02d", year, m + 1, dayOfMonth)
                startDate = chosen
                if (startDate > endDate) {
                    endDate = startDate
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun pickEndDate() {
        val cal = Calendar.getInstance()
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(endDate)
            if (d != null) cal.time = d
        } catch (_: Exception) {}
        DatePickerDialog(
            context,
            { _, year, m, dayOfMonth ->
                val chosen = String.format(Locale.US, "%04d-%02d-%02d", year, m + 1, dayOfMonth)
                endDate = chosen
                if (endDate < startDate) {
                    startDate = endDate
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    LaunchedEffect(month, currency, refresh, isCustomRange, startDate, endDate) {
        val sDate = if (isCustomRange) startDate else null
        val eDate = if (isCustomRange) endDate else null
        result = CashflowRepository.getCached(context, month, currency, sDate, eDate)
        prevData = if (!isCustomRange) CashflowRepository.getCached(context, shiftMonth(month, -1), currency)?.data else null
        loading = true
        failed = false
        selection = null
        selectedBill = null
        try {
            result = CashflowRepository.load(context, month, currency, forceRefresh = true, startDate = sDate, endDate = eDate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("Cashflow", "Analysis unavailable", error)
            failed = true
        } finally {
            loading = false
        }
        if (!isCustomRange && result != null && prevData == null) {
            try {
                prevData = CashflowRepository.load(context, shiftMonth(month, -1), currency).data
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("Cashflow", "Previous month unavailable", error)
            }
        }
    }

    fun changeMonth(delta: Int) {
        val newMonth = shiftMonth(month, delta)
        result = CashflowRepository.getCached(context, newMonth, currency)
        prevData = CashflowRepository.getCached(context, shiftMonth(newMonth, -1), currency)?.data
        loading = true
        failed = false
        month = newMonth
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
                android.util.Log.w("Cashflow", "Update failed", error)
                android.widget.Toast.makeText(context, context.getString(R.string.cf_error), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = LightColorScheme) {
        Surface(Modifier.fillMaxSize(), color = Slate50) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cf_back), tint = Slate900) }
                    Text(stringResource(R.string.cfd_details_title), modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cf_refresh), tint = Slate600) }
                }

                // 模式切换（按月查看 / 自定义区间）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isCustomRange,
                        onClick = { isCustomRange = false },
                        label = { Text(stringResource(R.string.view_by_month)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White,
                            labelColor = Slate700,
                            selectedContainerColor = Teal50,
                            selectedLabelColor = PrimaryTeal
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = !isCustomRange,
                            borderColor = Slate200,
                            selectedBorderColor = PrimaryTeal,
                            borderWidth = 1.dp
                        )
                    )
                    FilterChip(
                        selected = isCustomRange,
                        onClick = { isCustomRange = true },
                        leadingIcon = {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(stringResource(R.string.view_by_custom_range)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White,
                            labelColor = Slate700,
                            selectedContainerColor = Teal50,
                            selectedLabelColor = PrimaryTeal
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isCustomRange,
                            borderColor = Slate200,
                            selectedBorderColor = PrimaryTeal,
                            borderWidth = 1.dp
                        )
                    )
                }

                if (!isCustomRange) {
                    Surface(
                        Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200)
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { changeMonth(-1) }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.prev_month), tint = Slate600) }
                            Text(month, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            IconButton(onClick = { changeMonth(1) }) { Icon(Icons.Default.ChevronRight, stringResource(R.string.next_month), tint = Slate600) }
                        }
                    }
                } else {
                    Surface(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.weight(1f).clickable { pickStartDate() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Slate50,
                                    border = BorderStroke(1.dp, Slate200)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(14.dp))
                                        Column {
                                            Text(stringResource(R.string.start_date), fontSize = 9.sp, color = Slate500)
                                            Text(startDate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                                        }
                                    }
                                }

                                Text(stringResource(R.string.to_date), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate400, modifier = Modifier.padding(horizontal = 6.dp))

                                Surface(
                                    modifier = Modifier.weight(1f).clickable { pickEndDate() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Slate50,
                                    border = BorderStroke(1.dp, Slate200)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(14.dp))
                                        Column {
                                            Text(stringResource(R.string.end_date), fontSize = 9.sp, color = Slate500)
                                            Text(endDate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                                        }
                                    }
                                }
                            }

                            // 快捷预设按钮行
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    stringResource(R.string.preset_this_month) to Pair("$initialMonth-01", todayStr),
                                    stringResource(R.string.preset_last_30_days) to Pair(shiftDays(todayStr, -30), todayStr),
                                    stringResource(R.string.preset_year_to_date) to Pair("${todayStr.substringBefore('-')}-01-01", todayStr),
                                    stringResource(R.string.preset_all_history) to Pair("2020-01-01", todayStr)
                                )
                                presets.forEach { (label, range) ->
                                    val isCur = startDate == range.first && endDate == range.second
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable {
                                            startDate = range.first
                                            endDate = range.second
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isCur) Teal50 else Slate100,
                                        border = if (isCur) BorderStroke(1.dp, PrimaryTeal) else null
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCur) PrimaryTeal else Slate700,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                result?.data?.optJSONArray("currencies")?.let { codes ->
                    if (codes.length() > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until codes.length()).map { codes.getString(it) }) { code ->
                                val isSelected = code == currency.ifEmpty { result?.data?.optString("currency") ?: "" }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val target = code
                                        val cached = CashflowRepository.getCached(context, month, target)
                                        result = cached
                                        prevData = CashflowRepository.getCached(context, shiftMonth(month, -1), target)?.data
                                        loading = true
                                        failed = false
                                        currency = target
                                    },
                                    label = { Text(code) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color.White,
                                        labelColor = Slate700,
                                        selectedContainerColor = Teal50,
                                        selectedLabelColor = PrimaryTeal
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = Slate200,
                                        selectedBorderColor = PrimaryTeal,
                                        borderWidth = 1.dp
                                    )
                                )
                            }
                        }
                    }
                }

                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = Teal700, trackColor = Slate200)
                }
                if (failed) {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFEF2F2), border = BorderStroke(1.dp, Color(0xFFFECDD3)), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.cf_error), color = ExpenseRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.cfu_retry), color = PrimaryTeal) }
                        }
                    }
                }
                result?.let { report ->
                    val data = report.data
                    val cur = data.optString("currency")
                    val isOnline = !report.cached
                    val prev = prevData?.takeIf { it.optString("currency") == cur && it.optString("month") == shiftMonth(month, -1) }
                    val daily = dailyExpense(data, month)
                    val todayDay = todayDayIfCurrent(month)
                    val incAmt = data.optDouble("income", 0.0)
                    val expAmt = data.optDouble("expense", 0.0)
                    val savAmt = data.optDouble("savings", 0.0)
                    val availAmt = data.optDouble("available", incAmt - expAmt - savAmt)
                    val isPositive = availAmt >= 0

                    // 突出呈现的总览卡片：总支出、储蓄总额、总收入与净结余
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Slate200),
                        shadowElevation = 0.5.dp
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isCustomRange) stringResource(R.string.cf_overview_custom) else stringResource(R.string.cf_overview_month),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700
                                )
                                Text(
                                    text = if (isCustomRange) "$startDate ~ $endDate" else month,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryTeal
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column {
                                    Text(
                                        text = if (savAmt > 0) stringResource(R.string.cf_net_savings_deducted) else stringResource(R.string.cf_net_balance),
                                        fontSize = 11.sp,
                                        color = Slate500
                                    )
                                    Text(
                                        text = (if (availAmt < 0) "-$cur " else "$cur ") + String.format(Locale.getDefault(), "%,.2f", Math.abs(availAmt)),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isPositive) PrimaryTeal else ExpenseRed
                                    )
                                }
                            }

                            HorizontalDivider(color = Slate100, thickness = 1.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                        scope.launch { pager.animateScrollToPage(0) }
                                    }.padding(vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.cf_total_expense), fontSize = 11.sp, color = Slate500)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "-$cur " + String.format(Locale.getDefault(), "%,.2f", expAmt),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ExpenseRed
                                    )
                                }
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                        scope.launch { pager.animateScrollToPage(2) }
                                    }.padding(vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.cf_total_savings), fontSize = 11.sp, color = Slate500)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "$cur " + String.format(Locale.getDefault(), "%,.2f", savAmt),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFD97706)
                                    )
                                }
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                        scope.launch { pager.animateScrollToPage(1) }
                                    }.padding(vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.cf_total_income), fontSize = 11.sp, color = Slate500)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "+$cur " + String.format(Locale.getDefault(), "%,.2f", incAmt),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryTeal
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Slate100)) {
                        val width = maxWidth / detailPages.size
                        val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (detailPages.size - 1).toFloat())
                        Box(Modifier.matchParentSize()) {
                            Box(Modifier.offset(x = width * position).width(width).fillMaxHeight().padding(3.dp)
                                .background(Color.White, RoundedCornerShape(9.dp)))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            val labels = listOf(R.string.cf_tab_expense, R.string.cf_tab_income, R.string.cf_tab_savings, R.string.cf_tab_bills)
                            detailPages.forEachIndexed { index, _ ->
                                val selected = pager.currentPage == index
                                val emphasis = 1f - kotlin.math.abs(position - index).coerceIn(0f, 1f)
                                Box(Modifier.weight(1f).selectable(selected = selected,
                                    role = androidx.compose.ui.semantics.Role.Tab,
                                    onClick = { scope.launch { pager.animateScrollToPage(index, animationSpec = tween(300, easing = FastOutSlowInEasing)) } })
                                    .padding(horizontal = 2.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(labels[index]), fontSize = 13.sp,
                                        color = androidx.compose.ui.graphics.lerp(Slate600, PrimaryTeal, emphasis),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                        key = { detailPages[it].name }, beyondBoundsPageCount = 1) { page ->
                        // Each page owns a saveable vertical position; report loading is shared above the pager.
                        val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()
                            .then(if (page == pager.currentPage) Modifier else Modifier.clearAndSetSemantics {}),
                            contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (detailPages[page]) {
                                // ================= 支出 =================
                                CashflowDashboardTab.EXPENSE -> {
                                    item {
                                        val transfer = transferAmount(data)
                                        val isPlatformMode = expenseViewMode == 1
                                        BreakdownCard(
                                            title = stringResource(R.string.cfd_expense_title),
                                            totalLabel = if (includeTransfer) stringResource(R.string.cf_donut_center_expense) else stringResource(R.string.cfu_consumption),
                                            currency = cur,
                                            slices = if (isPlatformMode) expensePlatformSlices(data) else expenseSlices(data, includeTransfer),
                                            unitLabel = if (isPlatformMode) stringResource(R.string.cfu_platforms) else stringResource(R.string.cfu_categories),
                                            showIncomeShare = true,
                                            prevMap = if (isPlatformMode) emptyMap() else prevCategoryAmounts(prev),
                                            headerExtra = {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        FilterChip(
                                                            selected = expenseViewMode == 0,
                                                            onClick = { expenseViewMode = 0 },
                                                            label = { Text(stringResource(R.string.cf_by_category)) },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                containerColor = Color.White,
                                                                labelColor = Slate700,
                                                                selectedContainerColor = Teal50,
                                                                selectedLabelColor = PrimaryTeal
                                                            ),
                                                            border = FilterChipDefaults.filterChipBorder(
                                                                enabled = true,
                                                                selected = expenseViewMode == 0,
                                                                borderColor = Slate200,
                                                                selectedBorderColor = PrimaryTeal,
                                                                borderWidth = 1.dp
                                                            )
                                                        )
                                                        FilterChip(
                                                            selected = expenseViewMode == 1,
                                                            onClick = { expenseViewMode = 1 },
                                                            label = { Text(stringResource(R.string.cf_by_platform)) },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                containerColor = Color.White,
                                                                labelColor = Slate700,
                                                                selectedContainerColor = Teal50,
                                                                selectedLabelColor = PrimaryTeal
                                                            ),
                                                            border = FilterChipDefaults.filterChipBorder(
                                                                enabled = true,
                                                                selected = expenseViewMode == 1,
                                                                borderColor = Slate200,
                                                                selectedBorderColor = PrimaryTeal,
                                                                borderWidth = 1.dp
                                                            )
                                                        )
                                                    }
                                                    if (!isPlatformMode && transfer > 0) {
                                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text(
                                                                stringResource(R.string.cfu_transfers) + " " + formatMoney(cur, transfer),
                                                                fontSize = 12.sp, color = Slate500
                                                            )
                                                            FilterChip(
                                                                selected = includeTransfer,
                                                                onClick = { includeTransfer = !includeTransfer },
                                                                label = { Text(stringResource(R.string.cfu_include_transfers)) },
                                                                colors = FilterChipDefaults.filterChipColors(
                                                                    containerColor = Color.White,
                                                                    labelColor = Slate700,
                                                                    selectedContainerColor = Teal50,
                                                                    selectedLabelColor = PrimaryTeal
                                                                ),
                                                                border = FilterChipDefaults.filterChipBorder(
                                                                    enabled = true,
                                                                    selected = includeTransfer,
                                                                    borderColor = Slate200,
                                                                    selectedBorderColor = PrimaryTeal,
                                                                    borderWidth = 1.dp
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onItemClick = { selection = it }
                                        )
                                    }
                                    if (expenseViewMode == 1) {
                                        item {
                                            PlatformCashflowCard(data, cur, onItemClick = { selection = it })
                                        }
                                    }
                                    item {
                                        TextButton(onClick = { showCharts = !showCharts }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(if (showCharts) R.string.cfu_hide_charts else R.string.cfu_show_charts))
                                        }
                                    }
                                    if (showCharts) item { DailySpendingCard(daily, cur, todayDay) }
                                }

                                // ================= 收入 =================
                                CashflowDashboardTab.OVERVIEW, CashflowDashboardTab.INCOME -> {
                                    item {
                                        BreakdownCard(
                                            title = stringResource(R.string.cfd_income_title),
                                            totalLabel = stringResource(R.string.cf_donut_center_income),
                                            currency = cur,
                                            slices = incomeSlices(data),
                                            unitLabel = stringResource(R.string.cfu_platforms),
                                            headerExtra = {
                                                if (data.rows("income_platforms").any { it.optString("key").isBlank() }) {
                                                    Text(stringResource(R.string.cfd_assign_accounts), fontSize = 12.sp, color = Slate600)
                                                }
                                            },
                                            onItemClick = { selection = it }
                                        )
                                    }
                                    item {
                                        PlatformCashflowCard(data, cur, onItemClick = { selection = it })
                                    }
                                }

                                // ================= 储蓄 =================
                                CashflowDashboardTab.SAVINGS -> {
                                    val totalSavingsAmt = data.optDouble("savings", 0.0)
                                    val incomeAmt = data.optDouble("income", 0.0)
                                    val savingsRate = if (incomeAmt > 0) totalSavingsAmt / incomeAmt * 100.0 else 0.0
                                    val hasSavings = totalSavingsAmt > 0 || data.rows("savings_categories").isNotEmpty() || data.rows("savings_platforms").isNotEmpty()

                                    if (!hasSavings) {
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                                shape = RoundedCornerShape(16.dp),
                                                color = Color.White,
                                                border = BorderStroke(1.dp, Slate200)
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Text("💰", fontSize = 36.sp)
                                                    Spacer(Modifier.height(10.dp))
                                                    Text(stringResource(R.string.cf_no_savings_in_period), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate700)
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(stringResource(R.string.cf_savings_empty_hint), fontSize = 12.sp, color = Slate400, textAlign = TextAlign.Center)
                                                }
                                            }
                                        }
                                    } else {
                                        item {
                                            val isPlatformMode = savingsViewMode == 1
                                            val slices = if (isPlatformMode) savingsPlatformSlices(data) else savingsSlices(data)
                                            BreakdownCard(
                                                title = stringResource(R.string.cf_savings_structure_title),
                                                totalLabel = stringResource(R.string.cf_total_savings),
                                                currency = cur,
                                                slices = slices,
                                                unitLabel = if (isPlatformMode) stringResource(R.string.cf_deposit_account) else stringResource(R.string.cf_savings_category),
                                                showIncomeShare = true,
                                                headerExtra = {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        FilterChip(
                                                            selected = savingsViewMode == 0,
                                                            onClick = { savingsViewMode = 0 },
                                                            label = { Text(stringResource(R.string.cf_by_category)) },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                containerColor = Color.White,
                                                                labelColor = Slate700,
                                                                selectedContainerColor = Teal50,
                                                                selectedLabelColor = PrimaryTeal
                                                            ),
                                                            border = FilterChipDefaults.filterChipBorder(
                                                                enabled = true,
                                                                selected = savingsViewMode == 0,
                                                                borderColor = Slate200,
                                                                selectedBorderColor = PrimaryTeal,
                                                                borderWidth = 1.dp
                                                            )
                                                        )
                                                        FilterChip(
                                                            selected = savingsViewMode == 1,
                                                            onClick = { savingsViewMode = 1 },
                                                            label = { Text(stringResource(R.string.cf_by_account)) },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                containerColor = Color.White,
                                                                labelColor = Slate700,
                                                                selectedContainerColor = Teal50,
                                                                selectedLabelColor = PrimaryTeal
                                                            ),
                                                            border = FilterChipDefaults.filterChipBorder(
                                                                enabled = true,
                                                                selected = savingsViewMode == 1,
                                                                borderColor = Slate200,
                                                                selectedBorderColor = PrimaryTeal,
                                                                borderWidth = 1.dp
                                                            )
                                                        )
                                                    }
                                                },
                                                onItemClick = { selection = it }
                                            )
                                        }
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp),
                                                color = Color.White,
                                                border = BorderStroke(1.dp, Slate200)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(stringResource(R.string.cf_savings_rate_label), fontSize = 12.sp, color = Slate500)
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(
                                                            String.format(Locale.getDefault(), "%.1f%%", savingsRate),
                                                            fontSize = 20.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = PrimaryTeal
                                                        )
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = if (savingsRate >= 20.0) Teal50 else Color(0xFFFEF3C7)
                                                    ) {
                                                        Text(
                                                            text = when {
                                                                savingsRate >= 40.0 -> stringResource(R.string.cf_savings_grade_excellent)
                                                                savingsRate >= 20.0 -> stringResource(R.string.cf_savings_grade_good)
                                                                savingsRate > 0.0 -> stringResource(R.string.cf_savings_grade_starting)
                                                                else -> stringResource(R.string.cf_savings_grade_pending)
                                                            },
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (savingsRate >= 20.0) PrimaryTeal else Color(0xFFD97706),
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // ================= 账单 =================
                                CashflowDashboardTab.BILLS -> {
                                    item {
                                        if (data.optDouble("income") > 0) Text(stringResource(R.string.cfd_fixed_share,
                                            formatPct(data.optDouble("planned") / data.optDouble("income") * 100)), color = Slate600, fontSize = 12.sp)
                                    }
                                    if (data.optBoolean("schedule_estimated")) item {
                                        Text(stringResource(R.string.cfd_schedule_hint), color = WarningAmber, fontSize = 12.sp)
                                    }
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
                                                Text(stringResource(R.string.cf_empty), color = Slate500)
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
                                                            Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            Text(tx.optString("date") + " · " + formatMoney(cur, tx.optDouble("amount", 0.0)), fontSize = 11.sp, color = Slate500)
                                                        }
                                                        FilledTonalButton(
                                                            enabled = isOnline && !busy,
                                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Teal50, contentColor = PrimaryTeal),
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
                                            SectionCard(title = stringResource(R.string.cfu_confirmed)) {
                                                confirmed.forEach { tx ->
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text(tx.optString("note").ifEmpty { "#" + tx.optInt("id") }, fontSize = 14.sp, color = Slate900, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        TextButton(
                                                            enabled = isOnline && !busy,
                                                            onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id")).put("undo", true)) }
                                                        ) { Text(stringResource(R.string.cf_undo_confirm), fontSize = 12.sp, color = PrimaryTeal) }
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
                                                        Text(bill.optString("title") + " · " + bill.optString("due_date"), fontSize = 13.sp, color = Slate500, modifier = Modifier.weight(1f))
                                                        TextButton(enabled = isOnline && !busy, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key"))) }) {
                                                            Text(stringResource(R.string.cf_restore), color = PrimaryTeal)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.cf_updated) + ": " + reportTime(data.optString("updated_at"), LocalContext.current.resources.configuration.locales[0]),
                                        fontSize = 11.sp, color = Slate400
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(18.dp)) }
                        }
                    }
                }
            }
        }

    // ---------------- 交易明细 Bottom Sheet ----------------
    selection?.let { sel ->
        val cur = result?.data?.optString("currency") ?: ""
        val txs = result?.data?.rows("transactions")?.filter { it.optInt("id") in sel.ids } ?: emptyList()
        val inTxs = txs.filter { it.optString("type") == "income" }
        val outTxs = txs.filter { it.optString("type") != "income" }
        val inSum = inTxs.sumOf { it.optDouble("amount", 0.0) }
        val outSum = outTxs.sumOf { it.optDouble("amount", 0.0) }
        val net = inSum - outSum
        ModalBottomSheet(onDismissRequest = { selection = null }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(sel.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Text(stringResource(R.string.cfu_tx_count, txs.size), fontSize = 12.sp, color = Slate500)
                    }
                    if (inSum > 0 && outSum > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (net >= 0) "+" else "") + formatMoney(cur, net),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (net >= 0) IncomeGreen else ExpenseRed
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("+" + formatMoney(cur, inSum), fontSize = 11.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                                Text("-" + formatMoney(cur, outSum), fontSize = 11.sp, color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (inSum > 0) {
                        Text("+" + formatMoney(cur, inSum), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = IncomeGreen)
                    } else {
                        Text("-" + formatMoney(cur, outSum), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = ExpenseRed)
                    }
                }
                if (txs.isEmpty()) {
                    Text(stringResource(R.string.cf_empty), fontSize = 13.sp, color = Slate500)
                } else {
                    val maxTx = txs.maxOf { it.optDouble("amount", 0.0) }
                    LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(txs.sortedByDescending { it.optDouble("amount", 0.0) }) { tx ->
                            val isIncome = tx.optString("type") == "income"
                            Surface(shape = RoundedCornerShape(12.dp), color = Slate50, border = BorderStroke(1.dp, Slate200)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(tx.optString("note").ifEmpty { tx.optString("category") }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                                            Text(tx.optString("date"), fontSize = 11.sp, color = Slate500)
                                        }
                                        Text(
                                            (if (isIncome) "+" else "-") + formatMoney(cur, tx.optDouble("amount", 0.0)),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isIncome) IncomeGreen else ExpenseRed
                                        )
                                    }
                                    BarTrack(if (maxTx > 0) (tx.optDouble("amount", 0.0) / maxTx).toFloat() else 0f, if (isIncome) IncomeGreen else ExpenseRed, height = 4.dp, track = Slate200)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 账单详情 Bottom Sheet ----------------
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.optString("title"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            Text(stringResource(R.string.cfu_due) + bill.optString("due_date"), fontSize = 12.sp, color = Slate500)
                        }
                        Text(formatMoney(cur, amount), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Slate900)
                    }
                    BillSegmentBar(paid, pending, amount)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.cf_paid) + " " + formatMoney(cur, paid), fontSize = 12.sp, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.cf_pending) + " " + formatMoney(cur, pending), fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (failed) Text(stringResource(R.string.cf_error), color = ExpenseRed, fontSize = 13.sp)

                val linked = result?.data?.rows("payment_links")?.filter { it.optString("bill_key") == billKey } ?: emptyList()
                if (linked.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cfu_linked)) {
                        linked.forEach { link ->
                            SheetActionRow(text = "#" + link.optInt("transaction_id"), action = stringResource(R.string.cf_unlink), enabled = isOnline && !busy) {
                                update(JSONObject().put("bill_key", billKey).put("transaction_id", link.optInt("transaction_id")).put("unlink", true))
                            }
                        }
                    }
                }

                val candidateExpenses = ((result?.data?.rows("transactions") ?: emptyList()) +
                    (result?.data?.rows("scheduled_transactions") ?: emptyList()))
                    .filter { it.optString("type") == "expense" }
                if (candidateExpenses.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_link)) {
                        candidateExpenses.take(20).forEach { tx ->
                            SheetActionRow(
                                text = tx.optString("date") + " · " + tx.optString("note").ifEmpty { "#" + tx.optInt("id") },
                                sub = formatMoney(cur, tx.optDouble("amount", 0.0)),
                                action = stringResource(R.string.cf_link), enabled = isOnline && !busy
                            ) { update(JSONObject().put("bill_key", billKey).put("transaction_id", tx.optInt("id"))) }
                        }
                    }
                }

                val candidateRefunds = result?.data?.rows("transactions")?.filter {
                    it.optString("type") == "income" && it.optDouble("amount") > 0
                } ?: emptyList()
                if (candidateRefunds.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_refund)) {
                        candidateRefunds.take(20).forEach { tx ->
                            SheetActionRow(
                                text = tx.optString("date") + " · " + tx.optString("note").ifEmpty { "#" + tx.optInt("id") },
                                sub = formatMoney(cur, tx.optDouble("amount", 0.0)),
                                action = stringResource(R.string.cf_link), enabled = isOnline && !busy
                            ) { update(JSONObject().put("bill_key", billKey).put("transaction_id", tx.optInt("id")).put("refund", true)) }
                        }
                    }
                }

                SheetSection(stringResource(R.string.cf_edit_amount)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = correctedAmount,
                            onValueChange = { correctedAmount = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryTeal,
                                unfocusedBorderColor = Slate200,
                                focusedTextColor = Slate900,
                                unfocusedTextColor = Slate900
                            )
                        )
                        Button(
                            enabled = isOnline && !busy && correctedAmount.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                            onClick = { update(JSONObject().put("action", "bill").put("bill_key", billKey).put("amount", correctedAmount)) }
                        ) { Text(stringResource(R.string.cfu_save)) }
                    }
                }

                val others = result?.data?.rows("bills")?.filter { it.optString("bill_key") != billKey } ?: emptyList()
                if (others.isNotEmpty()) {
                    SheetSection(stringResource(R.string.cf_duplicate)) {
                        others.forEach { other ->
                            SheetActionRow(text = other.optString("title") + " · " + other.optString("due_date"), action = stringResource(R.string.cfu_mark), enabled = isOnline && !busy) {
                                update(JSONObject().put("action", "bill").put("bill_key", billKey).put("duplicate_of", other.optString("bill_key")))
                            }
                        }
                    }
                }

                OutlinedButton(
                    enabled = isOnline && !busy,
                    onClick = { update(JSONObject().put("action", "bill").put("bill_key", billKey).put("cancelled", true)) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ExpenseRed),
                    border = BorderStroke(1.dp, Color(0xFFFECDD3)),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.cf_cancel)) }
            }
        }
    }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate500)
        content()
    }
}

@Composable
private fun SheetActionRow(text: String, action: String, enabled: Boolean, sub: String? = null, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Slate50, border = BorderStroke(1.dp, Slate200)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, fontSize = 11.sp, color = Slate500)
            }
            TextButton(enabled = enabled, onClick = onClick) { Text(action, fontSize = 12.sp, color = PrimaryTeal) }
        }
    }
}