package com.siangloh.ledger.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.CashflowRepository
import com.siangloh.ledger.data.LocalLedgerRepository
import com.siangloh.ledger.util.AppLanguageManager
import com.siangloh.ledger.widget.LedgerAppWidgetProvider
import com.siangloh.ledger.widget.WidgetChartRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 桌面微组件样式定制与实时交互预览页面
 * (Widget Customization & Live 1:1 Preview)
 * 严格遵循用户自定义多语言 (AppLanguageManager)，完全消除硬编码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCustomizationPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentLang = remember { AppLanguageManager.getLanguage(context) }
    val prefs = remember { context.getSharedPreferences("ledger_widget_prefs", Context.MODE_PRIVATE) }

    // 当前选中的图表模式
    var selectedPattern by remember {
        mutableIntStateOf(prefs.getInt("chart_pattern", WidgetChartRenderer.PATTERN_TREND))
    }

    // 真实业务数据状态 (供预览卡片使用)
    var monthNetBalance by remember { mutableStateOf(0.0) }
    var todayExpense by remember { mutableStateOf(0.0) }
    var safeCashflow by remember { mutableStateOf(0.0) }
    var pendingCount by remember { mutableIntStateOf(0) }
    var pendingAmount by remember { mutableStateOf(0.0) }
    var monthDisplayStr by remember { mutableStateOf("") }
    val currency = "RM"

    // 渲染生成的图表 Bitmaps
    var trendBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var barsBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 加载本地真实记账数据并预绘制图表
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val cal = Calendar.getInstance()
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                monthDisplayStr = AppLanguageManager.formatWidgetMonth(cal, currentLang)

                val monthQuery = String.format(Locale.US, "%04d-%02d", year, month)
                val todayQuery = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

                val db = AppDatabase.getInstance(context)
                val monthTransactions = db.localTransactionDao().getByMonth("$monthQuery%")

                var inc = 0.0
                var exp = 0.0
                var todayExp = 0.0

                for (tx in monthTransactions) {
                    if (tx.type == "income") inc += tx.amount
                    else if (tx.type == "expense") {
                        exp += tx.amount
                        if (tx.date == todayQuery) todayExp += tx.amount
                    }
                }
                monthNetBalance = inc - exp
                todayExpense = todayExp

                // 计算待缴账单
                val toolsRes = LocalLedgerRepository.getToolsSummary(context).getOrNull()
                val subs = toolsRes?.subscriptions ?: emptyList()
                val linksArr = CashflowRepository.getPaymentLinks(context)
                val linksList = mutableListOf<org.json.JSONObject>()
                for (i in 0 until linksArr.length()) {
                    linksList.add(linksArr.getJSONObject(i))
                }

                var pCount = 0
                var pAmount = 0.0
                for (sub in subs) {
                    if (sub.cost <= 0) continue
                    val billKey = "sub_${sub.id}"
                    val hasLink = linksList.any { it.optString("bill_key") == billKey }
                    val isPaid = if (hasLink) true else {
                        monthTransactions.any { tx ->
                            tx.type == "expense" &&
                                (sub.accountId == null || tx.accountId == sub.accountId || (sub.accountName != null && sub.accountName.equals(tx.channel, ignoreCase = true))) &&
                                (tx.category.equals(sub.category, ignoreCase = true) || tx.note.contains(sub.name, ignoreCase = true))
                        }
                    }
                    if (!isPaid) {
                        pCount++
                        pAmount += sub.cost
                    }
                }
                pendingCount = pCount
                pendingAmount = pAmount
                safeCashflow = monthNetBalance - pAmount

                // 预渲染走势折线图
                val trendPoints = mutableListOf<Double>()
                var cumulative = 0.0
                val txByDate = monthTransactions.groupBy { it.date }
                for (d in 1..day) {
                    val dStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, d)
                    val dayTxList = txByDate[dStr] ?: emptyList()
                    for (tx in dayTxList) {
                        if (tx.type == "income") cumulative += tx.amount
                        else if (tx.type == "expense") cumulative -= tx.amount
                    }
                    trendPoints.add(cumulative)
                }
                if (trendPoints.size <= 1) {
                    trendPoints.add(0, 0.0)
                }
                trendBitmap = WidgetChartRenderer.drawCashflowTrend(480, 110, trendPoints)

                // 预渲染 7 日柱状图
                val dailyExpenses = mutableListOf<Pair<String, Double>>()
                val barCal = Calendar.getInstance()
                barCal.add(Calendar.DAY_OF_MONTH, -6)
                val sdfDay = SimpleDateFormat("dd", Locale.US)
                val sdfFull = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                for (i in 0..6) {
                    val fullDate = sdfFull.format(barCal.time)
                    val label = sdfDay.format(barCal.time)
                    val dayTxs = db.localTransactionDao().getByDateRange(fullDate, fullDate)
                    val dayExpAmt = dayTxs.filter { it.type == "expense" }.sumOf { it.amount }
                    dailyExpenses.add(Pair(label, dayExpAmt))
                    barCal.add(Calendar.DAY_OF_MONTH, 1)
                }
                barsBitmap = WidgetChartRenderer.draw7DaySpendingBars(480, 110, dailyExpenses)
            } catch (_: Exception) {}
        }
    }

    // 保存并应用到桌面小部件
    fun applyPattern(pattern: Int) {
        selectedPattern = pattern
        prefs.edit().putInt("chart_pattern", pattern).apply()
        LedgerAppWidgetProvider.notifyDataChanged(context)
        Toast.makeText(context, context.getString(R.string.widget_applied_toast), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tools_widget_customization),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF0F172A)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8FAFC))
            )
        },
        containerColor = Color(0xFFF8FAFC)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. 实时效果预览区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.widget_preview_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF334155)
                )
                Text(
                    text = stringResource(R.string.widget_preview_tip),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(Modifier.height(10.dp))

            // 桌面小部件 1:1 动态仿真卡片 (纯净预览，完美对应桌面小部件)
            WidgetPreviewCard(
                pattern = selectedPattern,
                monthNetBalance = monthNetBalance,
                safeCashflow = safeCashflow,
                todayExpense = todayExpense,
                pendingCount = pendingCount,
                pendingAmount = pendingAmount,
                monthDisplayStr = monthDisplayStr,
                currency = currency,
                trendBitmap = trendBitmap,
                barsBitmap = barsBitmap
            )

            Spacer(Modifier.height(24.dp))

            // 2. 样式选择器区域
            Text(
                text = stringResource(R.string.widget_style_select_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF334155)
            )

            Spacer(Modifier.height(12.dp))

            // 选项 1：现金流累积走势图
            StyleSelectionItem(
                title = stringResource(R.string.widget_pattern_trend) + " · " + stringResource(R.string.widget_style_trend_title),
                description = stringResource(R.string.widget_style_trend_desc),
                isSelected = selectedPattern == WidgetChartRenderer.PATTERN_TREND,
                tagText = stringResource(R.string.widget_tag_recommended),
                tagColor = Color(0xFF0F766E),
                onClick = { applyPattern(WidgetChartRenderer.PATTERN_TREND) }
            )

            Spacer(Modifier.height(10.dp))

            // 选项 2：近 7 天支出柱状图
            StyleSelectionItem(
                title = stringResource(R.string.widget_pattern_bars) + " · " + stringResource(R.string.widget_style_bars_title),
                description = stringResource(R.string.widget_style_bars_desc),
                isSelected = selectedPattern == WidgetChartRenderer.PATTERN_BARS,
                tagText = stringResource(R.string.widget_tag_contrast),
                tagColor = Color(0xFF0284C7),
                onClick = { applyPattern(WidgetChartRenderer.PATTERN_BARS) }
            )

            Spacer(Modifier.height(10.dp))

            // 选项 3：极简紧凑数字模式
            StyleSelectionItem(
                title = stringResource(R.string.widget_pattern_compact) + " · " + stringResource(R.string.widget_style_compact_title),
                description = stringResource(R.string.widget_style_compact_desc),
                isSelected = selectedPattern == WidgetChartRenderer.PATTERN_COMPACT,
                tagText = stringResource(R.string.widget_tag_minimal),
                tagColor = Color(0xFF64748B),
                onClick = { applyPattern(WidgetChartRenderer.PATTERN_COMPACT) }
            )

            Spacer(Modifier.height(24.dp))

            // 底部提示信息 (全面多语言自适应)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF1F5F9),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "💡 ", fontSize = 16.sp)
                    Text(
                        text = stringResource(R.string.widget_customization_tip),
                        fontSize = 12.sp,
                        color = Color(0xFF475569),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

/**
 * 桌面微组件 1:1 动态仿真卡片 (与 RemoteViews 布局完全 100% 对应)
 */
@Composable
private fun WidgetPreviewCard(
    pattern: Int,
    monthNetBalance: Double,
    safeCashflow: Double,
    todayExpense: Double,
    pendingCount: Int,
    pendingAmount: Double,
    monthDisplayStr: String,
    currency: String,
    trendBitmap: Bitmap?,
    barsBitmap: Bitmap?
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x1A0F172A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // 顶栏：标题、月份与操作按钮 (完全对应桌面小部件的纯净顶栏)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F766E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(currency.take(1), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.widget_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF334155)
                    )
                    Text(
                        text = monthDisplayStr,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F766E)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🔄", fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F766E),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.widget_quick_add),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 中部指标：净结余与安全现金流预估
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.widget_net_balance),
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                    val sign = if (monthNetBalance >= 0) "+" else "-"
                    val netStr = "$sign $currency ${String.format(Locale.getDefault(), "%,.2f", abs(monthNetBalance))}"
                    Text(
                        text = netStr,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (monthNetBalance >= 0) Color(0xFF0F766E) else Color(0xFFB91C1C)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.widget_safe_cashflow),
                            fontSize = 9.sp,
                            color = Color(0xFF64748B)
                        )
                        val safeSign = if (safeCashflow >= 0) "+" else "-"
                        val safeStr = "$safeSign $currency ${String.format(Locale.getDefault(), "%,.2f", abs(safeCashflow))}"
                        Text(
                            text = safeStr,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (safeCashflow >= 0) Color(0xFF0F766E) else Color(0xFFB91C1C)
                        )
                    }
                }
            }

            // 动态图表显示
            when (pattern) {
                WidgetChartRenderer.PATTERN_TREND -> {
                    Spacer(Modifier.height(4.dp))
                    if (trendBitmap != null) {
                        Image(
                            bitmap = trendBitmap.asImageBitmap(),
                            contentDescription = "Trend Chart",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                        )
                    }
                }
                WidgetChartRenderer.PATTERN_BARS -> {
                    Spacer(Modifier.height(4.dp))
                    if (barsBitmap != null) {
                        Image(
                            bitmap = barsBitmap.asImageBitmap(),
                            contentDescription = "Bars Chart",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                        )
                    }
                }
                else -> {
                    // 极简模式不占高
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部双指标胶囊：今日支出 & 待付账单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 今日支出
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            text = stringResource(R.string.widget_today_expense),
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "$currency ${String.format(Locale.getDefault(), "%,.2f", todayExpense)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                // 待付账单
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            text = stringResource(R.string.widget_pending_bills),
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                        if (pendingCount == 0) {
                            Text(
                                text = stringResource(R.string.widget_bills_all_clear),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F766E)
                            )
                        } else {
                            val amtStr = "$currency ${String.format(Locale.getDefault(), "%,.2f", pendingAmount)}"
                            Text(
                                text = stringResource(R.string.widget_bills_pending_fmt, pendingCount, amtStr),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 样式选择项卡片
 */
@Composable
private fun StyleSelectionItem(
    title: String,
    description: String,
    isSelected: Boolean,
    tagText: String,
    tagColor: Color,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF0F766E) else Color(0xFFE2E8F0),
        animationSpec = tween(200),
        label = "border"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFF0FDFA) else Color.White,
        animationSpec = tween(200),
        label = "bg"
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) Color(0xFF0F766E) else Color(0xFF94A3B8),
                modifier = Modifier.size(22.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tagColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = tagText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tagColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 17.sp
                )
            }
        }
    }
}
