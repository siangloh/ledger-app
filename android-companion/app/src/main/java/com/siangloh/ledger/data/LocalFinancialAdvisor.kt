package com.siangloh.ledger.data

import android.content.Context
import com.siangloh.ledger.R
import com.siangloh.ledger.ui.compose.CategoryUiHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 离线优先的本地智能财务诊断引擎 (Local-First On-Device Financial Advisor)
 *
 * 100% 运行在手机端本地，脱敏直接读取本地账本统计指标，
 * 无需外部网络、无需 Laptop 电脑连接、无需任何 API Key，开箱即用。
 */
object LocalFinancialAdvisor {

    enum class InsightType {
        WARNING,  // 预警 / 超支 / 集中度偏高
        POSITIVE, // 储蓄成效 / 现金流健康
        TIP       // 节奏把控 / 每日安全额度建议
    }

    data class FinancialInsightItem(
        val type: InsightType,
        val title: String,
        val description: String,
        val tag: String
    )

    data class FinancialHealthReport(
        val score: Int,            // 0..100
        val scoreGrade: String,    // 优良 / 稳健 / 需关注 / 预警
        val summary: String,
        val savingsRatePct: Double,
        val topExpenseCategory: String?,
        val topExpensePct: Double,
        val dailySafeAllowance: Double,
        val insights: List<FinancialInsightItem>
    )

    /**
     * 根据当前收支报告数据与预算概览，秒级生成综合财务诊断报告
     */
    fun analyze(
        context: Context,
        cashflowData: JSONObject,
        budgetOverview: BudgetOverviewRecord? = null
    ): FinancialHealthReport {
        val currency = cashflowData.optString("currency", "RM").ifBlank { "RM" }
        val income = max(0.0, cashflowData.optDouble("income", 0.0))
        val expense = max(0.0, cashflowData.optDouble("expense", 0.0))
        val savings = max(0.0, cashflowData.optDouble("savings", 0.0))
        val available = cashflowData.optDouble("available", income - expense - savings)
        val netCashflow = income - expense // 纯收支差额

        // 1. 储蓄率指标 (Retention / Savings Rate)
        val savingsRatePct = if (income > 0) {
            val retained = max(0.0, netCashflow)
            min(100.0, (retained / income) * 100.0)
        } else {
            0.0
        }

        // 2. 分类支出集中度分析 (Top Category Analysis)
        val expCategories = cashflowData.optJSONArray("expense_categories")
        var topCategoryName: String? = null
        var topCategoryAmount = 0.0
        if (expCategories != null && expCategories.length() > 0) {
            val first = expCategories.optJSONObject(0)
            if (first != null) {
                topCategoryName = first.optString("label")
                topCategoryAmount = first.optDouble("amount", 0.0)
            }
        }
        val topCategoryPct = if (expense > 0.0 && topCategoryAmount > 0.0) {
            min(100.0, (topCategoryAmount / expense) * 100.0)
        } else {
            0.0
        }

        // 3. 周期与剩余天数计算
        val remainingDays = budgetOverview?.billingCycle?.remainingDays ?: run {
            val now = Calendar.getInstance()
            val totalDays = now.getActualMaximum(Calendar.DAY_OF_MONTH)
            val currentDay = now.get(Calendar.DAY_OF_MONTH)
            max(1, totalDays - currentDay + 1)
        }

        // 4. 每日安全花费建议 (Daily Safe Allowance)
        val overallBudgetLimit = budgetOverview?.overall?.limit ?: 0.0
        val dailySafeAllowance = if (remainingDays > 0) {
            if (overallBudgetLimit > 0.0) {
                val remBudget = max(0.0, overallBudgetLimit - expense)
                remBudget / remainingDays
            } else if (available > 0.0) {
                available / remainingDays
            } else {
                0.0
            }
        } else {
            0.0
        }

        // 5. 财务健康综合评分测算 (0 ~ 100 综合分)
        var score = 70 // 基准分

        // 维度 A: 现金流与净结余能力 (权重 40 分)
        if (income > 0) {
            when {
                savingsRatePct >= 30.0 -> score += 15
                savingsRatePct >= 20.0 -> score += 10
                savingsRatePct >= 10.0 -> score += 5
                savingsRatePct >= 0.0 -> score += 0
                else -> score -= 15 // 赤字扣分
            }
        } else if (expense > 0) {
            score -= 10
        }

        // 维度 B: 预算执行与超支控制 (权重 30 分)
        val overCount = budgetOverview?.overCount ?: 0
        val reachedCount = budgetOverview?.categories?.count { it.level == "reached" } ?: 0
        when {
            overCount > 0 -> score -= (overCount * 12).coerceAtMost(25)
            reachedCount > 0 -> score -= (reachedCount * 4).coerceAtMost(10)
            overallBudgetLimit > 0.0 && (expense / overallBudgetLimit) <= 0.8 -> score += 10
            else -> score += 5
        }

        // 维度 C: 支出集中度健康度 (权重 20 分)
        when {
            expense <= 0.0 -> score += 5
            topCategoryPct > 55.0 -> score -= 10
            topCategoryPct > 40.0 -> score -= 5
            topCategoryPct <= 30.0 -> score += 5
        }

        // 维度 D: 储蓄习惯 (权重 10 分)
        if (savings > 0.0) {
            score += 5
        }

        // 最终综合分归一化
        score = score.coerceIn(20, 100)

        // 评级与核心总评
        val (grade, summary) = when {
            score >= 85 -> Pair(
                context.getString(R.string.ai_advisor_score_excellent),
                context.getString(R.string.ai_advisor_summary_excellent)
            )
            score >= 70 -> Pair(
                context.getString(R.string.ai_advisor_score_healthy),
                context.getString(R.string.ai_advisor_summary_healthy)
            )
            score >= 55 -> Pair(
                context.getString(R.string.ai_advisor_score_moderate),
                context.getString(R.string.ai_advisor_summary_moderate)
            )
            else -> Pair(
                context.getString(R.string.ai_advisor_score_warning),
                context.getString(R.string.ai_advisor_summary_warning)
            )
        }

        // 6. 生成 3 条结构化诊断建议
        val insightList = mutableListOf<FinancialInsightItem>()

        // 条目 1: 超支预警 / 集中度诊断 (Warning / Attention)
        val overBudgetCat = budgetOverview?.categories?.firstOrNull { it.level == "over" }
        val reachedBudgetCat = budgetOverview?.categories?.firstOrNull { it.level == "reached" }
        if (overBudgetCat != null) {
            val localizedName = CategoryUiHelper.getLocalizedCategoryName(context, overBudgetCat.category)
            val overAmt = Math.abs(overBudgetCat.remaining)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(
                        R.string.ai_advisor_title_overspent,
                        localizedName,
                        String.format(Locale.getDefault(), "%s %.2f", currency, overAmt)
                    ),
                    description = context.getString(R.string.ai_advisor_desc_overspent),
                    tag = context.getString(R.string.ai_advisor_tag_warning)
                )
            )
        } else if (reachedBudgetCat != null) {
            val localizedName = CategoryUiHelper.getLocalizedCategoryName(context, reachedBudgetCat.category)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(R.string.ai_advisor_title_reached, localizedName),
                    description = context.getString(R.string.ai_advisor_desc_reached),
                    tag = context.getString(R.string.ai_advisor_tag_full)
                )
            )
        } else if (topCategoryName != null && topCategoryPct >= 35.0 && topCategoryAmount > 0.0) {
            val localizedName = CategoryUiHelper.getLocalizedCategoryName(context, topCategoryName)
            val potentialSavings = topCategoryAmount * 0.15
            val savingsStr = String.format(Locale.getDefault(), "%s %.2f", currency, potentialSavings)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(R.string.ai_advisor_title_concentrated, localizedName, topCategoryPct),
                    description = context.getString(R.string.ai_advisor_desc_concentrated, savingsStr),
                    tag = context.getString(R.string.ai_advisor_tag_optimize)
                )
            )
        } else {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.POSITIVE,
                    title = context.getString(R.string.ai_advisor_title_balanced_cats),
                    description = context.getString(R.string.ai_advisor_desc_balanced_cats),
                    tag = context.getString(R.string.ai_advisor_tag_healthy)
                )
            )
        }

        // 条目 2: 现金流与储蓄成效 (Savings & Cashflow Progress)
        if (netCashflow > 0.0 || savings > 0.0) {
            val netStr = String.format(Locale.getDefault(), "%s %.2f", currency, netCashflow)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.POSITIVE,
                    title = context.getString(R.string.ai_advisor_title_savings_good),
                    description = context.getString(R.string.ai_advisor_desc_savings_good, netStr, savingsRatePct),
                    tag = context.getString(R.string.ai_advisor_tag_savings)
                )
            )
        } else if (netCashflow < 0.0) {
            val deficitStr = String.format(Locale.getDefault(), "%s %.2f", currency, Math.abs(netCashflow))
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(R.string.ai_advisor_title_deficit),
                    description = context.getString(R.string.ai_advisor_desc_deficit, deficitStr),
                    tag = context.getString(R.string.ai_advisor_tag_deficit)
                )
            )
        } else {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_breakeven),
                    description = context.getString(R.string.ai_advisor_desc_breakeven),
                    tag = context.getString(R.string.ai_advisor_tag_balanced)
                )
            )
        }

        // 条目 3: 周期节奏把控与每日安全额度 (Daily Allowance & Pacing Tip)
        if (remainingDays > 0 && dailySafeAllowance > 0.0) {
            val allowanceStr = String.format(Locale.getDefault(), "%s %.2f", currency, dailySafeAllowance)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_daily_safe, allowanceStr),
                    description = context.getString(R.string.ai_advisor_desc_daily_safe, remainingDays),
                    tag = context.getString(R.string.ai_advisor_tag_pacing)
                )
            )
        } else {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_cycle_wrapup),
                    description = context.getString(R.string.ai_advisor_desc_cycle_wrapup),
                    tag = context.getString(R.string.ai_advisor_tag_planning)
                )
            )
        }

        return FinancialHealthReport(
            score = score,
            scoreGrade = grade,
            summary = summary,
            savingsRatePct = savingsRatePct,
            topExpenseCategory = topCategoryName,
            topExpensePct = topCategoryPct,
            dailySafeAllowance = dailySafeAllowance,
            insights = insightList
        )
    }
}

