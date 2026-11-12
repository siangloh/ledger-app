package com.siangloh.ledger.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
     * 绘制现金流累积走势平滑曲线图 (Smooth Cashflow Trend Area Chart)
     */
    fun drawCashflowTrend(
        widthPx: Int,
        heightPx: Int,
        dataPoints: List<Double>
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
        val lineColor = if (isPositive) Color.parseColor("#0F766E") else Color.parseColor("#E11D48")
        val topFill = if (isPositive) Color.parseColor("#440F766E") else Color.parseColor("#44E11D48")
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

        // 最新数据点外圈光晕与实心圆点
        val lastPoint = points.last()
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isPositive) Color.parseColor("#660F766E") else Color.parseColor("#66E11D48")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(lastPoint.first, lastPoint.second, 7f, glowPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(lastPoint.first, lastPoint.second, 4.5f, dotPaint)

        val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(lastPoint.first, lastPoint.second, 2f, innerDotPaint)

        return bitmap
    }

    /**
     * 绘制近 7 天支出柱状图 (7-Day Daily Spending Bar Chart)
     */
    fun draw7DaySpendingBars(
        widthPx: Int,
        heightPx: Int,
        dailyExpenses: List<Pair<String, Double>> // Pair(dateLabel e.g. "12", amount)
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

        var maxVal = dailyExpenses.maxOfOrNull { it.second } ?: 0.0
        if (maxVal <= 0.0) maxVal = 100.0

        val count = dailyExpenses.size
        val totalSlotWidth = effectiveWidth / count
        val barWidth = (totalSlotWidth * 0.45f).coerceIn(8f, 22f)

        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.FILL
        }
        val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F766E")
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        val textTodayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F766E")
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        for (i in dailyExpenses.indices) {
            val item = dailyExpenses[i]
            val isToday = (i == count - 1)

            val slotCenter = paddingH + i * totalSlotWidth + totalSlotWidth / 2f
            val ratio = (item.second / maxVal).toFloat().coerceIn(0f, 1f)
            val barHeight = max(effectiveHeight * ratio, 5f)

            val left = slotCenter - barWidth / 2f
            val right = slotCenter + barWidth / 2f
            val top = paddingTop + (effectiveHeight - barHeight)
            val bottom = paddingTop + effectiveHeight

            val rect = RectF(left, top, right, bottom)
            val paint = if (isToday) todayPaint else normalPaint
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)

            // 日期文本 (如 08, 09, 10...)
            val tPaint = if (isToday) textTodayPaint else textPaint
            canvas.drawText(item.first, slotCenter, height - 2f, tPaint)
        }

        return bitmap
    }
}

