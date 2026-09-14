import sqlite3
from datetime import date

import pytest

from subscription_tracker import (
    BillingCycle,
    MockNotificationChannel,
    rollToNextBillingDate,
    scheduleRenewalCronJob,
)


def test_roll_monthly_advances_one_month_with_anchor_clamping():
    # Started on the 31st; Feb has no 31st so it clamps, but March recovers the anchor.
    jan_31 = date(2026, 1, 31)
    feb = rollToNextBillingDate(jan_31, BillingCycle.MONTHLY, originalAnchorDay=31)
    assert feb == date(2026, 2, 28)

    march = rollToNextBillingDate(feb, BillingCycle.MONTHLY, originalAnchorDay=31)
    assert march == date(2026, 3, 31)


def test_roll_weekly_adds_seven_days():
    start = date(2026, 6, 1)
    assert rollToNextBillingDate(start, BillingCycle.WEEKLY) == date(2026, 6, 8)


@pytest.fixture
def db_conn():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE subscriptions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            billing_cycle TEXT NOT NULL,
            cost REAL NOT NULL,
            currency TEXT NOT NULL DEFAULT 'MYR',
            auto_renew INTEGER NOT NULL DEFAULT 1,
            start_date TEXT NOT NULL,
            next_billing_date TEXT NOT NULL,
            payment_method_id INTEGER,
            status TEXT NOT NULL DEFAULT 'ACTIVE',
            cancellation_reminder_days INTEGER NOT NULL DEFAULT 3,
            is_trial INTEGER NOT NULL DEFAULT 0,
            trial_end_date TEXT,
            target_to_cancel INTEGER NOT NULL DEFAULT 0,
            anchor_day INTEGER,
            note TEXT
        );
        """
    )
    conn.commit()
    yield conn
    conn.close()


def _insert_subscription(conn, **overrides):
    row = {
        "user_id": "u1",
        "name": "Netflix",
        "category": "娱乐",
        "billing_cycle": "MONTHLY",
        "cost": 45.0,
        "currency": "MYR",
        "auto_renew": 1,
        "start_date": "2026-01-15",
        "next_billing_date": "2026-02-15",
        "payment_method_id": None,
        "status": "ACTIVE",
        "cancellation_reminder_days": 3,
        "is_trial": 0,
        "trial_end_date": None,
        "target_to_cancel": 0,
        "anchor_day": 15,
        "note": None,
    }
    row.update(overrides)
    conn.execute(
        """
        INSERT INTO subscriptions (
            user_id, name, category, billing_cycle, cost, currency, auto_renew,
            start_date, next_billing_date, payment_method_id, status,
            cancellation_reminder_days, is_trial, trial_end_date, target_to_cancel,
            anchor_day, note
        ) VALUES (:user_id, :name, :category, :billing_cycle, :cost, :currency, :auto_renew,
                  :start_date, :next_billing_date, :payment_method_id, :status,
                  :cancellation_reminder_days, :is_trial, :trial_end_date, :target_to_cancel,
                  :anchor_day, :note)
        """,
        row,
    )
    conn.commit()


def test_cron_rolls_forward_exactly_on_billing_day(db_conn):
    _insert_subscription(db_conn, next_billing_date="2026-02-15")

    report = scheduleRenewalCronJob(
        db_conn, currentDate=date(2026, 2, 15), notification_channels=[MockNotificationChannel()]
    )

    assert report["rolled_subscriptions"] == 1
    row = db_conn.execute("SELECT next_billing_date FROM subscriptions").fetchone()
    assert row["next_billing_date"] == "2026-03-15"


def test_cron_still_rolls_forward_after_a_missed_run(db_conn):
    """Regression test for the days_left == 0 bug: if the daily cron didn't run on the
    exact due date (deploy downtime, missed trigger), the subscription must still roll
    forward instead of being permanently stuck in the past."""
    _insert_subscription(db_conn, next_billing_date="2026-02-15")

    # Cron only gets to run 5 days late.
    report = scheduleRenewalCronJob(
        db_conn, currentDate=date(2026, 2, 20), notification_channels=[MockNotificationChannel()]
    )

    assert report["rolled_subscriptions"] == 1
    row = db_conn.execute("SELECT next_billing_date FROM subscriptions").fetchone()
    next_billing = date.fromisoformat(row["next_billing_date"])
    assert next_billing > date(2026, 2, 20)


def test_cron_catches_up_multiple_missed_cycles(db_conn):
    """If the cron has been down for a couple of months, next_billing_date must catch
    up to the future in one run rather than needing one run per missed cycle."""
    _insert_subscription(db_conn, next_billing_date="2026-01-15", billing_cycle="MONTHLY")

    report = scheduleRenewalCronJob(
        db_conn, currentDate=date(2026, 4, 1), notification_channels=[MockNotificationChannel()]
    )

    assert report["rolled_subscriptions"] == 1
    row = db_conn.execute("SELECT next_billing_date FROM subscriptions").fetchone()
    next_billing = date.fromisoformat(row["next_billing_date"])
    assert next_billing > date(2026, 4, 1)


def test_cron_does_not_roll_when_auto_renew_disabled(db_conn):
    _insert_subscription(db_conn, next_billing_date="2026-02-15", auto_renew=0)

    report = scheduleRenewalCronJob(
        db_conn, currentDate=date(2026, 2, 15), notification_channels=[MockNotificationChannel()]
    )

    assert report["rolled_subscriptions"] == 0
    row = db_conn.execute("SELECT next_billing_date FROM subscriptions").fetchone()
    assert row["next_billing_date"] == "2026-02-15"


def test_cron_dispatches_alert_within_reminder_window(db_conn):
    _insert_subscription(
        db_conn, next_billing_date="2026-02-16", cancellation_reminder_days=3
    )
    channel = MockNotificationChannel()

    report = scheduleRenewalCronJob(
        db_conn, currentDate=date(2026, 2, 15), notification_channels=[channel]
    )

    assert report["alerts_dispatched"] == 1
    assert len(channel.dispatched_alerts) == 1
