import sqlite3

import pytest

from services.cashflow_analysis_service import (
    get_monthly_cashflow_summary, init_cashflow_schema, update_bill_payment, update_bill,
    confirm_scheduled_transaction, record_source_payment,
)


@pytest.fixture
def db():
    conn = sqlite3.connect(':memory:')
    conn.row_factory = sqlite3.Row
    conn.executescript('''
        CREATE TABLE user_settings (user_id TEXT, default_currency TEXT);
        CREATE TABLE system_metadata (key TEXT PRIMARY KEY, val TEXT, updated_at TEXT);
        CREATE TABLE accounts (id INTEGER PRIMARY KEY, user_id TEXT, name TEXT, currency TEXT);
        CREATE TABLE transactions (id INTEGER PRIMARY KEY, user_id TEXT, date TEXT, type TEXT,
            category TEXT, amount REAL, note TEXT, account_id INTEGER, deleted_at TEXT, from_savings INTEGER DEFAULT 0, source TEXT DEFAULT 'manual');
        CREATE TABLE recurring_rules (id INTEGER PRIMARY KEY, user_id TEXT, type TEXT, is_active INTEGER,
            created_at TEXT, day_of_month INTEGER, amount REAL, category TEXT, note TEXT, last_generated_month TEXT);
        CREATE TABLE subscriptions (id INTEGER PRIMARY KEY, user_id TEXT, name TEXT, status TEXT,
            start_date TEXT, next_billing_date TEXT, billing_cycle TEXT, anchor_day INTEGER,
            auto_renew INTEGER, cost REAL, currency TEXT, is_trial INTEGER DEFAULT 0, trial_end_date TEXT);
        CREATE TABLE installments (id INTEGER PRIMARY KEY, user_id TEXT, status TEXT, first_due_date TEXT,
            tenure_months INTEGER, monthly_amount REAL, account_id INTEGER, title TEXT, paid_periods INTEGER, last_synced_month TEXT);
        CREATE TABLE loans (id INTEGER PRIMARY KEY, user_id TEXT, status TEXT, start_date TEXT, due_day INTEGER,
            tenure_months INTEGER, monthly_payment REAL, debit_account_id INTEGER, title TEXT);
        INSERT INTO user_settings VALUES ('u', 'MYR');
        INSERT INTO accounts VALUES (1, 'u', 'Bank', 'MYR'), (2, 'u', 'USD wallet', 'USD'), (3, 'other', 'Private', 'USD');
        INSERT INTO transactions (id,user_id,date,type,category,amount,note,account_id) VALUES
            (1,'u','2026-10-01','income','Salary',5000,'Salary',1),
            (2,'u','2026-10-02','expense','Rent',400,'Rent partial',1),
            (3,'u','2026-10-03','transfer','内部划转',1000,'Transfer',1),
            (4,'other','2026-10-02','expense','Rent',1000,'Private',3),
            (5,'u','2026-10-05','expense','Food',20,'Food',2),
            (6,'u','2026-10-06','expense','Rent',600,'Rent remainder',1);
        UPDATE transactions SET from_savings=1 WHERE id=2;
        INSERT INTO recurring_rules VALUES (1,'u','expense',1,'2026-09-01',10,1000,'Rent','Rent','2026-10');
    ''')
    init_cashflow_schema(conn)
    yield conn
    conn.close()


def report(db, **kwargs):
    return get_monthly_cashflow_summary('u', '2026-10', db, **kwargs)


def test_totals_include_savings_and_exclude_internal_and_foreign(db):
    result = report(db)
    assert result['income'] == 5000
    assert result['expense'] == 1000
    assert result['reserved'] == 1000  # last_generated_month is NOT payment proof
    assert result['available'] == 3000
    assert result['expense_percent'] == 20
    assert report(db, currency='USD')['expense'] == 20
    assert sum(g['amount'] for g in result['expense_platforms']) == result['expense']


def test_partial_retry_full_deleted_and_refund(db):
    key = 'recurring:1:2026-10-10'
    update_bill_payment('u', '2026-10', key, 2, db)
    update_bill_payment('u', '2026-10', key, 2, db)
    assert report(db)['reserved'] == 600
    update_bill_payment('u', '2026-10', key, 6, db)
    assert report(db)['reserved'] == 0
    db.execute("UPDATE transactions SET deleted_at='2026-10-07' WHERE id=6")
    assert report(db)['reserved'] == 600
    db.execute("INSERT INTO transactions (id,user_id,date,type,category,amount,note,account_id) VALUES (7,'u','2026-10-08','income','Refund',100,'Refund',1)")
    update_bill_payment('u', '2026-10', key, 7, db, refund=True)
    result = report(db)
    assert result['reserved'] == 700
    assert result['income'] == 5000
    assert result['expense'] == 300
    update_bill_payment('u', '2026-10', key, 7, db, unlink=True)
    assert report(db)['reserved'] == 600


def test_cross_user_currency_and_double_claim_rejected(db):
    key = 'recurring:1:2026-10-10'
    for tx_id in (4, 5, 3):
        with pytest.raises(ValueError):
            update_bill_payment('u', '2026-10', key, tx_id, db)
    db.execute("INSERT INTO recurring_rules VALUES (2,'u','expense',1,'2026-09-01',10,1000,'Rent','Other',NULL)")
    update_bill_payment('u', '2026-10', key, 2, db)
    with pytest.raises(ValueError, match='already linked'):
        update_bill_payment('u', '2026-10', 'recurring:2:2026-10-10', 2, db)


def test_subscription_renewal_history_and_weekly_not_monthly_average(db):
    db.execute("INSERT INTO subscriptions VALUES (1,'u','Yearly','ACTIVE','2025-10-15','2027-10-15','YEARLY',15,1,120,'MYR',0,NULL)")
    db.execute("INSERT INTO subscriptions VALUES (2,'u','Weekly','ACTIVE','2026-10-01','2026-11-05','WEEKLY',1,1,10,'MYR',0,NULL)")
    result = report(db)
    assert len([b for b in result['bills'] if b['source_id'] == 2 and b['source_kind'] == 'subscription']) == 5
    assert result['planned'] == 1170
    update_bill_payment('u', '2026-10', 'subscription:1:2026-10-15', 2, db)
    db.execute("UPDATE subscriptions SET next_billing_date='2028-10-15', cost=240 WHERE id=1")
    assert next(b for b in report(db)['bills'] if b['bill_key'] == 'subscription:1:2026-10-15')['amount'] == 120
    assert not any(b['source_id'] == 1 and b['source_kind'] == 'subscription'
                   for b in get_monthly_cashflow_summary('u', '2026-11', db)['bills'])


def test_zero_income_deficit_and_prior_overdue_separate(db):
    db.execute("DELETE FROM transactions WHERE type='income'")
    result = report(db)
    assert result['expense_percent'] is None
    assert result['available'] == -2000
    assert result['deficit'] == 2000
    update_bill_payment('u', '2026-10', 'recurring:1:2026-10-10', 2, db)
    november = get_monthly_cashflow_summary('u', '2026-11', db)
    assert november['prior_overdue_amount'] == 600
    assert november['reserved'] == 1000


def test_explicit_duplicate_and_cancellation(db):
    db.execute("INSERT INTO recurring_rules VALUES (2,'u','expense',1,'2026-09-01',10,1000,'Rent','Duplicate',NULL)")
    update_bill('u', '2026-10', 'recurring:2:2026-10-10', db, duplicate_of='recurring:1:2026-10-10')
    assert report(db)['reserved'] == 1000
    update_bill('u', '2026-10', 'recurring:1:2026-10-10', db, cancelled=True)
    assert report(db)['reserved'] == 0


@pytest.mark.parametrize('month', ['2026-13', 'bad', '2026-1', '1999-12'])
def test_invalid_month(db, month):
    with pytest.raises(ValueError):
        get_monthly_cashflow_summary('u', month, db)


def test_api_authorization_and_bad_month(client, logged_in_client):
    response = logged_in_client.get('/api/analytics/cashflow?month=bad')
    assert response.status_code == 400
    with client.session_transaction() as session:
        session.clear()
    assert client.get('/api/analytics/cashflow?month=2026-10').status_code in (302, 401)


def test_generated_rows_are_not_payment_or_actual_evidence(db):
    db.execute("UPDATE transactions SET source='recurring' WHERE id=2")
    assert report(db)['expense'] == 600
    assert report(db)['reserved'] == 1000
    assert len(report(db)['scheduled_transactions']) == 1
    update_bill_payment('u', '2026-10', 'recurring:1:2026-10-10', 2, db)
    assert report(db)['expense'] == 1000
    assert report(db)['reserved'] == 600
    update_bill_payment('u', '2026-10', 'recurring:1:2026-10-10', 2, db, unlink=True)
    assert report(db)['expense'] == 600
    confirm_scheduled_transaction('u', 2, db)
    assert report(db)['expense'] == 1000
    confirm_scheduled_transaction('u', 2, db, undo=True)
    assert report(db)['expense'] == 600


def test_explicit_match_bridge_partial_retry_and_full(db):
    db.execute("INSERT INTO subscriptions VALUES (1,'u','Monthly','ACTIVE','2026-01-10','2026-10-10','MONTHLY',10,1,1000,'MYR',0,NULL)")
    source = db.execute('SELECT * FROM subscriptions WHERE id=1').fetchone()
    assert record_source_payment('u', 'subscription', source, '2026-10-10', 2, db) == 'partial'
    assert record_source_payment('u', 'subscription', source, '2026-10-10', 2, db) == 'retry'
    assert record_source_payment('u', 'subscription', source, '2026-10-10', 6, db) == 'paid'
    bill = next(b for b in report(db)['bills'] if b['bill_key'] == 'subscription:1:2026-10-10')
    assert bill['paid'] == 1000
    assert bill['pending'] == 0
