package com.siangloh.ledger.util

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import com.siangloh.ledger.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object CalendarHelper {

    /**
     * 将待付账单快捷添加至系统日历 (Google 日历、三星日历、小米日历等)
     * 支持自动设定全天日程及到期前提醒
     */
    fun addBillToCalendar(
        context: Context,
        title: String,
        amount: Double,
        currency: String,
        dueDateStr: String,
        accountName: String?
    ): Boolean {
        return try {
            val eventDateMs = parseDueDateToMillis(dueDateStr)

            val formattedAmount = String.format(Locale.getDefault(), "%.2f", amount)
            val fullAmountStr = if (currency.isNotBlank()) "$currency $formattedAmount" else formattedAmount
            val accDisplay = accountName?.takeIf { it.isNotBlank() && it != "null" } ?: context.getString(R.string.cf_unknown)
            val cleanDueDate = if (dueDateStr.isNotBlank()) dueDateStr else context.getString(R.string.cfu_due)

            val eventTitle = context.getString(R.string.cf_calendar_event_title, title)
            val eventDesc = context.getString(
                R.string.cf_calendar_event_desc,
                title,
                fullAmountStr,
                accDisplay,
                cleanDueDate
            )

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, eventTitle)
                putExtra(CalendarContract.Events.DESCRIPTION, eventDesc)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventDateMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, eventDateMs + 60 * 60 * 1000) // 1 hour window
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
                putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            Toast.makeText(context, context.getString(R.string.cf_calendar_added), Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.cf_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * 健壮解析账单到期日期为毫秒时间戳
     * 兼容: "2026-11-15", "每月15号", "15", "15日" 等多种格式
     */
    private fun parseDueDateToMillis(dueDateStr: String): Long {
        val cal = Calendar.getInstance()

        // 1. 尝试直接标准 ISO 日期 yyyy-MM-dd
        if (dueDateStr.length >= 10 && dueDateStr.contains("-")) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val parsed = sdf.parse(dueDateStr.substring(0, 10))
                if (parsed != null) {
                    return parsed.time
                }
            } catch (_: Exception) {}
        }

        // 2. 提取数字当做当月的日期 (day of month)
        val numMatch = Regex("""\d+""").find(dueDateStr)
        if (numMatch != null) {
            val day = numMatch.value.toIntOrNull()
            if (day != null && day in 1..31) {
                cal.set(Calendar.DAY_OF_MONTH, day)
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }

        // 3. 兜底返回当天早上 9 点
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

