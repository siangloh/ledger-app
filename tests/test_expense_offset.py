from datetime import datetime, date
from app import get_db, DEFAULT_AUTO_TRACK_KEY


def test_manual_expense_offset(logged_in_client, flask_app):
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().isoformat()
        today = date.today().isoformat()

        # 1. 创建一笔餐饮支出 RM 60
        cur1 = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 60.00, '海底捞聚餐', 'manual', ?)",
            (user_id, today, now)
        )
        expense_id = cur1.lastrowid

        # 2. 创建一笔朋友还款收入 RM 25
        cur2 = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'income', 'side', '其他', 25.00, '张三还钱', 'auto_track', ?)",
            (user_id, today, now)
        )
        income_id = cur2.lastrowid
        db.commit()

    # 获取近期支出
    res = logged_in_client.get('/api/transactions/recent-expenses')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert any(exp['id'] == expense_id for exp in data['expenses'])

    # 执行手动冲抵
    offset_res = logged_in_client.post(
        f'/api/transactions/{income_id}/offset',
        json={'target_expense_id': expense_id}
    )
    assert offset_res.status_code == 200
    offset_data = offset_res.get_json()
    assert offset_data['ok'] is True
    assert offset_data['new_amount'] == 35.00

    # 检查数据库：原支出金额已减为 35，原收入记录已被清理
    with flask_app.app.app_context():
        db = get_db()
        updated_exp = db.execute("SELECT * FROM transactions WHERE id = ?", (expense_id,)).fetchone()
        assert updated_exp['amount'] == 35.00
        assert "收到还款冲抵" in updated_exp['note']

        deleted_inc = db.execute("SELECT * FROM transactions WHERE id = ?", (income_id,)).fetchone()
        assert deleted_inc is None


def test_auto_track_automatic_repayment_offset(flask_app, client):
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().isoformat()
        today = date.today().isoformat()

        # 1. 模拟不久前刷卡付款 RM 80
        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 80.00, 'Starbucks Coffee', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    # 2. 收到朋友还款通知（DuitNow / Transfer）
    notif_text = "DuitNow Transfer: You have received RM 30.00 from Ali on 10 Sep 2026. Ref: DN991823."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 200
    res_data = resp.get_json()
    assert res_data['ok'] is True
    assert res_data['verdict'] == 'offset_success'
    assert res_data['new_amount'] == 50.00
    assert "已自动冲减" in res_data['notification_title'] or "已自动冲抵" in res_data['notification_title']

    # 检查数据库验证：上一笔支出被扣减为 50，且没有插入新的收入记录
    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 50.00
        assert "收到还款冲减" in exp['note']

        # 确认没有凭空多出收入记录
        inc = db.execute("SELECT * FROM transactions WHERE note LIKE '%Ali%'").fetchone()
        assert inc is None


def test_gasoline_refund_auto_offset(flask_app, client):
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().isoformat()
        today = date.today().isoformat()

        # 1. 模拟加油站预扣款 RM 100.00
        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '交通', 100.00, 'RON95', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_gas_expense_id = cur.lastrowid
        db.commit()

    # 2. 收到加油退款通知（例如加完油后退回差额 RM 29.30）
    refund_text = "Payment refunded You've been refunded RM29.30 for RON95 on 15 Sep 2026."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': refund_text}
    )

    assert resp.status_code == 200
    res_data = resp.get_json()
    assert res_data['ok'] is True
    assert res_data['verdict'] == 'refund_offset_success'
    assert res_data['new_amount'] == 70.70
    assert "加油/消费退款已冲减" in res_data['notification_title']

    # 3. 验证数据库：原加油支出已自动更新为实际加油金额 70.70，无虚假收入生成
    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_gas_expense_id,)).fetchone()
        assert exp['amount'] == 70.70
        assert "已扣减退款" in exp['note']
        assert "29.30" in exp['note']

        # 确保未生成重复虚假收入
        inc = db.execute("SELECT * FROM transactions WHERE type = 'income' AND amount = 29.30").fetchone()
        assert inc is None


def test_go_plus_internal_transfer_ignored(client):
    # 模拟 TnG GO+ 自动零钱/理财转存通知，应被直接忽略且返回 200
    goplus_text = "Cash In Successful You have successfully cashed in RM29.30 into your GO+ account."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': goplus_text}
    )

    assert resp.status_code == 200
    res_data = resp.get_json()
    assert res_data['ok'] is True
    assert res_data['verdict'] == 'ignored_internal_transfer'
    assert "钱包内部资金划转" in res_data['message']


def test_shopee_voucher_ad_rejected(client):
    # 模拟带有金额与返现的促销广告通知，应被识别并拦截
    voucher_text = "Shopee: You just got a voucher! 100% Cashback, sehingga RM5. Check out in-store now!"
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': voucher_text}
    )

    assert resp.status_code == 200
    res_data = resp.get_json()
    assert res_data['ok'] is False
    assert res_data['verdict'] == 'rejected_promo'

