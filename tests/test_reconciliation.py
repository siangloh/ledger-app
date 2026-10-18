"""
tests/test_reconciliation.py
账户对账与余额校准完整测试套件 (Account Reconciliation & Calibration Tests)
"""

import sqlite3
from contextlib import contextmanager
from datetime import datetime

from services.reconciliation_service import (
    calculate_account_system_balance,
    get_account_reconciliation_details,
    toggle_transaction_reconciled,
    batch_set_transactions_reconciled,
    reconcile_account
)

AJAX_HEADERS = {"X-Requested-With": "XMLHttpRequest"}


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def _create_account(flask_app, user_id, name="Maybank", acc_type="savings", credit_limit=0.0):
    with _test_db(flask_app) as db:
        cur = db.cursor()
        cur.execute(
            """
            INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at)
            VALUES (?, ?, ?, ?, 'MYR', 1, datetime('now'))
            """,
            (user_id, name, acc_type, credit_limit)
        )
        acc_id = cur.lastrowid
        db.commit()
        return acc_id


def _insert_account_tx(flask_app, user_id, account_id, amount=100.0, tx_type="income", is_rec=0):
    with _test_db(flask_app) as db:
        cur = db.cursor()
        now_iso = datetime.now().isoformat()
        cur.execute(
            """
            INSERT INTO transactions
            (user_id, date, type, category, amount, note, source, created_at, account_id, is_reconciled)
            VALUES (?, '2026-10-18', ?, '测试', ?, 'TxNote', 'manual', ?, ?, ?)
            """,
            (user_id, tx_type, amount, now_iso, account_id, is_rec)
        )
        tx_id = cur.lastrowid
        db.commit()
        return tx_id


def test_calculate_balance_and_details(flask_app, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Savings Test", acc_type="savings")
    t1 = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=1000.0, tx_type="income", is_rec=0)
    t2 = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=250.0, tx_type="expense", is_rec=0)

    with _test_db(flask_app) as db:
        calc = calculate_account_system_balance(acc_id, admin_user_id, db)
        assert calc is not None
        assert calc['income_sum'] == 1000.0
        assert calc['expense_sum'] == 250.0
        assert calc['system_balance'] == 750.0

        details = get_account_reconciliation_details(acc_id, admin_user_id, db)
        assert details['ok'] is True
        assert details['unreconciled_count'] == 2
        assert len(details['transactions']) == 2
        tx_ids = {t['id'] for t in details['transactions']}
        assert t1 in tx_ids and t2 in tx_ids


def test_toggle_and_batch_reconciled(flask_app, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="CIMB Checking", acc_type="current")
    t1 = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=50.0, tx_type="expense", is_rec=0)
    t2 = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=60.0, tx_type="expense", is_rec=0)

    with _test_db(flask_app) as db:
        # Toggle t1 to 1
        res1 = toggle_transaction_reconciled(t1, admin_user_id, db)
        assert res1['ok'] is True
        assert res1['is_reconciled'] == 1
        assert res1['reconciled_at'] is not None

        # Toggle t1 back to 0
        res2 = toggle_transaction_reconciled(t1, admin_user_id, db)
        assert res2['ok'] is True
        assert res2['is_reconciled'] == 0
        assert res2['reconciled_at'] is None

        # Batch set both to 1
        batch_res = batch_set_transactions_reconciled([t1, t2], admin_user_id, db, is_reconciled=True)
        assert batch_res['ok'] is True
        assert batch_res['count'] == 2

        # Check DB state
        rows = db.execute("SELECT id, is_reconciled FROM transactions WHERE id IN (?, ?)", (t1, t2)).fetchall()
        for r in rows:
            assert r['is_reconciled'] == 1


def test_reconcile_account_zero_discrepancy(flask_app, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Zero Diff Acc", acc_type="savings")
    _insert_account_tx(flask_app, admin_user_id, acc_id, amount=500.0, tx_type="income", is_rec=0)
    _insert_account_tx(flask_app, admin_user_id, acc_id, amount=100.0, tx_type="expense", is_rec=0)

    with _test_db(flask_app) as db:
        # System balance = 400.0, Statement balance = 400.0
        res = reconcile_account(acc_id, admin_user_id, statement_balance=400.0, auto_adjust=True, note="无差额对账", db=db)
        assert res['ok'] is True
        assert res['discrepancy'] == 0.0
        assert res['adjustment_tx_id'] is None
        assert res['final_balance'] == 400.0

        # Check account record updated
        acc = db.execute("SELECT last_reconciled_at, reconciled_balance FROM accounts WHERE id = ?", (acc_id,)).fetchone()
        assert acc['last_reconciled_at'] is not None
        assert acc['reconciled_balance'] == 400.0

        # Check audit record
        audit = db.execute("SELECT * FROM account_reconciliations WHERE account_id = ?", (acc_id,)).fetchone()
        assert audit is not None
        assert audit['statement_balance'] == 400.0
        assert audit['system_balance'] == 400.0
        assert audit['discrepancy'] == 0.0


def test_reconcile_account_with_auto_adjust_asset(flask_app, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Asset Adjust Acc", acc_type="savings")
    _insert_account_tx(flask_app, admin_user_id, acc_id, amount=500.0, tx_type="income", is_rec=0)
    # System balance = 500.0, statement says 550.0 (has 50 more -> discrepancy = +50.0)

    with _test_db(flask_app) as db:
        res = reconcile_account(acc_id, admin_user_id, statement_balance=550.0, auto_adjust=True, note="利息补录", db=db)
        assert res['ok'] is True
        assert res['discrepancy'] == 50.0
        assert res['adjustment_tx_id'] is not None
        assert res['final_balance'] == 550.0

        adj_tx = db.execute("SELECT * FROM transactions WHERE id = ?", (res['adjustment_tx_id'],)).fetchone()
        assert adj_tx['type'] == 'income'
        assert adj_tx['amount'] == 50.0
        assert adj_tx['category'] == '余额校准平账'
        assert 'reconciliation_adjustment' in adj_tx['tags']
        assert adj_tx['is_reconciled'] == 1


def test_reconcile_credit_card_auto_adjust(flask_app, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Maybank CC", acc_type="credit_card", credit_limit=5000.0)
    _insert_account_tx(flask_app, admin_user_id, acc_id, amount=1200.0, tx_type="expense", is_rec=0)
    _insert_account_tx(flask_app, admin_user_id, acc_id, amount=200.0, tx_type="income", is_rec=0)
    # System debt = 1200 - 200 = 1000.0. Statement says 1050.0 (owes 50 more -> expense)

    with _test_db(flask_app) as db:
        res = reconcile_account(acc_id, admin_user_id, statement_balance=1050.0, auto_adjust=True, note="年费差异", db=db)
        assert res['ok'] is True
        assert res['discrepancy'] == 50.0
        assert res['adjustment_tx_id'] is not None
        assert res['final_balance'] == 1050.0

        adj_tx = db.execute("SELECT * FROM transactions WHERE id = ?", (res['adjustment_tx_id'],)).fetchone()
        assert adj_tx['type'] == 'expense'
        assert adj_tx['amount'] == 50.0
        assert adj_tx['category'] == '余额校准平账'


def test_web_reconciliation_api_endpoints(flask_app, logged_in_client, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Web Test Acc", acc_type="savings")
    tx_id = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=300.0, tx_type="income")

    # 1. GET reconciliation details
    get_res = logged_in_client.get(f"/api/accounts/{acc_id}/reconciliation")
    assert get_res.status_code == 200
    data = get_res.get_json()
    assert data['ok'] is True
    assert data['account']['system_balance'] == 300.0

    # 2. Toggle transaction
    toggle_res = logged_in_client.post(f"/api/transactions/{tx_id}/toggle-reconciled", json={"is_reconciled": 1})
    assert toggle_res.status_code == 200
    assert toggle_res.get_json()['is_reconciled'] == 1

    # 3. Batch reconcile
    batch_res = logged_in_client.post(f"/api/accounts/{acc_id}/batch-reconcile-transactions", json={"tx_ids": [tx_id], "is_reconciled": True})
    assert batch_res.status_code == 200
    assert batch_res.get_json()['count'] == 1

    # 4. Perform reconcile via POST
    rec_res = logged_in_client.post(f"/api/accounts/{acc_id}/reconcile", json={
        "statement_balance": 300.0,
        "auto_adjust": True,
        "note": "Web对账测试"
    })
    assert rec_res.status_code == 200
    assert rec_res.get_json()['ok'] is True
    assert rec_res.get_json()['discrepancy'] == 0.0


def test_native_reconciliation_api_endpoints(flask_app, logged_in_client, admin_user_id):
    acc_id = _create_account(flask_app, admin_user_id, name="Native Test Acc", acc_type="savings")
    tx_id = _insert_account_tx(flask_app, admin_user_id, acc_id, amount=800.0, tx_type="income")

    headers = {"X-API-KEY": "test-auto-track-key"}

    # 1. GET native accounts list contains reconciliation metadata
    list_res = logged_in_client.get("/api/native/accounts", headers=headers)
    assert list_res.status_code == 200
    acc_list_data = list_res.get_json()
    assert acc_list_data['ok'] is True
    target_acc = next((a for a in acc_list_data['accounts'] if a['id'] == acc_id), None)
    assert target_acc is not None
    assert 'unreconciled_count' in target_acc
    assert target_acc['unreconciled_count'] == 1

    # 2. GET native account reconciliation details
    det_res = logged_in_client.get(f"/api/native/accounts/{acc_id}/reconciliation", headers=headers)
    assert det_res.status_code == 200
    assert det_res.get_json()['ok'] is True

    # 3. Toggle via native endpoint
    tog_res = logged_in_client.post(f"/api/native/transactions/{tx_id}/toggle-reconciled", headers=headers, json={"is_reconciled": 1})
    assert tog_res.status_code == 200
    assert tog_res.get_json()['is_reconciled'] == 1

    # 4. Reconcile via native endpoint
    post_res = logged_in_client.post(f"/api/native/accounts/{acc_id}/reconcile", headers=headers, json={
        "statement_balance": 850.0,
        "auto_adjust": True,
        "note": "Native校准"
    })
    assert post_res.status_code == 200
    assert post_res.get_json()['ok'] is True
    assert post_res.get_json()['final_balance'] == 850.0
