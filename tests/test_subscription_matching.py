"""
tests/test_subscription_matching.py
订阅/分期智能匹配服务层、Web 接口、待确认交易流转及 Native 原生伴侣端 API 全面测试套件
"""

import sqlite3
from contextlib import contextmanager
from datetime import date

from services.subscription_matching_service import (
    _normalize_match_str,
    match_subscription,
    match_installment,
    match_transaction,
    apply_subscription_match,
    apply_installment_match,
    scan_unmatched_transactions
)


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def test_match_transaction_helper(flask_app, admin_user_id):
    """测试 match_transaction 统一分发助手函数"""
    with _test_db(flask_app) as db:
        res = match_transaction(
            user_id=admin_user_id,
            amount=10.0,
            merchant="Test",
            note="",
            raw_text="Test tx",
            tx_date="2026-10-18",
            db=db
        )
        assert isinstance(res, dict)
        assert "subscription" in res
        assert "installment" in res
        assert "matched" in res


def test_normalize_match_str():
    """测试文本规范化工具函数"""
    assert _normalize_match_str("NETFLIX.COM (SG) 1234") == "netflix com sg 1234"
    assert _normalize_match_str("  Apple.com / Bill  ") == "apple com bill"
    assert _normalize_match_str("Spotify@Music! #999") == "spotify music 999"


def test_match_subscription_and_target_to_cancel(flask_app, admin_user_id):
    """测试订阅计划匹配及打算退订标记预警"""
    with _test_db(flask_app) as db:
        # 清理旧测试数据
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 插入活跃订阅：Spotify (54.90 MYR, 每月 18 号扣款)
        cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day, note
            ) VALUES (?, ?, ?, ?, ?, ?, 1, '2026-01-18', '2026-10-18', 'ACTIVE', 3, 0, 1, 18, '家庭版')
            """,
            (str(admin_user_id), "Spotify Premium", "流媒体", "MONTHLY", 54.90, "MYR")
        )
        sub_id = cur.lastrowid
        db.commit()

        # 1. 完全吻合的交易
        match1 = match_subscription(
            user_id=admin_user_id,
            amount=54.90,
            merchant="SPOTIFY COM",
            note="",
            raw_text="Spotify monthly payment",
            tx_date="2026-10-18",
            db=db
        )
        assert match1 is not None
        assert match1["id"] == sub_id
        assert match1["name"] == "Spotify Premium"
        assert match1["target_to_cancel"] is True  # 打算退订标记命中
        assert match1["score"] >= 80

        # 2. 金额不符的交易
        match_wrong_amt = match_subscription(
            user_id=admin_user_id,
            amount=19.90,
            merchant="SPOTIFY COM",
            note="",
            raw_text="Spotify single track",
            tx_date="2026-10-18",
            db=db
        )
        assert match_wrong_amt is None

        # 3. 商户不符的交易
        match_wrong_merchant = match_subscription(
            user_id=admin_user_id,
            amount=54.90,
            merchant="KFC Restaurant",
            note="",
            raw_text="Fried chicken",
            tx_date="2026-10-18",
            db=db
        )
        assert match_wrong_merchant is None


def test_apply_subscription_match(flask_app, admin_user_id):
    """测试应用匹配：自动顺延下期扣款日并保持锚定日"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day, note
            ) VALUES (?, ?, ?, ?, ?, ?, 1, '2026-01-31', '2026-01-31', 'ACTIVE', 3, 0, 0, 31, '')
            """,
            (str(admin_user_id), "iCloud 2TB", "云存储", "MONTHLY", 49.90, "MYR")
        )
        sub_id = cur.lastrowid
        db.commit()

        # 顺延到 2 月（平年或闰年 28/29）
        res = apply_subscription_match(admin_user_id, sub_id, db=db)
        assert res["ok"] is True
        assert res["new_next_billing_date"].startswith("2026-02-")

        # 再次顺延到 3 月，应精准恢复 31 日锚定
        res2 = apply_subscription_match(admin_user_id, sub_id, db=db)
        assert res2["ok"] is True
        assert res2["new_next_billing_date"] == "2026-03-31"

        # 检查数据库持久化
        row = db.execute("SELECT next_billing_date, anchor_day FROM subscriptions WHERE id = ?", (sub_id,)).fetchone()
        assert row["next_billing_date"] == "2026-03-31"
        assert row["anchor_day"] == 31


def test_match_and_apply_installment(flask_app, admin_user_id):
    """测试分期匹配与期数累加递增逻辑"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM installments WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        cur = db.execute(
            """
            INSERT INTO installments (
                user_id, account_id, title, total_amount, tenure_months,
                paid_periods, monthly_amount, first_due_date, status, created_at
            ) VALUES (?, NULL, 'iPhone 16 Pro 24期免息', 4800.0, 24, 23, 200.0, '2024-11-01', 'active', '2024-11-01')
            """,
            (str(admin_user_id),)
        )
        inst_id = cur.lastrowid
        db.commit()

        # 1. 匹配分期
        match = match_installment(
            user_id=admin_user_id,
            amount=200.0,
            merchant="Maybank EzyPay",
            note="iPhone 16 Pro installment",
            raw_text="Maybank installment deducted 200.00 for iPhone 16 Pro",
            tx_date="2026-10-15",
            db=db
        )
        assert match is not None
        assert match["id"] == inst_id
        assert match["title"] == "iPhone 16 Pro 24期免息"
        assert match["paid_periods"] == 23
        assert match["tenure_months"] == 24

        # 2. 应用匹配：23/24 -> 24/24 并自动转为 completed
        res = apply_installment_match(admin_user_id, inst_id, db=db)
        assert res["ok"] is True
        assert res["new_paid_periods"] == 24
        assert res["status"] == "completed"

        # 检查数据库更新
        row = db.execute("SELECT paid_periods, status FROM installments WHERE id = ?", (inst_id,)).fetchone()
        assert row["paid_periods"] == 24
        assert row["status"] == "completed"


def test_scan_unmatched_transactions(flask_app, admin_user_id):
    """测试批量流水与活跃计划扫描对齐"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM transactions WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 插入订阅：Netflix
        sub_cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day
            ) VALUES (?, 'Netflix 4K', '娱乐', 'MONTHLY', 55.00, 'MYR', 1, '2026-01-10', '2026-10-10', 'ACTIVE', 3, 0, 0, 10)
            """,
            (str(admin_user_id),)
        )
        sub_id = sub_cur.lastrowid

        # 插入交易流水
        today_str = date.today().strftime("%Y-%m-%d")
        db.execute(
            """
            INSERT INTO transactions (
                user_id, type, amount, category, date, note, created_at
            ) VALUES (?, '支出', 55.00, '娱乐', ?, 'NETFLIX.COM auto billing', ?)
            """,
            (str(admin_user_id), today_str, today_str)
        )
        db.commit()

        # 扫描未匹配流水
        matches = scan_unmatched_transactions(admin_user_id, db=db, limit=10)
        assert len(matches) >= 1
        found = any(m.get("matched_subscription") and m["matched_subscription"]["id"] == sub_id for m in matches)
        assert found is True


def test_web_api_subscriptions_matching(logged_in_client, flask_app, admin_user_id):
    """测试 Web 端订阅/分期匹配与顺延 API"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM installments WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        sub_cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day
            ) VALUES (?, 'GitHub Copilot', '生产力', 'MONTHLY', 10.00, 'USD', 1, '2026-01-05', '2026-10-05', 'ACTIVE', 3, 0, 0, 5)
            """,
            (str(admin_user_id),)
        )
        sub_id = sub_cur.lastrowid

        inst_cur = db.execute(
            """
            INSERT INTO installments (
                user_id, account_id, title, total_amount, tenure_months,
                paid_periods, monthly_amount, first_due_date, status, created_at
            ) VALUES (?, NULL, 'iPad Pro 12期', 1200.0, 12, 1, 100.0, '2026-01-01', 'active', '2026-01-01')
            """,
            (str(admin_user_id),)
        )
        inst_id = inst_cur.lastrowid
        db.commit()

    # 1. GET /api/subscriptions/matches
    res_get = logged_in_client.get("/api/subscriptions/matches")
    assert res_get.status_code == 200
    data = res_get.get_json()
    assert data["ok"] is True
    assert "matches" in data

    # 2. POST /api/subscriptions/<sub_id>/apply-match
    res_apply_sub = logged_in_client.post(f"/api/subscriptions/{sub_id}/apply-match", json={})
    assert res_apply_sub.status_code == 200
    sub_data = res_apply_sub.get_json()
    assert sub_data["ok"] is True
    assert "new_next_billing_date" in sub_data

    # 3. POST /api/installments/<inst_id>/apply-match
    res_apply_inst = logged_in_client.post(f"/api/installments/{inst_id}/apply-match", json={})
    assert res_apply_inst.status_code == 200
    inst_data = res_apply_inst.get_json()
    assert inst_data["ok"] is True
    assert inst_data["new_paid_periods"] == 2


def test_pending_transaction_match_and_auto_confirm(logged_in_client, flask_app, admin_user_id):
    """测试待确认中心智能命中与确认时自动滚动续期"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM pending_transactions WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 插入订阅
        sub_cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day
            ) VALUES (?, 'ChatGPT Plus', '生产力', 'MONTHLY', 20.00, 'USD', 1, '2026-01-15', '2026-10-15', 'ACTIVE', 3, 0, 0, 15)
            """,
            (str(admin_user_id),)
        )
        sub_id = sub_cur.lastrowid

        # 插入待确认交易
        today_str = date.today().strftime("%Y-%m-%d")
        pt_cur = db.execute(
            """
            INSERT INTO pending_transactions (
                user_id, date, type, amount, category, merchant, note, raw_text, status, created_at
            ) VALUES (?, ?, 'expense', 20.00, '生产力', 'OpenAI ChatGPT', '', 'OpenAI ChatGPT fee 20.00 USD', 'on_hold', ?)
            """,
            (str(admin_user_id), today_str, today_str)
        )
        pending_id = pt_cur.lastrowid
        db.commit()

    # 1. 查询待确认列表，应包含 matched_subscription
    res_pending = logged_in_client.get("/api/pending-transactions")
    assert res_pending.status_code == 200
    p_data = res_pending.get_json()
    items = p_data.get("items") or p_data.get("data") or []
    target_item = next((it for it in items if it["id"] == pending_id), None)
    assert target_item is not None
    assert target_item.get("matched_subscription") is not None
    assert target_item["matched_subscription"]["id"] == sub_id

    # 2. 确认待确认交易，同时传递 sub_id
    res_confirm = logged_in_client.post(
        f"/api/pending-transactions/{pending_id}/confirm",
        json={"sub_id": sub_id}
    )
    assert res_confirm.status_code == 200
    confirm_data = res_confirm.get_json()
    assert confirm_data["ok"] is True

    # 3. 验证订阅扣款日已顺延
    with _test_db(flask_app) as db:
        sub_row = db.execute("SELECT next_billing_date FROM subscriptions WHERE id = ?", (sub_id,)).fetchone()
        assert sub_row["next_billing_date"] == "2026-11-15"


def test_native_api_subscription_matching(client, flask_app, admin_user_id):
    """测试 Native 原生伴侣端智能匹配及顺延接口"""
    headers = {"X-API-KEY": "test-auto-track-key"}

    with _test_db(flask_app) as db:
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM installments WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        sub_cur = db.execute(
            """
            INSERT INTO subscriptions (
                user_id, name, category, billing_cycle, cost, currency,
                auto_renew, start_date, next_billing_date, status,
                cancellation_reminder_days, is_trial, target_to_cancel, anchor_day
            ) VALUES (?, 'YouTube Premium', '娱乐', 'MONTHLY', 17.90, 'MYR', 1, '2026-01-22', '2026-10-22', 'ACTIVE', 3, 0, 0, 22)
            """,
            (str(admin_user_id),)
        )
        sub_id = sub_cur.lastrowid

        inst_cur = db.execute(
            """
            INSERT INTO installments (
                user_id, account_id, title, total_amount, tenure_months,
                paid_periods, monthly_amount, first_due_date, status, created_at
            ) VALUES (?, NULL, 'Dyson V12 12期', 2400.0, 12, 5, 200.0, '2026-01-01', 'active', '2026-01-01')
            """,
            (str(admin_user_id),)
        )
        inst_id = inst_cur.lastrowid
        db.commit()

    # 1. GET /api/native/subscriptions/matches
    res_native_get = client.get("/api/native/subscriptions/matches", headers=headers)
    assert res_native_get.status_code == 200
    n_data = res_native_get.get_json()
    assert n_data["ok"] is True
    assert "matches" in n_data

    # 2. POST /api/native/subscriptions/<sub_id>/apply-match
    res_sub = client.post(f"/api/native/subscriptions/{sub_id}/apply-match", headers=headers, json={})
    assert res_sub.status_code == 200
    res_sub_json = res_sub.get_json()
    assert res_sub_json["ok"] is True
    assert res_sub_json["new_next_billing_date"] == "2026-11-22"

    # 3. POST /api/native/installments/<inst_id>/apply-match
    res_inst = client.post(f"/api/native/installments/{inst_id}/apply-match", headers=headers, json={})
    assert res_inst.status_code == 200
    res_inst_json = res_inst.get_json()
    assert res_inst_json["ok"] is True
    assert res_inst_json["new_paid_periods"] == 6
