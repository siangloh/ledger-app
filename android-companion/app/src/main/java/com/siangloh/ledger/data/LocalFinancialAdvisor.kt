package com.siangloh.ledger.data

import android.content.Context
import androidx.compose.runtime.Immutable
import com.siangloh.ledger.R
import com.siangloh.ledger.ui.compose.CategoryUiHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 离线优先的深度本地智能财务诊断引擎 (Deep On-Device Financial Advisor)
 *
 * 结合收支流水明细、固定待缴账单、消费日历节奏、预算与多账户流动性，
 * 全面剖析月终收支预测、50/30/20 支出结构、高频漏斗、周末消费规律与应急安全垫。
 */
object LocalFinancialAdvisor {

    enum class InsightType {
        WARNING,  // 预警 / 超支 / 消费过快 / 信用高占用
        POSITIVE, // 储蓄成效 / 现金流健康 / 储备充裕
        TIP       // 节奏把控 / 行为洞察 / 账单预留建议
    }

    @Immutable
    data class FinancialInsightItem(
        val type: InsightType,
        val title: String,
        val description: String,
        val tag: String
    )

    @Immutable
    data class CategoryHealthItem(
        val categoryName: String,
        val localizedName: String,
        val amount: Double,
        val currency: String,
        val isFixed: Boolean,
        val isOk: Boolean,
        val statusLabel: String
    )

    @Immutable
    data class FinancialHealthReport(
        val score: Int,            // 0..100
        val scoreGrade: String,
        val summary: String,
        val savingsRatePct: Double,
        val topExpenseCategory: String?,
        val topExpensePct: Double,
        val dailySafeAllowance: Double,
        val insights: List<FinancialInsightItem>,
        val categoryHealth: List<CategoryHealthItem> = emptyList(),
        val projectedMonthEndNet: Double = 0.0,
        val hasProjection: Boolean = false,
        val needsPct: Double = 0.0,
        val wantsPct: Double = 0.0,
        val savedPct: Double = 0.0,
        val runwayMonths: Double? = null
    )

    data class TxSample(
        val date: String,
        val amount: Double,
        val category: String,
        val note: String,
        val isFixed: Boolean
    )

    data class RepeatMerchantStat(
        val merchant: String,
        val count: Int,
        val total: Double,
        val avgPerVisit: Double,
        val annualizedCost: Double
    )

    data class OutlierSpendStat(
        val name: String,
        val amount: Double,
        val shareOfWantsPct: Double
    )

    data class WeekendSkewStat(
        val weekendDailyAvg: Double,
        val weekdayDailyAvg: Double,
        val ratio: Double,
        val estimatedMonthlySavable: Double
    )

    data class CycleMetricsSnapshot(
        val needsPct: Double,
        val wantsPct: Double,
        val savedPct: Double,
        val variableDailyRate: Double,
        val projectedMonthEndExpense: Double,
        val projectedMonthEndNet: Double,
        val dailySafeAllowance: Double,
        val targetDailyCapToBreakEven: Double,
        val repeatMerchant: RepeatMerchantStat?,
        val outlierSpend: OutlierSpendStat?,
        val weekendSkew: WeekendSkewStat?,
        val runwayMonths: Double?,
        val highCreditCard: Pair<String, Double>? // (accountName, usedRatioPct) with balance
    )

    private val FIXED_ESSENTIAL_KEYWORDS = listOf(
        "住房", "房租", "租金", "账单", "水电", "物业", "房贷", "分期", "保险", "通讯", "转账",
        "housing", "rent", "rental", "utilities", "utility", "bill", "mortgage",
        "installment", "insurance", "telecom", "internet", "transfer",
        "sewa", "bil", "pinjaman", "insurans", "ansuran", "perumahan"
    )

    fun isFixedEssentialName(rawCategory: String, localizedCategory: String = rawCategory): Boolean {
        val candidates = listOf(rawCategory, localizedCategory)
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return false
        return candidates.any { c -> FIXED_ESSENTIAL_KEYWORDS.any { k -> c.contains(k) } }
    }

    private fun isFixedEssentialCategory(context: Context, rawCategory: String): Boolean {
        val localized = CategoryUiHelper.getLocalizedCategoryName(context, rawCategory)
        return isFixedEssentialName(rawCategory, localized)
    }

    /**
     * 纯计算核心：脱离 Android Context 与 UI 独立测算深层消费指标、商户高频漏斗、周末规律与安全垫
     */
    object AdvisorCalculator {

        private val TRAILING_REF_REGEX = Regex("""\s*(?:#\d+|ref[:\s]*\w+|\(\s*AA.*?\)|\d{4,})$""", RegexOption.IGNORE_CASE)

        fun normalizeMerchantLabel(note: String): String {
            val cleaned = TRAILING_REF_REGEX.replace(note.trim(), "").trim()
            return if (cleaned.length >= 2) cleaned.take(28) else ""
        }

        fun detectRepeatMerchant(discretionaryTxs: List<TxSample>): RepeatMerchantStat? {
            if (discretionaryTxs.size < 3) return null
            val grouped = discretionaryTxs
                .mapNotNull { tx ->
                    val label = normalizeMerchantLabel(tx.note).ifBlank { tx.category.trim() }
                    if (label.isBlank()) null else (label to tx.amount)
                }
                .groupBy({ it.first.lowercase(Locale.US) }, { it })

            val top = grouped.values
                .filter { it.size >= 3 && it.sumOf { pair -> pair.second } >= 40.0 }
                .maxByOrNull { it.sumOf { pair -> pair.second } }
                ?: return null

            val total = top.sumOf { it.second }
            val count = top.size
            val avg = total / count
            return RepeatMerchantStat(
                merchant = top.first().first,
                count = count,
                total = total,
                avgPerVisit = avg,
                annualizedCost = total * 12.0
            )
        }

        fun detectOutlierSpend(discretionaryTxs: List<TxSample>, discretionaryTotal: Double): OutlierSpendStat? {
            if (discretionaryTxs.size < 4 || discretionaryTotal <= 0.0) return null
            val sortedAmounts = discretionaryTxs.map { it.amount }.sorted()
            val median = sortedAmounts[sortedAmounts.size / 2]
            val maxTx = discretionaryTxs.maxByOrNull { it.amount } ?: return null
            val sharePct = (maxTx.amount / discretionaryTotal) * 100.0
            if (maxTx.amount >= 80.0 && maxTx.amount >= median * 2.5 && sharePct >= 28.0) {
                val label = normalizeMerchantLabel(maxTx.note).ifBlank { maxTx.category }
                return OutlierSpendStat(name = label, amount = maxTx.amount, shareOfWantsPct = sharePct)
            }
            return null
        }

        fun detectWeekendSkew(discretionaryTxs: List<TxSample>): WeekendSkewStat? {
            if (discretionaryTxs.size < 5) return null
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()
            val weekendDays = mutableMapOf<String, Double>()
            val weekdayDays = mutableMapOf<String, Double>()

            for (tx in discretionaryTxs) {
                if (tx.date.length < 10) continue
                val parsed = try { sdf.parse(tx.date.take(10)) } catch (_: Exception) { null } ?: continue
                cal.time = parsed
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val isWeekend = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
                val target = if (isWeekend) weekendDays else weekdayDays
                target[tx.date.take(10)] = (target[tx.date.take(10)] ?: 0.0) + tx.amount
            }

            if (weekendDays.size < 2 || weekdayDays.size < 3) return null
            val wkndAvg = weekendDays.values.average()
            val wkdyAvg = weekdayDays.values.average()
            if (wkdyAvg <= 0.0) return null

            val ratio = wkndAvg / wkdyAvg
            val monthlySavable = max(0.0, (wkndAvg - wkdyAvg) * 4.0)
            return if (ratio >= 1.6 && monthlySavable >= 40.0) {
                WeekendSkewStat(
                    weekendDailyAvg = wkndAvg,
                    weekdayDailyAvg = wkdyAvg,
                    ratio = ratio,
                    estimatedMonthlySavable = monthlySavable
                )
            } else {
                null
            }
        }

        fun computeMetrics(
            income: Double,
            expense: Double,
            savings: Double,
            available: Double,
            pendingBillsTotal: Double,
            fixedExpenseTotal: Double,
            discretionaryExpenseTotal: Double,
            overallBudgetLimit: Double,
            passedDays: Int,
            remainingDays: Int,
            totalDays: Int,
            discretionaryTxs: List<TxSample>,
            accounts: List<AccountRecord>
        ): CycleMetricsSnapshot {
            val safePassed = passedDays.coerceAtLeast(1)
            val safeRemaining = remainingDays.coerceAtLeast(1)
            val safeTotal = max(safePassed + safeRemaining - 1, totalDays.coerceAtLeast(28))

            // 50/30/20 structure ratios against income (or total flow if income absent)
            val denom = if (income > 0.0) income else max(1.0, expense + savings)
            val retainedRaw = if (income > 0.0) max(0.0, income - expense) else savings
            val needsPct = ((fixedExpenseTotal / denom) * 100.0).coerceIn(0.0, 100.0)
            val wantsPct = ((discretionaryExpenseTotal / denom) * 100.0).coerceIn(0.0, 100.0)
            val savedPct = ((retainedRaw / denom) * 100.0).coerceIn(0.0, 100.0)

            // Variable burn projection (separates fixed one-off bills from daily variable pace)
            val variableDailyRate = discretionaryExpenseTotal / safePassed
            val projectedVariable = variableDailyRate * safeTotal
            val projectedMonthEndExpense = fixedExpenseTotal + pendingBillsTotal + projectedVariable
            val projectedMonthEndNet = income - projectedMonthEndExpense

            // True Daily Safe Allowance: tighten by remaining budget AND remaining free cash after bills
            val freeCashAfterBills = if (income > 0.0) min(available, income - expense - pendingBillsTotal) else available
            val dailyByCash = max(0.0, freeCashAfterBills) / safeRemaining
            val dailySafeAllowance = if (overallBudgetLimit > 0.0) {
                val dailyByBudget = max(0.0, overallBudgetLimit - expense) / safeRemaining
                if (income > 0.0) min(dailyByBudget, dailyByCash) else dailyByBudget
            } else {
                dailyByCash
            }

            val targetDailyCapToBreakEven = max(0.0, (income - expense - pendingBillsTotal) / safeRemaining)

            // Liquidity Runway & Credit Card Utilization from AccountRecord balances
            val liquidTypes = setOf("savings", "current", "e_wallet", "cash")
            val liquidSum = accounts.filter { it.type in liquidTypes && it.balance > 0.0 }.sumOf { it.balance }
            val monthlyBaselineBurn = max(expense + pendingBillsTotal, projectedMonthEndExpense)
            val runwayMonths = if (accounts.any { it.type in liquidTypes } && monthlyBaselineBurn >= 100.0) {
                (liquidSum / monthlyBaselineBurn).coerceIn(0.0, 60.0)
            } else null

            val worstCard = accounts
                .filter { it.type == "credit_card" && it.creditLimit > 0.0 }
                .map { acc ->
                    val utilizedPct = (abs(acc.balance) / acc.creditLimit) * 100.0
                    Triple(acc.name, utilizedPct, abs(acc.balance))
                }
                .filter { it.second >= 45.0 }
                .maxByOrNull { it.second }

            val repeatMerchant = detectRepeatMerchant(discretionaryTxs)
            val outlierSpend = detectOutlierSpend(discretionaryTxs, discretionaryExpenseTotal)
            val weekendSkew = detectWeekendSkew(discretionaryTxs)

            return CycleMetricsSnapshot(
                needsPct = needsPct,
                wantsPct = wantsPct,
                savedPct = savedPct,
                variableDailyRate = variableDailyRate,
                projectedMonthEndExpense = projectedMonthEndExpense,
                projectedMonthEndNet = projectedMonthEndNet,
                dailySafeAllowance = dailySafeAllowance,
                targetDailyCapToBreakEven = targetDailyCapToBreakEven,
                repeatMerchant = repeatMerchant,
                outlierSpend = outlierSpend,
                weekendSkew = weekendSkew,
                runwayMonths = runwayMonths,
                highCreditCard = worstCard?.let { it.first to it.second }
            )
        }
    }

    /**
     * 根据当前收支报告数据、预算概览及账户余额，生成多维度深度财务诊断报告
     */
    fun analyze(
        context: Context,
        cashflowData: JSONObject,
        budgetOverview: BudgetOverviewRecord? = null,
        accounts: List<AccountRecord> = emptyList()
    ): FinancialHealthReport {
        val currency = cashflowData.optString("currency", "RM").ifBlank { "RM" }
        val income = max(0.0, cashflowData.optDouble("income", 0.0))
        val expense = max(0.0, cashflowData.optDouble("expense", 0.0))
        val savings = max(0.0, cashflowData.optDouble("savings", 0.0))
        val pendingBillsTotal = max(0.0, cashflowData.optDouble("reserved", 0.0))
        val available = cashflowData.optDouble("available", income - expense - pendingBillsTotal)
        val netCashflow = income - expense

        val savingsRatePct = if (income > 0) {
            min(100.0, (max(0.0, netCashflow) / income) * 100.0)
        } else 0.0

        // Categorize expenses into fixed essentials vs controllable discretionary
        val expCategories = cashflowData.optJSONArray("expense_categories")
        val rawCategoryEntries = mutableListOf<Pair<String, Double>>()
        if (expCategories != null) {
            for (i in 0 until expCategories.length()) {
                val obj = expCategories.optJSONObject(i) ?: continue
                val lbl = obj.optString("label").trim()
                val amt = obj.optDouble("amount", 0.0)
                if (lbl.isNotEmpty() && amt > 0.0) {
                    rawCategoryEntries.add(lbl to amt)
                }
            }
        }
        val fixedEntries = rawCategoryEntries.filter { isFixedEssentialCategory(context, it.first) }
        val discretionaryEntries = rawCategoryEntries.filterNot { isFixedEssentialCategory(context, it.first) }
        val fixedTotal = fixedEntries.sumOf { it.second }
        val discretionaryTotal = discretionaryEntries.sumOf { it.second }
        val topDiscretionary = discretionaryEntries.maxByOrNull { it.second }
        val topCategoryName = topDiscretionary?.first
        val topCategoryAmount = topDiscretionary?.second ?: 0.0
        val topCategoryPct = if (discretionaryTotal > 0.0 && topCategoryAmount > 0.0) {
            min(100.0, (topCategoryAmount / discretionaryTotal) * 100.0)
        } else 0.0

        // Billing cycle pacing
        val cycle = budgetOverview?.billingCycle
        val now = Calendar.getInstance()
        val calMaxDays = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val calDay = now.get(Calendar.DAY_OF_MONTH)
        val totalDays = cycle?.totalDays ?: calMaxDays
        val passedDays = cycle?.passedDays ?: calDay
        val remainingDays = cycle?.remainingDays ?: max(1, calMaxDays - calDay + 1)

        // Extract individual expense transactions for behavioral pattern mining
        val txArray = cashflowData.optJSONArray("transactions")
        val discretionaryTxs = mutableListOf<TxSample>()
        if (txArray != null) {
            for (i in 0 until txArray.length()) {
                val tx = txArray.optJSONObject(i) ?: continue
                if (tx.optString("type") != "expense") continue
                val amt = tx.optDouble("amount", 0.0)
                if (amt <= 0.0) continue
                val cat = tx.optString("category", "").trim()
                val fixed = isFixedEssentialCategory(context, cat)
                if (!fixed) {
                    discretionaryTxs.add(
                        TxSample(
                            date = tx.optString("date", ""),
                            amount = amt,
                            category = cat,
                            note = tx.optString("note", ""),
                            isFixed = false
                        )
                    )
                }
            }
        }

        // Extract unpaid fixed bills details
        val billsArray = cashflowData.optJSONArray("bills")
        val unpaidBills = mutableListOf<Pair<String, Double>>()
        if (billsArray != null) {
            for (i in 0 until billsArray.length()) {
                val b = billsArray.optJSONObject(i) ?: continue
                val isPaid = b.optBoolean("is_paid", true)
                val pend = b.optDouble("pending", 0.0)
                if (!isPaid && pend > 0.0) {
                    unpaidBills.add(b.optString("title", "Bill") to pend)
                }
            }
        }
        val computedPendingTotal = if (pendingBillsTotal > 0.0) pendingBillsTotal else unpaidBills.sumOf { it.second }

        val overallBudgetLimit = budgetOverview?.overall?.limit ?: 0.0
        val metrics = AdvisorCalculator.computeMetrics(
            income = income,
            expense = expense,
            savings = savings,
            available = available,
            pendingBillsTotal = computedPendingTotal,
            fixedExpenseTotal = fixedTotal,
            discretionaryExpenseTotal = discretionaryTotal,
            overallBudgetLimit = overallBudgetLimit,
            passedDays = passedDays,
            remainingDays = remainingDays,
            totalDays = totalDays,
            discretionaryTxs = discretionaryTxs,
            accounts = accounts
        )

        // Multi-factor Health Score (0 ~ 100)
        var score = 70
        if (income > 0.0) {
            when {
                savingsRatePct >= 30.0 -> score += 14
                savingsRatePct >= 20.0 -> score += 9
                savingsRatePct >= 10.0 -> score += 4
                netCashflow < 0.0 -> score -= 16
            }
            if (passedDays >= 4 && metrics.projectedMonthEndNet < 0.0) {
                score -= 6 // Pacing toward month-end deficit
            }
        } else if (expense > 0.0) {
            score -= 10
        }

        val overCount = budgetOverview?.overCount ?: 0
        val reachedCount = budgetOverview?.categories?.count { it.level == "reached" } ?: 0
        when {
            overCount > 0 -> score -= (overCount * 11).coerceAtMost(24)
            reachedCount > 0 -> score -= (reachedCount * 4).coerceAtMost(10)
            overallBudgetLimit > 0.0 && (expense / overallBudgetLimit) <= 0.8 -> score += 8
            else -> score += 4
        }

        when {
            expense <= 0.0 -> score += 4
            topCategoryPct > 55.0 -> score -= 8
            topCategoryPct > 40.0 -> score -= 4
            topCategoryPct <= 30.0 -> score += 4
        }

        if (savings > 0.0) score += 4
        metrics.runwayMonths?.let { rm ->
            if (rm >= 6.0) score += 5 else if (rm < 1.5) score -= 5
        }
        if (metrics.highCreditCard != null) score -= 4

        score = score.coerceIn(20, 100)

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

        fun fmtAmt(v: Double): String = String.format(Locale.getDefault(), "%s %.2f", currency, v)

        // Generate Prioritized Deep Insights (max 5 crisp actionable items)
        val insightList = mutableListOf<FinancialInsightItem>()

        // 1) Budget overrun or Top Discretionary Concentration
        val overBudgetCat = budgetOverview?.categories?.firstOrNull { it.level == "over" }
        val reachedBudgetCat = budgetOverview?.categories?.firstOrNull { it.level == "reached" }
        when {
            overBudgetCat != null -> {
                val locName = CategoryUiHelper.getLocalizedCategoryName(context, overBudgetCat.category)
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.WARNING,
                        title = context.getString(R.string.ai_advisor_title_overspent, locName, fmtAmt(abs(overBudgetCat.remaining))),
                        description = context.getString(R.string.ai_advisor_desc_overspent),
                        tag = context.getString(R.string.ai_advisor_tag_warning)
                    )
                )
            }
            reachedBudgetCat != null -> {
                val locName = CategoryUiHelper.getLocalizedCategoryName(context, reachedBudgetCat.category)
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.WARNING,
                        title = context.getString(R.string.ai_advisor_title_reached, locName),
                        description = context.getString(R.string.ai_advisor_desc_reached),
                        tag = context.getString(R.string.ai_advisor_tag_full)
                    )
                )
            }
            topCategoryName != null && topCategoryPct >= 35.0 && topCategoryAmount > 0.0 -> {
                val locName = CategoryUiHelper.getLocalizedCategoryName(context, topCategoryName)
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.WARNING,
                        title = context.getString(R.string.ai_advisor_title_concentrated, locName, topCategoryPct),
                        description = context.getString(R.string.ai_advisor_desc_concentrated, fmtAmt(topCategoryAmount * 0.15)),
                        tag = context.getString(R.string.ai_advisor_tag_optimize)
                    )
                )
            }
        }

        // 2) Month-End Burn Forecast vs Break-Even Pace
        val hasProjection = passedDays >= 2 && (income > 0.0 || expense > 0.0)
        if (hasProjection && income > 0.0) {
            if (metrics.projectedMonthEndNet < -10.0) {
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.WARNING,
                        title = context.getString(R.string.ai_advisor_title_burn_warn, fmtAmt(abs(metrics.projectedMonthEndNet))),
                        description = context.getString(
                            R.string.ai_advisor_desc_burn_warn,
                            fmtAmt(metrics.variableDailyRate),
                            fmtAmt(metrics.projectedMonthEndExpense),
                            fmtAmt(metrics.targetDailyCapToBreakEven)
                        ),
                        tag = context.getString(R.string.ai_advisor_tag_forecast)
                    )
                )
            } else {
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.POSITIVE,
                        title = context.getString(R.string.ai_advisor_title_burn_ok, fmtAmt(metrics.projectedMonthEndNet)),
                        description = context.getString(R.string.ai_advisor_desc_burn_ok, fmtAmt(metrics.variableDailyRate)),
                        tag = context.getString(R.string.ai_advisor_tag_forecast)
                    )
                )
            }
        }

        // 3) Pending Fixed Bills Cash Guard
        if (unpaidBills.isNotEmpty() && computedPendingTotal > 0.0) {
            val topBillNames = unpaidBills.sortedByDescending { it.second }.take(2).joinToString("、") { it.first }
            val isTight = available < 0.0
            insightList.add(
                FinancialInsightItem(
                    type = if (isTight) InsightType.WARNING else InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_pending_bills, unpaidBills.size, fmtAmt(computedPendingTotal)),
                    description = if (isTight) {
                        context.getString(R.string.ai_advisor_desc_pending_bills_tight, topBillNames, fmtAmt(abs(available)))
                    } else {
                        context.getString(R.string.ai_advisor_desc_pending_bills_ok, topBillNames, fmtAmt(available))
                    },
                    tag = context.getString(R.string.ai_advisor_tag_bills)
                )
            )
        }

        // 4) Credit Card High Utilization Alert (if any)
        metrics.highCreditCard?.let { (cardName, usedPct) ->
            val cardBal = abs(accounts.firstOrNull { it.name == cardName }?.balance ?: 0.0)
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(R.string.ai_advisor_title_credit_high, cardName, usedPct),
                    description = context.getString(R.string.ai_advisor_desc_credit_high, fmtAmt(cardBal)),
                    tag = context.getString(R.string.ai_advisor_tag_credit)
                )
            )
        }

        // 5) Behavioral Pattern: Repeat Merchant Annualized Leak OR Weekend vs Weekday Skew OR Single Outlier
        val repeat = metrics.repeatMerchant
        val weekend = metrics.weekendSkew
        val outlier = metrics.outlierSpend
        when {
            repeat != null -> {
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.TIP,
                        title = context.getString(R.string.ai_advisor_title_repeat_merchant, repeat.merchant, repeat.count),
                        description = context.getString(
                            R.string.ai_advisor_desc_repeat_merchant,
                            fmtAmt(repeat.total),
                            fmtAmt(repeat.avgPerVisit),
                            fmtAmt(repeat.annualizedCost)
                        ),
                        tag = context.getString(R.string.ai_advisor_tag_habit)
                    )
                )
            }
            weekend != null -> {
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.TIP,
                        title = context.getString(R.string.ai_advisor_title_weekend_skew, weekend.ratio),
                        description = context.getString(
                            R.string.ai_advisor_desc_weekend_skew,
                            fmtAmt(weekend.weekendDailyAvg),
                            fmtAmt(weekend.weekdayDailyAvg),
                            fmtAmt(weekend.estimatedMonthlySavable)
                        ),
                        tag = context.getString(R.string.ai_advisor_tag_weekend)
                    )
                )
            }
            outlier != null -> {
                insightList.add(
                    FinancialInsightItem(
                        type = InsightType.TIP,
                        title = context.getString(R.string.ai_advisor_title_outlier_spend, outlier.name, outlier.shareOfWantsPct),
                        description = context.getString(R.string.ai_advisor_desc_outlier_spend, fmtAmt(outlier.amount)),
                        tag = context.getString(R.string.ai_advisor_tag_optimize)
                    )
                )
            }
        }

        // 6) Secondary Behavioral Insight (Weekend if Repeat already shown) OR Liquid Emergency Runway
        if (repeat != null && weekend != null) {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_weekend_skew, weekend.ratio),
                    description = context.getString(
                        R.string.ai_advisor_desc_weekend_skew,
                        fmtAmt(weekend.weekendDailyAvg),
                        fmtAmt(weekend.weekdayDailyAvg),
                        fmtAmt(weekend.estimatedMonthlySavable)
                    ),
                    tag = context.getString(R.string.ai_advisor_tag_weekend)
                )
            )
        } else {
            metrics.runwayMonths?.let { runway ->
                when {
                    runway < 3.0 -> insightList.add(
                        FinancialInsightItem(
                            type = InsightType.WARNING,
                            title = context.getString(R.string.ai_advisor_title_runway_low, runway),
                            description = context.getString(R.string.ai_advisor_desc_runway_low),
                            tag = context.getString(R.string.ai_advisor_tag_runway)
                        )
                    )
                    runway >= 6.0 -> {
                        insightList.add(
                            FinancialInsightItem(
                                type = InsightType.POSITIVE,
                                title = context.getString(R.string.ai_advisor_title_runway_strong, runway),
                                description = context.getString(R.string.ai_advisor_desc_runway_strong),
                                tag = context.getString(R.string.ai_advisor_tag_runway)
                            )
                        )
                    }
                    else -> Unit
                }
            }
        }

        // 7) Fallback / Pacing / Deficit if fewer than 3 insights
        if (netCashflow < 0.0 && insightList.none { it.tag == context.getString(R.string.ai_advisor_tag_forecast) }) {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.WARNING,
                    title = context.getString(R.string.ai_advisor_title_deficit),
                    description = context.getString(R.string.ai_advisor_desc_deficit, fmtAmt(abs(netCashflow))),
                    tag = context.getString(R.string.ai_advisor_tag_deficit)
                )
            )
        }
        if (insightList.size < 3 && remainingDays > 0 && metrics.dailySafeAllowance > 0.0) {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.TIP,
                    title = context.getString(R.string.ai_advisor_title_daily_safe, fmtAmt(metrics.dailySafeAllowance)),
                    description = context.getString(R.string.ai_advisor_desc_daily_safe, remainingDays),
                    tag = context.getString(R.string.ai_advisor_tag_pacing)
                )
            )
        }
        if (insightList.isEmpty()) {
            insightList.add(
                FinancialInsightItem(
                    type = InsightType.POSITIVE,
                    title = context.getString(R.string.ai_advisor_title_balanced_cats),
                    description = context.getString(R.string.ai_advisor_desc_balanced_cats),
                    tag = context.getString(R.string.ai_advisor_tag_healthy)
                )
            )
        }

        val sortedInsights = insightList
            .sortedBy {
                when (it.type) {
                    InsightType.WARNING -> 0
                    InsightType.TIP -> 1
                    InsightType.POSITIVE -> 2
                }
            }
            .take(5)

        // Category Health Checklist
        val budgetByCategory = budgetOverview?.categories?.associateBy { it.category.trim().lowercase(Locale.US) } ?: emptyMap()
        val categoryHealthList = rawCategoryEntries.map { (catName, amt) ->
            val localized = CategoryUiHelper.getLocalizedCategoryName(context, catName)
            val isFixed = isFixedEssentialCategory(context, catName)
            val budgetItem = budgetByCategory[catName.trim().lowercase(Locale.US)]
                ?: budgetByCategory[localized.trim().lowercase(Locale.US)]
            val shareOfDiscretionary = if (!isFixed && discretionaryTotal > 0.0) (amt / discretionaryTotal) * 100.0 else 0.0

            val (isOk, statusLabel) = when {
                budgetItem?.level == "over" -> false to context.getString(R.string.ai_advisor_status_over)
                budgetItem?.level == "reached" -> false to context.getString(R.string.ai_advisor_status_reached)
                isFixed -> true to context.getString(R.string.ai_advisor_status_fixed)
                discretionaryEntries.size >= 2 && shareOfDiscretionary >= 40.0 -> false to context.getString(R.string.ai_advisor_status_high)
                else -> true to context.getString(R.string.ai_advisor_status_ok)
            }
            CategoryHealthItem(
                categoryName = catName,
                localizedName = localized,
                amount = amt,
                currency = currency,
                isFixed = isFixed,
                isOk = isOk,
                statusLabel = statusLabel
            )
        }.sortedWith(compareBy<CategoryHealthItem> { it.isOk }.thenByDescending { it.amount })

        return FinancialHealthReport(
            score = score,
            scoreGrade = grade,
            summary = summary,
            savingsRatePct = savingsRatePct,
            topExpenseCategory = topCategoryName,
            topExpensePct = topCategoryPct,
            dailySafeAllowance = metrics.dailySafeAllowance,
            insights = sortedInsights,
            categoryHealth = categoryHealthList,
            projectedMonthEndNet = metrics.projectedMonthEndNet,
            hasProjection = hasProjection,
            needsPct = metrics.needsPct,
            wantsPct = metrics.wantsPct,
            savedPct = metrics.savedPct,
            runwayMonths = metrics.runwayMonths
        )
    }
}
