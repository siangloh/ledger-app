package com.siangloh.ledger.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * 桌面微部件图表渲染引擎 (Home Screen Widget Chart Renderer)
 * 在内存中直接通过 Canvas 极速绘制高清平滑曲线图与柱状图，0 开销嵌入 RemoteViews
 */
object WidgetChartRenderer {

    const val PATTERN_TREND = 0      // 现金流累积走势面积图
    const val PATTERN_BARS = 1       // 近 7 天支出柱状图
    const val PATTERN_COMPACT = 2    // 纯数字简洁模式 (无图表)

    /**
     * 将近 7 天热区索引 (0..6) 映射到当月累计走势图的对应数据点索引，
     * 兼容月初不足 7 天时的日期对齐。
     */
    fun computeTrendSelectedIndex(trendPointsCount: Int, selectedDayIndex: Int): Int {
        if (trendPointsCount <= 0) return 0
        return (trendPointsCount - 7 + selectedDayIndex).coerceIn(0, trendPointsCount - 1)
    }

    /**
     * 绘制现金流累积走势平滑曲线图 (Smooth Cashflow Trend Area Chart)
     * 支持选中特定索引以高亮显示该点，并支持浅色/深色主题自适应
     */
    fun drawCashflowTrend(
        widthPx: Int,
        heightPx: Int,
        dataPoints: List<Double>,
        selectedIndex: Int = -1,
        isDarkTheme: Boolean = false
    ): Bitmap {
        val width = max(widthPx, 240)
        val height = max(heightPx, 60)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (dataPoints.isEmpty()) {
            return bitmap
        }

        val paddingH = 14f
        val paddingTop = 10f
        val paddingBottom = 10f

        val effectiveWidth = width - paddingH * 2
        val effectiveHeight = height - paddingTop - paddingBottom

        var minVal = dataPoints.minOrNull() ?: 0.0
        var maxVal = dataPoints.maxOrNull() ?: 0.0
        if (minVal == maxVal) {
            maxVal += 10.0
            minVal -= 10.0
        }
        val range = maxVal - minVal

        val points = mutableListOf<Pair<Float, Float>>()
        val stepX = if (dataPoints.size > 1) effectiveWidth / (dataPoints.size - 1) else effectiveWidth

        for (i in dataPoints.indices) {
            val x = paddingH + i * stepX
            val ratio = ((dataPoints[i] - minVal) / range).toFloat().coerceIn(0f, 1f)
            val y = paddingTop + effectiveHeight * (1f - ratio)
            points.add(Pair(x, y))
        }

        // 曲线路径
        val path = Path()
        val fillPath = Path()

        path.moveTo(points[0].first, points[0].second)
        fillPath.moveTo(points[0].first, points[0].second)

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val cX = (prev.first + curr.first) / 2f
            path.cubicTo(cX, prev.second, cX, curr.second, curr.first, curr.second)
            fillPath.cubicTo(cX, prev.second, cX, curr.second, curr.first, curr.second)
        }

        fillPath.lineTo(points.last().first, height.toFloat())
        fillPath.lineTo(points.first().first, height.toFloat())
        fillPath.close()

        val isPositive = (dataPoints.lastOrNull() ?: 0.0) >= 0.0
        val lineColor = when {
            isPositive && isDarkTheme -> Color.parseColor("#5EEAD4")
            isPositive -> Color.parseColor("#0F766E")
            isDarkTheme -> Color.parseColor("#FB7185")
            else -> Color.parseColor("#E11D48")
        }
        val topFill = when {
            isPositive && isDarkTheme -> Color.parseColor("#445EEAD4")
            isPositive -> Color.parseColor("#440F766E")
            isDarkTheme -> Color.parseColor("#44FB7185")
            else -> Color.parseColor("#44E11D48")
        }
        val botFill = Color.TRANSPARENT

        // 填充渐变面积
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, paddingTop, 0f, height.toFloat(), topFill, botFill, Shader.TileMode.CLAMP)
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)

        // 绘制主折线
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, linePaint)

        // 目标高亮点（若指定 selectedIndex 有效则标出选中点，否则默认高亮最后点）
        val targetIdx = if (selectedIndex in points.indices) selectedIndex else points.indices.last
        val targetPoint = points[targetIdx]

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                isPositive && isDarkTheme -> Color.parseColor("#665EEAD4")
                isPositive -> Color.parseColor("#660F766E")
                isDarkTheme -> Color.parseColor("#66FB7185")
                else -> Color.parseColor("#66E11D48")
            }
            style = Paint.Style.FILL
        }
        canvas.drawCircle(targetPoint.first, targetPoint.second, 7.5f, glowPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(targetPoint.first, targetPoint.second, 4.5f, dotPaint)

        val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkTheme) Color.parseColor("#111827") else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(targetPoint.first, targetPoint.second, 2f, innerDotPaint)

        return bitmap
    }

    /**
     * 绘制近 7 天支出柱状图 (7-Day Daily Spending Bar Chart)
     * 支持选中某一天 (selectedIndex, 默认 6 即今天) 进行重点突出高亮，并自适应浅色/深色主题
     */
    fun draw7DaySpendingBars(
        widthPx: Int,
        heightPx: Int,
        dailyExpenses: List<Pair<String, Double>>, // Pair(dateLabel e.g. "12", amount)
        selectedIndex: Int = 6,
        isDarkTheme: Boolean = false
    ): Bitmap {
        val width = max(widthPx, 240)
        val height = max(heightPx, 60)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (dailyExpenses.isEmpty()) {
            return bitmap
        }

        val paddingH = 14f
        val paddingTop = 8f
        val paddingBottom = 16f

        val effectiveWidth = width - paddingH * 2
        val effectiveHeight = height - paddingTop - paddingBottom

        val rawMax = dailyExpenses.maxOfOrNull { it.second } ?: 0.0
        val hasAnySpend = rawMax > 0.0
        val maxVal = if (hasAnySpend) rawMax else 100.0

        val count = dailyExpenses.size
        val totalSlotWidth = effectiveWidth / count
        val barWidth = (totalSlotWidth * 0.45f).coerceIn(8f, 22f)

        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isDarkTheme) "#334155" else "#CBD5E1")
            style = Paint.Style.FILL
        }
        val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isDarkTheme) "#5EEAD4" else "#0F766E")
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        val textSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (isDarkTheme) "#5EEAD4" else "#0F766E")
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        // 若近 7 天均无消费，绘制一条极淡的虚线基准线提示空态
        if (!hasAnySpend) {
            val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(if (isDarkTheme) "#334155" else "#E2E8F0")
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
            }
            val baseY = paddingTop + effectiveHeight - 2.5f
            canvas.drawLine(paddingH, baseY, width - paddingH, baseY, baseLinePaint)
        }

        for (i in dailyExpenses.indices) {
            val item = dailyExpenses[i]
            val isSelected = (i == selectedIndex)

            val slotCenter = paddingH + i * totalSlotWidth + totalSlotWidth / 2f
            val ratio = (item.second / maxVal).toFloat().coerceIn(0f, 1f)
            val barHeight = max(effectiveHeight * ratio, 5f)

            val left = slotCenter - barWidth / 2f
            val right = slotCenter + barWidth / 2f
            val top = paddingTop + (effectiveHeight - barHeight)
            val bottom = paddingTop + effectiveHeight

            val rect = RectF(left, top, right, bottom)
            val paint = if (isSelected) selectedPaint else normalPaint
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)

            // 日期文本 (如 08, 09, 10...)
            val tPaint = if (isSelected) textSelectedPaint else textPaint
            canvas.drawText(item.first, slotCenter, height - 2f, tPaint)
        }

        return bitmap
    }
}

