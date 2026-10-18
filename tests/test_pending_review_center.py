import json
import sqlite3
from datetime import datetime


def test_pending_transactions_summary_and_list(flask_app, logged_in_client, admin_user_id):
    db = sqlite3.connect(flask_app.DB_PATH)
    now_iso = datetime.now().isoformat()
    # Insert test pending transactions
    db.execute(
        """
        INSERT INTO pending_transactions
        (user_id, date, amount, type, category, note, channel, raw_text, status, review_reason, created_at)
        VALUES (?, '2026-10-18', 25.50, 'expense', '未分类', '午餐', 'auto_track', 'raw sms', 'on_hold', 'unclear_category', ?)
        """,
        (admin_user_id, now_iso)
    )
    db.execute(
        """
        INSERT INTO pending_transactions
        (user_id, date, amount, type, category, note, channel, raw_text, status, review_reason, created_at)
        VALUES (?, '2026-10-18', 50.00, 'expense', '交通', '打车', 'auto_track', 'raw sms 2', 'on_hold', 'suspected_duplicate', ?)
        """,
        (admin_user_id, now_iso)
    )
    db.commit()
    db.close()

    # 1. Test summary API
    resp = logged_in_client.get("/api/pending-transactions/summary")
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["ok"] is True
    assert data["total"] >= 2
    assert data["breakdown"]["unclear_category"] >= 1
    assert data["breakdown"]["suspected_duplicate"] >= 1

    # 2. Test list API
    list_resp = logged_in_client.get("/api/pending-transactions")
    assert list_resp.status_code == 200
    list_data = list_resp.get_json()
    assert list_data["ok"] is True
    items = list_data["items"]
    reasons = [it["review_reason"] for it in items]
    assert "unclear_category" in reasons
    assert "suspected_duplicate" in reasons


def test_pending_transaction_confirm_with_category(flask_app, logged_in_client, admin_user_id):
    db = sqlite3.connect(flask_app.DB_PATH)
    cur = db.cursor()
    now_iso = datetime.now().isoformat()
    cur.execute(
        """
        INSERT INTO pending_transactions
        (user_id, date, amount, type, category, note, channel, raw_text, status, review_reason, created_at)
        VALUES (?, '2026-10-18', 18.00, 'expense', '未分类', '咖啡', 'auto_track', 'raw', 'on_hold', 'unclear_category', ?)
        """,
        (admin_user_id, now_iso)
    )
    pending_id = cur.lastrowid
    db.commit()
    db.close()

    resp = logged_in_client.post(
        f"/api/pending-transactions/{pending_id}/confirm",
        data=json.dumps({"category": "餐饮", "note": "下午茶咖啡"}),
        content_type="application/json"
    )
    assert resp.status_code == 200
    res = resp.get_json()
    assert res["ok"] is True
    assert res["transaction_id"] > 0

    # Verify status changed in pending_transactions
    db = sqlite3.connect(flask_app.DB_PATH)
    row = db.execute("SELECT status FROM pending_transactions WHERE id = ?", (pending_id,)).fetchone()
    assert row[0] == "confirmed"

    # Verify transaction created
    tx = db.execute("SELECT amount, category, note FROM transactions WHERE id = ?", (res["transaction_id"],)).fetchone()
    assert tx[0] == 18.00
    assert tx[1] == "餐饮"
    assert tx[2] == "下午茶咖啡"
    db.close()

    # Idempotency test: confirming already confirmed transaction should be idempotent (prevent duplicate transaction creation)
    resp_repeat = logged_in_client.post(
        f"/api/pending-transactions/{pending_id}/confirm",
        data=json.dumps({"category": "餐饮"}),
        content_type="application/json"
    )
    assert resp_repeat.status_code == 200
    assert resp_repeat.get_json().get("already_confirmed") is True

    # Confirm transactions table still only has 1 transaction for this note
    db = sqlite3.connect(flask_app.DB_PATH)
    count = db.execute("SELECT COUNT(*) FROM transactions WHERE note = '下午茶咖啡'").fetchone()[0]
    assert count == 1
    db.close()


def test_pending_transaction_merge_duplicate(flask_app, logged_in_client, admin_user_id):
    db = sqlite3.connect(flask_app.DB_PATH)
    cur = db.cursor()
    now_iso = datetime.now().isoformat()
    # Create an existing confirmed transaction
    cur.execute(
        """
        INSERT INTO transactions (user_id, date, amount, type, category, note, created_at)
        VALUES (?, '2026-10-18', 88.00, 'expense', '餐饮', '火锅大餐', ?)
        """,
        (admin_user_id, now_iso)
    )
    target_tx_id = cur.lastrowid

    # Create a suspected duplicate pending transaction referencing target_tx_id
    cur.execute(
        """
        INSERT INTO pending_transactions
        (user_id, date, amount, type, category, note, channel, raw_text, status, review_reason, suspected_duplicate_id, created_at)
        VALUES (?, '2026-10-18', 88.00, 'expense', '餐饮', '火锅大餐重复短信', 'auto_track', 'sms text', 'on_hold', 'suspected_duplicate', ?, ?)
        """,
        (admin_user_id, target_tx_id, now_iso)
    )
    pending_id = cur.lastrowid
    db.commit()
    db.close()

    # Perform merge
    resp = logged_in_client.post(
        f"/api/pending-transactions/{pending_id}/merge",
        data=json.dumps({"target_transaction_id": target_tx_id, "merge_note": "短信重复发送"}),
        content_type="application/json"
    )
    assert resp.status_code == 200
    res = resp.get_json()
    assert res["ok"] is True

    # Verify pending transaction status is 'merged'
    db = sqlite3.connect(flask_app.DB_PATH)
    p_row = db.execute("SELECT status, merged_into_id FROM pending_transactions WHERE id = ?", (pending_id,)).fetchone()
    assert p_row[0] == "merged"
    assert p_row[1] == target_tx_id

    # Verify target transaction amount has NOT doubled (accounting integrity)
    tx = db.execute("SELECT amount, merged_info FROM transactions WHERE id = ?", (target_tx_id,)).fetchone()
    assert tx[0] == 88.00
    assert tx[1] is not None
    merged_data = json.loads(tx[1])
    assert len(merged_data) >= 1
    assert merged_data[0]["pending_id"] == pending_id
    db.close()


def test_pending_transaction_user_isolation(flask_app, logged_in_client):
    db = sqlite3.connect(flask_app.DB_PATH)
    cur = db.cursor()
    now_iso = datetime.now().isoformat()
    # Create another user
    cur.execute("INSERT OR IGNORE INTO users (id, username, password_hash, created_at) VALUES ('other-uid-123', 'other_user', 'hash', ?)", (now_iso,))
    other_uid = 'other-uid-123'

    cur.execute(
        """
        INSERT INTO pending_transactions
        (user_id, date, amount, type, category, note, channel, raw_text, status, review_reason, created_at)
        VALUES (?, '2026-10-18', 99.00, 'expense', '未分类', '私密账目', 'auto_track', 'sms', 'on_hold', 'unclear_category', ?)
        """,
        (other_uid, now_iso)
    )
    other_pending_id = cur.lastrowid
    db.commit()
    db.close()

    # Logged-in admin shouldn't be able to confirm or merge other user's pending transaction
    resp = logged_in_client.post(
        f"/api/pending-transactions/{other_pending_id}/confirm",
        data=json.dumps({"category": "餐饮"}),
        content_type="application/json"
    )
    assert resp.status_code in (403, 404) or resp.get_json().get("ok") is False
