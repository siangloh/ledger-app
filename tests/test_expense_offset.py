from datetime import datetime, date, timedelta
from app import get_db, DEFAULT_AUTO_TRACK_KEY
from core.db import update_user_settings


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


def test_repayment_offset_outside_time_window_not_offset(flask_app, client):
    # 模拟 3 小时前 (180 分钟前) 的支出，超出了默认 120 分钟窗口，应作为普通收入入账而不冲减旧支出
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        old_time = (datetime.now() - timedelta(minutes=180)).strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()
        # 确保该用户的所有旧支出时间都在 180 分钟前，避免被前序测试的即时数据干扰
        db.execute("UPDATE transactions SET created_at = ? WHERE user_id = ? AND type = 'expense'", (old_time, user_id))

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 50.00, '3小时前的午餐', 'auto_track', ?)",
            (user_id, today, old_time)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    notif_text = "DuitNow Transfer: You have received RM 25.00 from Uncle Lim on 10 Sep 2026. Ref: DN180M."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data['ok'] is True
    # 应当作为普通收入入账，而非 offset_success
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        # 原支出保持 50.00 未被修改
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 50.00
        assert "收到还款冲减" not in (exp['note'] or '')

        # 确认为这笔转账生成了独立的收入记录
        inc = db.execute("SELECT * FROM transactions WHERE type = 'income' AND amount = 25.00").fetchone()
        assert inc is not None


def test_self_transfer_keyword_not_offset(flask_app, client):
    # 模拟自己账户转账关键词 (如 "Transfer to own account" 或 "自己账户转入")
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 60.00, 'KFC 晚餐', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    # 包含 self transfer / own account 关键词
    notif_text = "DuitNow Transfer: Received RM 30.00 from own account on 10 Sep 2026. Ref: SELF001."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data['ok'] is True
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 60.00
        assert "收到还款冲减" not in (exp['note'] or '')


def test_self_transfer_registered_account_not_offset(flask_app, client):
    # 模拟通过在 accounts 表中注册的自有账户 (例如 Maybank) 转账进账
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()

        # 确保用户拥有 Maybank 账户
        db.execute(
            "INSERT OR IGNORE INTO accounts (user_id, name, type, created_at) VALUES (?, 'Maybank', 'bank', ?)",
            (user_id, now)
        )

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '购物', 70.00, 'Uniqlo', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    # 来自自有账户 Maybank 的转账
    notif_text = "Transfer from Maybank RM 40.00 credited to your account on 10 Sep 2026."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 70.00


def test_repayment_amount_exceeds_bill_not_offset(flask_app, client):
    # 进账金额大于上一笔支出金额 (例如晚餐 RM 25，进账 RM 200)，不属于该笔消费的分摊还款
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 25.00, '咖啡小吃', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    notif_text = "DuitNow Transfer: You have received RM 200.00 from Ah Seng on 10 Sep 2026. Ref: EXCEED01."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 25.00


def test_non_splittable_category_not_offset(flask_app, client):
    # 房租、贷款、理财等非分摊分类禁止自动冲抵
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '房租', 1500.00, '9月房租', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    notif_text = "DuitNow Transfer: You have received RM 500.00 from John on 10 Sep 2026. Ref: RENT01."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 1500.00


def test_repayment_offset_disabled_via_settings(flask_app, client):
    # 当用户设置 repayment_offset_window_minutes = 0 时，完全停用还款自动冲抵
    with flask_app.app.app_context():
        db = get_db()
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        user_id = admin_row['id']
        update_user_settings(user_id, {'repayment_offset_window_minutes': 0}, db=db)

        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        today = date.today().isoformat()

        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', NULL, '餐饮', 50.00, 'Zus Coffee', 'auto_track', ?)",
            (user_id, today, now)
        )
        target_expense_id = cur.lastrowid
        db.commit()

    notif_text = "DuitNow Transfer: You have received RM 20.00 from Mary on 10 Sep 2026. Ref: DIS01."
    resp = client.post(
        '/api/auto-track',
        headers={'X-API-KEY': DEFAULT_AUTO_TRACK_KEY},
        json={'text': notif_text}
    )

    assert resp.status_code == 201
    res_data = resp.get_json()
    assert res_data.get('verdict') != 'offset_success'

    with flask_app.app.app_context():
        db = get_db()
        exp = db.execute("SELECT * FROM transactions WHERE id = ?", (target_expense_id,)).fetchone()
        assert exp['amount'] == 50.00
        # 恢复默认设置
        update_user_settings(user_id, {'repayment_offset_window_minutes': 120}, db=db)
        db.commit()


