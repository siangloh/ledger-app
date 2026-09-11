"""
Unit test suite for Subscription Management & Renewal Alerts (subscription_tracker.py)
"""
import unittest
import sqlite3
import os
import sys
from decimal import Decimal
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from subscription_tracker import (
    BillingCycle,
    SubscriptionStatus,
    AlertLevel,
    Subscription,
    calculateAnnualAndMonthlyBurnRate,
    rollToNextBillingDate,
    getUpcomingRenewalsWithAlerts,
    buildSubscriptionDashboardSummary,
    scheduleRenewalCronJob,
    MockNotificationChannel
)


class TestSubscriptionTracker(unittest.TestCase):

    def test_roll_to_next_billing_date_month_end_anchor_preservation(self):
        """测试核心边界：1月31日按月滚动，2月对齐到28/29，3月恢复到31日（杜绝时间衰退漂移）"""
        # 平年 2023 年测试
        jan_31_2023 = date(2023, 1, 31)
        feb_2023 = rollToNextBillingDate(jan_31_2023, BillingCycle.MONTHLY, originalAnchorDay=31)
        self.assertEqual(feb_2023, date(2023, 2, 28), "2023平年2月应优雅对齐到28日")

        mar_2023 = rollToNextBillingDate(feb_2023, BillingCycle.MONTHLY, originalAnchorDay=31)
        self.assertEqual(mar_2023, date(2023, 3, 31), "3月拥有31天，应按基准日优雅恢复到31日！")

        apr_2023 = rollToNextBillingDate(mar_2023, BillingCycle.MONTHLY, originalAnchorDay=31)
        self.assertEqual(apr_2023, date(2023, 4, 30), "4月只有30天，应优雅对齐到30日")

        may_2023 = rollToNextBillingDate(apr_2023, BillingCycle.MONTHLY, originalAnchorDay=31)
        self.assertEqual(may_2023, date(2023, 5, 31), "5月恢复到31日")

    def test_roll_to_next_billing_date_leap_year(self):
        """测试闰年 2024 年 2月29日边界"""
        jan_31_2024 = date(2024, 1, 31)
        feb_2024 = rollToNextBillingDate(jan_31_2024, BillingCycle.MONTHLY, originalAnchorDay=31)
        self.assertEqual(feb_2024, date(2024, 2, 29), "2024闰年2月应正确对齐到29日")

        # 闰年2月29日开通的年费订阅
        feb_29_2024 = date(2024, 2, 29)
        next_year = rollToNextBillingDate(feb_29_2024, BillingCycle.YEARLY, originalAnchorDay=29)
        self.assertEqual(next_year, date(2025, 2, 28), "平年2月对齐到28日")

    def test_roll_to_next_billing_date_other_cycles(self):
        """测试周、季、半年周期滚动"""
        base = date(2026, 1, 15)
        # 周
        weekly = rollToNextBillingDate(base, BillingCycle.WEEKLY)
        self.assertEqual(weekly, date(2026, 1, 22))

        # 季 (3个月)
        quarterly = rollToNextBillingDate(base, BillingCycle.QUARTERLY)
        self.assertEqual(quarterly, date(2026, 4, 15))

        # 半年 (6个月)
        semi = rollToNextBillingDate(base, BillingCycle.SEMI_ANNUAL)
        self.assertEqual(semi, date(2026, 7, 15))

        # 跨年 11 月加半年 -> 5月
        nov_base = date(2026, 11, 20)
        next_semi = rollToNextBillingDate(nov_base, BillingCycle.SEMI_ANNUAL)
        self.assertEqual(next_semi, date(2027, 5, 20))

    def test_calculate_annual_and_monthly_burn_rate_multi_currency(self):
        """测试跨币种多计费周期高精度烧钱率换算"""
        # 自定义固定汇率：1 USD = 4.50 MYR
        def mock_rates(f, t):
            if f == "USD" and t == "MYR":
                return Decimal("4.50")
            return Decimal("1.0")

        subs = [
            # 1. Netflix: 10.00 USD / MONTH -> 45.00 MYR/月, 540.00 MYR/年
            Subscription(
                id=1, name="Netflix", category="Streaming",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("10.00"), currency="USD"
            ),
            # 2. Gym: 1200.00 MYR / YEAR -> 100.00 MYR/月, 1200.00 MYR/年
            Subscription(
                id=2, name="Gym Fitness", category="Fitness",
                billing_cycle=BillingCycle.YEARLY,
                cost=Decimal("1200.00"), currency="MYR"
            ),
            # 3. Weekly Newsletter: 10.00 MYR / WEEK -> 520.00 MYR/年, 43.33 MYR/月
            Subscription(
                id=3, name="Financial Times", category="News",
                billing_cycle=BillingCycle.WEEKLY,
                cost=Decimal("10.00"), currency="MYR"
            ),
            # 4. PAUSED: 不应计入活跃开销
            Subscription(
                id=4, name="iCloud Paused", category="Cloud",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("99.00"), currency="MYR",
                status=SubscriptionStatus.PAUSED
            )
        ]

        result = calculateAnnualAndMonthlyBurnRate(subs, targetCurrency="MYR", exchangeRateProvider=mock_rates)
        self.assertEqual(result.item_count, 3)
        # Expected annual: 540.00 + 1200.00 + 520.00 = 2260.00 MYR
        self.assertEqual(result.annual_burn_rate, Decimal("2260.00"))
        # Expected monthly: 45.00 + 100.00 + 43.33 = 188.33 MYR
        self.assertEqual(result.monthly_equivalent_burn, Decimal("188.33"))

    def test_get_upcoming_renewals_and_urgency_alerts(self):
        """测试预警筛选、倒计时天数与紧急度判断"""
        today = date(2026, 9, 11)

        subs = [
            # 今天扣款
            Subscription(
                id=1, name="Spotify", category="Music",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("17.90"), currency="MYR",
                next_billing_date=date(2026, 9, 11)
            ),
            # 2天后扣款，但用户标记打算取消 -> CRITICAL
            Subscription(
                id=2, name="Adobe Creative Cloud", category="Design",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("230.00"), currency="MYR",
                next_billing_date=date(2026, 9, 13),
                target_to_cancel=True
            ),
            # 试用期即将结束 (3天后) -> CRITICAL
            Subscription(
                id=3, name="Audible Trial", category="Books",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("49.00"), currency="MYR",
                next_billing_date=date(2026, 9, 14),
                is_trial=True
            ),
            # 5天后扣款 (在 lookahead=7 窗口内) -> INFO/WARNING
            Subscription(
                id=4, name="ChatGPT Plus", category="AI",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("20.00"), currency="USD",
                next_billing_date=date(2026, 9, 16)
            ),
            # 20天后扣款 (不在 lookahead=7 窗口内)
            Subscription(
                id=5, name="iCloud 2TB", category="Cloud",
                billing_cycle=BillingCycle.MONTHLY,
                cost=Decimal("49.90"), currency="MYR",
                next_billing_date=date(2026, 10, 1)
            )
        ]

        alerts = getUpcomingRenewalsWithAlerts(subs, currentDate=today, lookaheadDays=7)
        self.assertEqual(len(alerts), 4, "应提取到未来7天内的4笔")

        # 验证排序：先按 days_left 升序
        self.assertEqual(alerts[0].name, "Spotify")
        self.assertEqual(alerts[0].days_left, 0)
        self.assertEqual(alerts[0].alert_level, AlertLevel.CRITICAL)

        # 检查 Adobe 打算取消的标记
        adobe_alert = next(a for a in alerts if a.name == "Adobe Creative Cloud")
        self.assertEqual(adobe_alert.alert_level, AlertLevel.CRITICAL)
        self.assertIn("打算取消", adobe_alert.alert_message)

    def test_build_subscription_dashboard_summary(self):
        """测试看板聚合 DTO 及 Top 3 最贵订阅开销占比"""
        today = date(2026, 9, 11)
        subs = [
            Subscription(id=1, name="Rent Server", category="IT", billing_cycle=BillingCycle.MONTHLY, cost=Decimal("500.00")),   # 年: 6000
            Subscription(id=2, name="Gym VIP", category="Fitness", billing_cycle=BillingCycle.YEARLY, cost=Decimal("2400.00")),   # 年: 2400
            Subscription(id=3, name="Broadband", category="Home", billing_cycle=BillingCycle.MONTHLY, cost=Decimal("150.00")),   # 年: 1800
            Subscription(id=4, name="Netflix", category="Media", billing_cycle=BillingCycle.MONTHLY, cost=Decimal("55.00")),     # 年: 660
            Subscription(id=5, name="Spotify", category="Media", billing_cycle=BillingCycle.MONTHLY, cost=Decimal("18.00")),     # 年: 216
        ]
        # Total annual = 6000 + 2400 + 1800 + 660 + 216 = 11076.00
        dto = buildSubscriptionDashboardSummary(subs, currentDate=today, targetCurrency="MYR")
        self.assertEqual(dto.total_active_count, 5)
        self.assertEqual(dto.total_annual_burn, Decimal("11076.00"))
        self.assertEqual(len(dto.top_3_expensive_subscriptions), 3)
        self.assertEqual(dto.top_3_expensive_subscriptions[0].name, "Rent Server")
        self.assertEqual(dto.top_3_expensive_subscriptions[1].name, "Gym VIP")
        self.assertEqual(dto.top_3_expensive_subscriptions[2].name, "Broadband")
        # Top 3 total pct check
        self.assertGreater(dto.top_3_percentage_total, Decimal("80.0"))

    def test_schedule_renewal_cron_job_workflow(self):
        """测试定时任务全流程：数据库扫描、多渠道推送分发、计费日前滚与数据库更新"""
        conn = sqlite3.connect(":memory:")
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute("""
        CREATE TABLE subscriptions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            billing_cycle TEXT NOT NULL,
            cost REAL NOT NULL,
            currency TEXT DEFAULT 'MYR',
            auto_renew INTEGER DEFAULT 1,
            start_date TEXT NOT NULL,
            next_billing_date TEXT NOT NULL,
            payment_method_id INTEGER,
            status TEXT DEFAULT 'ACTIVE',
            cancellation_reminder_days INTEGER DEFAULT 3,
            is_trial INTEGER DEFAULT 0,
            trial_end_date TEXT,
            target_to_cancel INTEGER DEFAULT 0,
            anchor_day INTEGER,
            note TEXT
        );
        """)

        today = date(2026, 9, 11)
        # 插入 3 条记录：
        # 1. 今天扣款 (2026-09-11)，自动续费 -> 应触发推送并前滚至 2026-10-11
        # 2. 2天后扣款 (2026-09-13)，在3天预警期内 -> 应触发推送，但不前滚
        # 3. 10天后扣款 (2026-09-21) -> 无动作
        cur.executemany("""
        INSERT INTO subscriptions (
            user_id, name, category, billing_cycle, cost, currency,
            auto_renew, start_date, next_billing_date, status, cancellation_reminder_days, anchor_day
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            ("user_1", "ChatGPT Plus", "AI", "MONTHLY", 90.0, "MYR", 1, "2026-08-11", "2026-09-11", "ACTIVE", 3, 11),
            ("user_1", "Netflix Premium", "Media", "MONTHLY", 55.0, "MYR", 1, "2026-08-13", "2026-09-13", "ACTIVE", 3, 13),
            ("user_1", "Gym", "Fitness", "YEARLY", 1200.0, "MYR", 1, "2025-09-21", "2026-09-21", "ACTIVE", 7, 21),
        ])
        conn.commit()

        notifier = MockNotificationChannel()
        report = scheduleRenewalCronJob(conn, currentDate=today, notification_channels=[notifier])

        self.assertEqual(report["checked_count"], 3)
        self.assertEqual(report["alerts_dispatched"], 2, "ChatGPT 与 Netflix 处于预警期应分发告警")
        self.assertEqual(report["rolled_subscriptions"], 1, "只有今日到期的 ChatGPT 应前滚日期")

        # 检验 DB 中 ChatGPT 的 next_billing_date 是否已更新为下个月
        row = cur.execute("SELECT next_billing_date FROM subscriptions WHERE name = 'ChatGPT Plus'").fetchone()
        self.assertEqual(row["next_billing_date"], "2026-10-11", "应已自动更新至下个月 10-11")


if __name__ == "__main__":
    unittest.main()
