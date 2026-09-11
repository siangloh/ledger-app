"""
Subscription Management & Renewal Alerts Module (订阅服务大厅与续费提醒)
Architectural Domain Models, Pure Mathematical & Date Rolling Functions, and Cron Worker System.
"""
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum
from typing import List, Dict, Optional, Callable, Any
import calendar


# ============================================================================
# 1. 业务领域枚举与实体模型 (Domain Models)
# ============================================================================

class BillingCycle(str, Enum):
    """计费周期枚举"""
    WEEKLY = "WEEKLY"
    MONTHLY = "MONTHLY"
    QUARTERLY = "QUARTERLY"
    SEMI_ANNUAL = "SEMI_ANNUAL"
    YEARLY = "YEARLY"


class SubscriptionStatus(str, Enum):
    """订阅生命周期状态"""
    ACTIVE = "ACTIVE"
    PAUSED = "PAUSED"
    CANCELLED = "CANCELLED"


class AlertLevel(str, Enum):
    """预警紧急等级"""
    CRITICAL = "CRITICAL"  # 紧急：试用到期/打算取消但即将扣款/今天扣款
    WARNING = "WARNING"    # 警告：1~3 天内即将自动续订
    INFO = "INFO"          # 提醒：常规排程期内续费


@dataclass
class Subscription:
    """订阅服务核心领域实体"""
    id: Any
    name: str
    category: str
    billing_cycle: BillingCycle
    cost: Decimal
    currency: str = "MYR"
    auto_renew: bool = True
    start_date: date = field(default_factory=date.today)
    next_billing_date: date = field(default_factory=date.today)
    payment_method_id: Optional[Any] = None
    payment_method_name: Optional[str] = None
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE
    cancellation_reminder_days: int = 3
    is_trial: bool = False
    trial_end_date: Optional[date] = None
    target_to_cancel: bool = False  # 标记为打算取消，防止意外被反向扣费
    anchor_day: Optional[int] = None  # 记录原始基准日（如31日），防止月末滚动漂移
    note: Optional[str] = None

    def __post_init__(self):
        if not isinstance(self.cost, Decimal):
            self.cost = Decimal(str(self.cost))
        if self.anchor_day is None and self.start_date:
            self.anchor_day = self.start_date.day


# ============================================================================
# 2. 前台看板与预警传输对象 (DTOs)
# ============================================================================

@dataclass
class BurnRateSummary:
    """开销统计 DTO"""
    monthly_equivalent_burn: Decimal
    annual_burn_rate: Decimal
    currency: str
    item_count: int


@dataclass
class RenewalAlertDTO:
    """续费与取消预警 DTO"""
    subscription_id: Any
    name: str
    category: str
    next_billing_date: date
    days_left: int
    cost: Decimal
    currency: str
    converted_cost: Decimal
    target_currency: str
    billing_cycle: BillingCycle
    payment_method_name: Optional[str]
    alert_level: AlertLevel
    alert_message: str
    auto_renew: bool
    is_trial: bool
    target_to_cancel: bool


@dataclass
class TopSpendingItemDTO:
    """单项开销占比 DTO"""
    subscription_id: Any
    name: str
    category: str
    monthly_equivalent_cost: Decimal
    annual_cost: Decimal
    currency: str
    percentage_of_total_annual: Decimal  # 占年开销百分比


@dataclass
class SubscriptionDashboardSummaryDTO:
    """订阅大厅聚合看板 DTO"""
    total_active_count: int
    total_paused_count: int
    total_cancelled_count: int
    total_monthly_burn: Decimal
    total_annual_burn: Decimal
    currency: str
    upcoming_7_days: List[RenewalAlertDTO]
    top_3_expensive_subscriptions: List[TopSpendingItemDTO]
    top_3_percentage_total: Decimal


# ============================================================================
# 3. 核心计算与业务纯函数 (Pure Functions)
# ============================================================================

# 默认汇率提供者类型：(from_curr, to_curr) -> Decimal
ExchangeRateProvider = Callable[[str, str], Decimal]

def default_mock_exchange_rate_provider(from_curr: str, to_curr: str) -> Decimal:
    """默认汇率提供者（若相同则 1:1，提供常用基准货币兑换示例）"""
    if from_curr == to_curr:
        return Decimal("1.0")
    # 常用基准 (基于 MYR 锚定示例)
    rates_to_myr = {
        "MYR": Decimal("1.00"),
        "USD": Decimal("4.45"),
        "SGD": Decimal("3.35"),
        "CNY": Decimal("0.62"),
        "EUR": Decimal("4.85"),
        "GBP": Decimal("5.65"),
    }
    f_rate = rates_to_myr.get(from_curr.upper())
    t_rate = rates_to_myr.get(to_curr.upper())
    if f_rate and t_rate:
        return (f_rate / t_rate).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)
    return Decimal("1.0")


def calculateAnnualAndMonthlyBurnRate(
    subscriptions: List[Subscription],
    targetCurrency: str = "MYR",
    exchangeRateProvider: Optional[ExchangeRateProvider] = None
) -> BurnRateSummary:
    """
    函数 1: calculateAnnualAndMonthlyBurnRate
    将所有处于 ACTIVE 状态的订阅换算为统一的月度平均开销与年度总开销。
    
    换算系数规则：
    - WEEKLY:      annual = cost * 52, monthly = annual / 12
    - MONTHLY:     annual = cost * 12, monthly = cost
    - QUARTERLY:   annual = cost * 4,  monthly = annual / 12
    - SEMI_ANNUAL: annual = cost * 2,  monthly = annual / 12
    - YEARLY:      annual = cost * 1,  monthly = cost / 12
    """
    if exchangeRateProvider is None:
        exchangeRateProvider = default_mock_exchange_rate_provider

    total_annual = Decimal("0.00")
    total_monthly = Decimal("0.00")
    active_count = 0

    cycle_annual_multipliers = {
        BillingCycle.WEEKLY: Decimal("52"),
        BillingCycle.MONTHLY: Decimal("12"),
        BillingCycle.QUARTERLY: Decimal("4"),
        BillingCycle.SEMI_ANNUAL: Decimal("2"),
        BillingCycle.YEARLY: Decimal("1"),
    }

    twelve = Decimal("12")

    for sub in subscriptions:
        if sub.status != SubscriptionStatus.ACTIVE:
            continue

        active_count += 1
        rate = exchangeRateProvider(sub.currency, targetCurrency)
        cost_in_target = (sub.cost * rate).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

        multiplier = cycle_annual_multipliers.get(sub.billing_cycle, Decimal("12"))
        annual_cost = (cost_in_target * multiplier).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

        if sub.billing_cycle == BillingCycle.MONTHLY:
            monthly_cost = cost_in_target
        else:
            monthly_cost = (annual_cost / twelve).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

        total_annual += annual_cost
        total_monthly += monthly_cost

    return BurnRateSummary(
        monthly_equivalent_burn=total_monthly.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
        annual_burn_rate=total_annual.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
        currency=targetCurrency,
        item_count=active_count
    )


def _add_months_with_anchor(base_date: date, months_to_add: int, anchor_day: int) -> date:
    """
    内部辅助工具：增加指定月数，并以 anchor_day（原始基准日）进行保护对齐。
    杜绝 1月31日 -> 2月28日 -> 3月28日 的时间衰退漂移现象。
    """
    year = base_date.year
    month = base_date.month + months_to_add
    while month > 12:
        month -= 12
        year += 1
    while month < 1:
        month += 12
        year -= 1

    max_day_in_target_month = calendar.monthrange(year, month)[1]
    target_day = min(anchor_day, max_day_in_target_month)
    return date(year, month, target_day)


def rollToNextBillingDate(
    currentBillingDate: date,
    billingCycle: BillingCycle,
    originalAnchorDay: Optional[int] = None
) -> date:
    """
    函数 2: rollToNextBillingDate
    根据计费周期计算下一个合法的扣款/续费日期。
    
    【重点处理月末与时间漂移边界】：
    - 若在 1 月 31 日开通按月订阅，2 月对齐到 2 月最后一天（28 或 29 日）。
    - 结合 originalAnchorDay（默认取 currentBillingDate.day），当 rolling 到 3 月时自动恢复对齐到 31 日！
    - 闰年 2 月 29 日按年订阅，平年对齐至 2 月 28 日，下个闰年仍能恢复 29 日。
    """
    anchor_day = originalAnchorDay if originalAnchorDay is not None else currentBillingDate.day

    if billingCycle == BillingCycle.WEEKLY:
        return currentBillingDate + timedelta(days=7)

    elif billingCycle == BillingCycle.MONTHLY:
        return _add_months_with_anchor(currentBillingDate, 1, anchor_day)

    elif billingCycle == BillingCycle.QUARTERLY:
        return _add_months_with_anchor(currentBillingDate, 3, anchor_day)

    elif billingCycle == BillingCycle.SEMI_ANNUAL:
        return _add_months_with_anchor(currentBillingDate, 6, anchor_day)

    elif billingCycle == BillingCycle.YEARLY:
        next_year = currentBillingDate.year + 1
        month = currentBillingDate.month
        max_day = calendar.monthrange(next_year, month)[1]
        target_day = min(anchor_day, max_day)
        return date(next_year, month, target_day)

    else:
        # 保底按月
        return _add_months_with_anchor(currentBillingDate, 1, anchor_day)


def getUpcomingRenewalsWithAlerts(
    subscriptions: List[Subscription],
    currentDate: date,
    lookaheadDays: int = 3,
    targetCurrency: str = "MYR",
    exchangeRateProvider: Optional[ExchangeRateProvider] = None
) -> List[RenewalAlertDTO]:
    """
    函数 3: getUpcomingRenewalsWithAlerts
    提取即将在未来 lookaheadDays 天内发生续费的所有有效订阅，生成丰富上下文的预警 DTO。
    
    预警评级规则：
    - CRITICAL:
      1. 标记为 target_to_cancel（打算取消但尚未取消，即将自动扣款）
      2. 试用期订阅即将转正（is_trial 且 trial_end_date 到期）
      3. 今天扣款 (days_left == 0) 或已过期未结算 (days_left < 0)
    - WARNING:
      1. days_left in (1, 2, 3) 且开启了自动续费
    - INFO:
      常规在窗口内但不具破坏性（如非自动续订或较长周期提醒）
    """
    if exchangeRateProvider is None:
        exchangeRateProvider = default_mock_exchange_rate_provider

    alerts: List[RenewalAlertDTO] = []

    for sub in subscriptions:
        if sub.status != SubscriptionStatus.ACTIVE:
            continue

        days_left = (sub.next_billing_date - currentDate).days

        # 仅筛选在 lookaheadDays 窗口内的项目（或者包含过去已逾期但尚未处理的项目）
        if days_left <= lookaheadDays:
            rate = exchangeRateProvider(sub.currency, targetCurrency)
            converted = (sub.cost * rate).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

            # 决定告警等级与提示文案
            if sub.target_to_cancel:
                alert_level = AlertLevel.CRITICAL
                alert_message = f"【紧急】已标记打算取消！还有 {days_left} 天将自动扣款 {sub.currency} {sub.cost}，请立即退订避免反向扣费！"
            elif sub.is_trial:
                alert_level = AlertLevel.CRITICAL
                alert_message = f"【试用到期】免费试用即将结束（倒计时 {days_left} 天），届时将自动扣款 {sub.currency} {sub.cost}！"
            elif days_left <= 0:
                alert_level = AlertLevel.CRITICAL
                alert_message = f"【今日扣费】预计今日自 {sub.payment_method_name or '默认账户'} 扣款 {sub.currency} {sub.cost}。"
            elif days_left <= sub.cancellation_reminder_days:
                alert_level = AlertLevel.WARNING
                alert_message = f"【续费预警】还剩 {days_left} 天续费，预计扣款 {sub.currency} {sub.cost}。"
            else:
                alert_level = AlertLevel.INFO
                alert_message = f"即将扣费，周期：{sub.billing_cycle.value}。"

            alerts.append(RenewalAlertDTO(
                subscription_id=sub.id,
                name=sub.name,
                category=sub.category,
                next_billing_date=sub.next_billing_date,
                days_left=days_left,
                cost=sub.cost,
                currency=sub.currency,
                converted_cost=converted,
                target_currency=targetCurrency,
                billing_cycle=sub.billing_cycle,
                payment_method_name=sub.payment_method_name,
                alert_level=alert_level,
                alert_message=alert_message,
                auto_renew=sub.auto_renew,
                is_trial=sub.is_trial,
                target_to_cancel=sub.target_to_cancel
            ))

    # 按到期紧急度排序（days_left 升序，负数和 0 最优先）
    alerts.sort(key=lambda x: (x.days_left, 0 if x.alert_level == AlertLevel.CRITICAL else 1))
    return alerts


# ============================================================================
# 4. 看板数据聚合服务 (Dashboard Aggregator)
# ============================================================================

def buildSubscriptionDashboardSummary(
    subscriptions: List[Subscription],
    currentDate: date,
    targetCurrency: str = "MYR",
    exchangeRateProvider: Optional[ExchangeRateProvider] = None
) -> SubscriptionDashboardSummaryDTO:
    """
    生成前台订阅大厅看板所需的 Summary DTO：
    - 订阅总数与各状态分布
    - 月度平均支出与年度总支出
    - 近 7 天待扣款列表
    - 最贵前三项订阅及占总年度支出的百分比 Top 3 %
    """
    if exchangeRateProvider is None:
        exchangeRateProvider = default_mock_exchange_rate_provider

    total_active = sum(1 for s in subscriptions if s.status == SubscriptionStatus.ACTIVE)
    total_paused = sum(1 for s in subscriptions if s.status == SubscriptionStatus.PAUSED)
    total_cancelled = sum(1 for s in subscriptions if s.status == SubscriptionStatus.CANCELLED)

    burn_summary = calculateAnnualAndMonthlyBurnRate(subscriptions, targetCurrency, exchangeRateProvider)

    # 近 7 天待扣款
    upcoming_7_days = getUpcomingRenewalsWithAlerts(
        subscriptions,
        currentDate=currentDate,
        lookaheadDays=7,
        targetCurrency=targetCurrency,
        exchangeRateProvider=exchangeRateProvider
    )

    # 计算最贵前三项 (基于折算后的年开销)
    cycle_multipliers = {
        BillingCycle.WEEKLY: Decimal("52"),
        BillingCycle.MONTHLY: Decimal("12"),
        BillingCycle.QUARTERLY: Decimal("4"),
        BillingCycle.SEMI_ANNUAL: Decimal("2"),
        BillingCycle.YEARLY: Decimal("1"),
    }
    twelve = Decimal("12")

    spending_items: List[TopSpendingItemDTO] = []
    total_annual = burn_summary.annual_burn_rate

    for sub in subscriptions:
        if sub.status != SubscriptionStatus.ACTIVE:
            continue
        rate = exchangeRateProvider(sub.currency, targetCurrency)
        cost_in_target = (sub.cost * rate).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        annual = (cost_in_target * cycle_multipliers[sub.billing_cycle]).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        monthly = cost_in_target if sub.billing_cycle == BillingCycle.MONTHLY else (annual / twelve).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

        pct = Decimal("0.00")
        if total_annual > 0:
            pct = ((annual / total_annual) * Decimal("100")).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)

        spending_items.append(TopSpendingItemDTO(
            subscription_id=sub.id,
            name=sub.name,
            category=sub.category,
            monthly_equivalent_cost=monthly,
            annual_cost=annual,
            currency=targetCurrency,
            percentage_of_total_annual=pct
        ))

    # 按年开销降序排列，取 Top 3
    spending_items.sort(key=lambda x: x.annual_cost, reverse=True)
    top_3 = spending_items[:3]
    top_3_pct_sum = sum((item.percentage_of_total_annual for item in top_3), Decimal("0.0")).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)

    return SubscriptionDashboardSummaryDTO(
        total_active_count=total_active,
        total_paused_count=total_paused,
        total_cancelled_count=total_cancelled,
        total_monthly_burn=burn_summary.monthly_equivalent_burn,
        total_annual_burn=burn_summary.annual_burn_rate,
        currency=targetCurrency,
        upcoming_7_days=upcoming_7_days,
        top_3_expensive_subscriptions=top_3,
        top_3_percentage_total=top_3_pct_sum
    )


# ============================================================================
# 5. 定时任务与预警排程系统设计 (Cron Worker System)
# ============================================================================

class NotificationChannel:
    """推送通知通道抽象基类"""
    def send_alert(self, user_id: str, alert: RenewalAlertDTO) -> bool:
        raise NotImplementedError


class MockNotificationChannel(NotificationChannel):
    """用于测试与日志记录的通知适配器"""
    def __init__(self):
        self.dispatched_alerts: List[Dict[str, Any]] = []

    def send_alert(self, user_id: str, alert: RenewalAlertDTO) -> bool:
        self.dispatched_alerts.append({
            "user_id": user_id,
            "subscription_id": alert.subscription_id,
            "level": alert.alert_level.value,
            "message": alert.alert_message,
            "timestamp": datetime.now().isoformat()
        })
        return True


def scheduleRenewalCronJob(
    db_connection: Any,
    currentDate: Optional[date] = None,
    notification_channels: Optional[List[NotificationChannel]] = None,
    auto_record_transaction: bool = False
) -> Dict[str, Any]:
    """
    函数 4: scheduleRenewalCronJob
    每日 08:00 AM 定时运行的后台 Worker 任务。
    
    执行步骤：
    1. 扫描所有状态为 ACTIVE 且开启预警的订阅记录。
    2. 计算触发条件：`next_billing_date - cancellation_reminder_days <= today <= next_billing_date`。
    3. 分发多渠道提醒（高危项目如试用期或标记取消优先高亮）。
    4. 当到达续费日 (next_billing_date == today) 且为自动续订时：
       - （可选）自动生成一条待对账的账本支出流水（如与 transactions 表对接）。
       - 调用 `rollToNextBillingDate` 自动将 `next_billing_date` 前滚至下一合法周期，保留 anchor_day。
    """
    if currentDate is None:
        currentDate = date.today()
    if notification_channels is None:
        notification_channels = [MockNotificationChannel()]

    report = {
        "execution_date": currentDate.isoformat(),
        "checked_count": 0,
        "alerts_dispatched": 0,
        "rolled_subscriptions": 0,
        "details": []
    }

    # 假定从 DB 读取（若为 SQLite / Turso / Mock DB，执行对应查询）
    cursor = db_connection.cursor()
    cursor.execute("""
        SELECT id, user_id, name, category, billing_cycle, cost, currency, 
               auto_renew, start_date, next_billing_date, payment_method_id,
               status, cancellation_reminder_days, is_trial, trial_end_date,
               target_to_cancel, anchor_day, note
        FROM subscriptions
        WHERE status = 'ACTIVE'
    """)
    rows = cursor.fetchall()

    for r in rows:
        report["checked_count"] += 1
        # 兼容 dict / sqlite3.Row / tuple
        if hasattr(r, "keys"):
            d = dict(r)
        else:
            cols = [
                "id", "user_id", "name", "category", "billing_cycle", "cost", "currency",
                "auto_renew", "start_date", "next_billing_date", "payment_method_id",
                "status", "cancellation_reminder_days", "is_trial", "trial_end_date",
                "target_to_cancel", "anchor_day", "note"
            ]
            d = dict(zip(cols, r))

        user_id = d.get("user_id")
        next_dt = datetime.strptime(d["next_billing_date"], "%Y-%m-%d").date() if isinstance(d["next_billing_date"], str) else d["next_billing_date"]
        start_dt = datetime.strptime(d["start_date"], "%Y-%m-%d").date() if isinstance(d["start_date"], str) else d["start_date"]
        reminder_days = int(d.get("cancellation_reminder_days") or 3)
        anchor_day = d.get("anchor_day") or (start_dt.day if start_dt else next_dt.day)

        sub = Subscription(
            id=d["id"],
            name=d["name"],
            category=d["category"],
            billing_cycle=BillingCycle(d["billing_cycle"]),
            cost=Decimal(str(d["cost"])),
            currency=d.get("currency", "MYR"),
            auto_renew=bool(d.get("auto_renew", 1)),
            start_date=start_dt,
            next_billing_date=next_dt,
            payment_method_id=d.get("payment_method_id"),
            status=SubscriptionStatus(d["status"]),
            cancellation_reminder_days=reminder_days,
            is_trial=bool(d.get("is_trial", 0)),
            target_to_cancel=bool(d.get("target_to_cancel", 0)),
            anchor_day=anchor_day,
            note=d.get("note")
        )

        days_left = (next_dt - currentDate).days

        # 判定是否触发预警 (在预警窗口内)
        if 0 <= days_left <= reminder_days:
            alerts = getUpcomingRenewalsWithAlerts([sub], currentDate=currentDate, lookaheadDays=reminder_days)
            if alerts:
                alert = alerts[0]
                for ch in notification_channels:
                    ch.send_alert(user_id, alert)
                report["alerts_dispatched"] += 1
                report["details"].append({
                    "sub_id": sub.id,
                    "action": "ALERT_SENT",
                    "level": alert.alert_level.value,
                    "days_left": days_left
                })

        # 判定是否到达计费日 (扣款并前滚日期)
        if days_left == 0 and sub.auto_renew and not sub.target_to_cancel:
            new_next_date = rollToNextBillingDate(sub.next_billing_date, sub.billing_cycle, sub.anchor_day)
            cursor.execute("""
                UPDATE subscriptions
                SET next_billing_date = ?, anchor_day = ?
                WHERE id = ?
            """, (new_next_date.isoformat(), sub.anchor_day, sub.id))
            report["rolled_subscriptions"] += 1
            report["details"].append({
                "sub_id": sub.id,
                "action": "ROLLED_TO_NEXT",
                "old_date": next_dt.isoformat(),
                "new_date": new_next_date.isoformat()
            })

    if hasattr(db_connection, "commit"):
        db_connection.commit()

    return report
