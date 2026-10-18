from core.db import get_db


def test_native_overview_api(flask_app, client, admin_user_id):
    """测试原生 Android Overview 聚合接口的结构与计算准确性"""
    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM transactions WHERE user_id = ?", (admin_user_id,))
        # 插入一笔餐饮支出、一笔内部划转、一笔未分类支出
        db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
            VALUES (?, '2026-10-15', 'expense', '餐饮', 25.50, 'Luckin Coffee', 'auto_track', datetime('now'))
        ''', (admin_user_id,))
        db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
            VALUES (?, '2026-10-16', 'expense', '内部划转', 100.00, 'Self Transfer to MAE', 'auto_track', datetime('now'))
        ''', (admin_user_id,))
        db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
            VALUES (?, '2026-10-17', 'expense', '其他', 15.00, 'Warung Makan', 'auto_track', datetime('now'))
        ''', (admin_user_id,))
        db.commit()

    headers = {"X-API-KEY": "test-auto-track-key"}
    res = client.get('/api/native/overview?month=2026-10&period=month', headers=headers)
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['total_spending'] == 40.50   # 内部划转已严格剔除，仅统计真实外部日常消费 25.50 + 15.00
    assert data['expense_amount'] == 40.50   # 25.50 + 15.00
    assert data['transfer_amount'] == 100.00  # 100.00 内部划转单独统计
    assert data['uncategorized_count'] >= 1
    assert any(item['note'] == 'Warung Makan' and item['source_type'] == 'transaction' for item in data['uncategorized_items'])
    assert len(data['recent_transactions']) >= 3
    assert data['listening_status']['active'] is True


def test_native_quick_categorize_api(flask_app, client, admin_user_id):
    """测试原生快捷修改分类与商户记忆学习接口"""
    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM merchant_category_overrides WHERE merchant_note = 'Mixue Bubble Tea'")
        cur = db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
            VALUES (?, '2026-10-17', 'expense', '其他', 8.50, 'Mixue Bubble Tea', 'auto_track', datetime('now'))
        ''', (admin_user_id,))
        tx_id = cur.lastrowid
        db.commit()

    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
    payload = {
        "transaction_id": tx_id,
        "category": "餐饮",
        "remember_merchant": True,
        "note": "Mixue Bubble Tea"
    }
    res = client.post('/api/native/quick-categorize', json=payload, headers=headers)
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['category'] == '餐饮'
    assert data['remembered'] is True

    # 验证数据库中分类被正确更新
    with flask_app.app.app_context():
        db = get_db()
        tx = db.execute("SELECT category FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert tx['category'] == '餐饮'

        # 验证商户记忆覆盖规则被持久化
        rule = db.execute("SELECT category FROM merchant_category_overrides WHERE merchant_note = 'Mixue Bubble Tea'").fetchone()
        assert rule is not None
        assert rule['category'] == '餐饮'


def test_native_transactions_crud(flask_app, client, admin_user_id):
    """测试原生交易流水的增删改查 REST 接口"""
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}

    # 1. 新增支出
    add_payload = {
        "type": "expense",
        "amount": 42.00,
        "category": "餐饮",
        "date": "2026-10-18",
        "note": "KFC Lunch",
        "tags": "#food #lunch"
    }
    res = client.post('/api/native/transactions', json=add_payload, headers=headers)
    assert res.status_code == 200
    res_data = res.get_json()
    assert res_data['ok'] is True
    tx_id = res_data['id']

    # 2. 查询列表（带筛选）
    res_list = client.get('/api/native/transactions?type=expense&month=2026-10&search=KFC', headers=headers)
    assert res_list.status_code == 200
    list_data = res_list.get_json()
    assert list_data['ok'] is True
    assert list_data['total_count'] >= 1
    assert any(item['id'] == tx_id and item['amount'] == 42.00 for item in list_data['items'])

    # 3. 编辑交易
    update_payload = {
        "type": "expense",
        "amount": 45.50,
        "category": "餐饮",
        "date": "2026-10-18",
        "note": "KFC Dinner",
        "tags": "#food"
    }
    res_update = client.put(f'/api/native/transactions/{tx_id}', json=update_payload, headers=headers)
    assert res_update.status_code == 200
    assert res_update.get_json()['ok'] is True

    # 4. 删除交易
    res_del = client.delete(f'/api/native/transactions/{tx_id}', headers=headers)
    assert res_del.status_code == 200
    assert res_del.get_json()['ok'] is True


def test_native_accounts_and_categories_api(flask_app, client, admin_user_id):
    """测试原生资产账户与分类接口"""
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}

    # 1. 创建新账户
    acc_payload = {
        "name": "Maybank Savings",
        "type": "savings",
        "currency": "MYR"
    }
    res_acc = client.post('/api/native/accounts', json=acc_payload, headers=headers)
    assert res_acc.status_code == 200
    assert res_acc.get_json()['ok'] is True

    # 2. 查询账户概览
    res_get = client.get('/api/native/accounts', headers=headers)
    assert res_get.status_code == 200
    acc_data = res_get.get_json()
    assert acc_data['ok'] is True
    assert any(a['name'] == 'Maybank Savings' for a in acc_data['accounts'])

    # 3. 查询分类列表
    res_cat = client.get('/api/native/categories', headers=headers)
    assert res_cat.status_code == 200
    cat_data = res_cat.get_json()
    assert cat_data['ok'] is True
    assert len(cat_data['expense_categories']) >= 1
    assert len(cat_data['income_categories']) >= 1

    # 4. 查询工具汇总 (Subscriptions, Installments)
    res_tools = client.get('/api/native/tools-summary', headers=headers)
    assert res_tools.status_code == 200
    assert res_tools.get_json()['ok'] is True


def test_native_write_csrf_protection_for_cookie_session(flask_app, client, admin_user_id):
    """回归测试 [P1]: 仅凭 Cookie 会话请求原生写接口时，缺少 CSRF 或跨源必须被拒绝 (403)"""
    with flask_app.app.app_context():
        flask_app.app.config['WTF_CSRF_ENABLED'] = True

    try:
        with client.session_transaction() as sess:
            sess['user_id'] = admin_user_id
            sess['logged_in'] = True

        # 1. 模拟恶意跨源 POST 请求（无 API Key，来自 attacker.invalid）
        cross_origin_headers = {
            "Origin": "https://attacker.invalid",
            "Referer": "https://attacker.invalid/form.html"
        }
        res_cross = client.post('/api/native/transactions', data={"amount": "100.0", "type": "expense"}, headers=cross_origin_headers)
        assert res_cross.status_code == 403

        # 2. 同源请求但未携带有效 CSRF token 时同样必须被拦截 (403)
        res_no_csrf = client.post('/api/native/transactions', data={"amount": "100.0", "type": "expense"})
        assert res_no_csrf.status_code == 403

        # 3. 携带合法原生 X-API-KEY 时不受浏览器 Cookie 会话与 CSRF 拦截
        api_headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
        res_key = client.post('/api/native/transactions', json={"amount": 12.5, "type": "expense", "category": "餐饮"}, headers=api_headers)
        assert res_key.status_code == 200
    finally:
        with flask_app.app.app_context():
            flask_app.app.config['WTF_CSRF_ENABLED'] = False


def test_native_account_ownership_and_leak_prevention(flask_app, client, admin_user_id):
    """回归测试 [P1]: 严禁关联其他用户的账户，严禁通过 JOIN 泄露其他用户账户名称"""
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}

    # 在隔离数据库中创建受害者用户 B 及其专属私密账户
    with flask_app.app.app_context():
        db = get_db()
        db.execute("INSERT OR IGNORE INTO users (id, username, password_hash, created_at) VALUES ('user_victim', 'victim', 'hash', datetime('now'))")
        cur = db.execute("INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at) VALUES ('user_victim', 'VictimPrivateSwissBank', 'savings', 0, 'MYR', 1, datetime('now'))")
        victim_acc_id = cur.lastrowid
        db.commit()

    # 用户 A 试图使用 victim_acc_id 记账 -> 必须被拒绝 (400)
    add_payload = {
        "type": "expense",
        "amount": 99.00,
        "category": "购物",
        "account_id": victim_acc_id
    }
    res_bad = client.post('/api/native/transactions', json=add_payload, headers=headers)
    assert res_bad.status_code == 400
    assert "指定的账户不存在或无权访问" in res_bad.get_json()['message']

    # 即使存在脏数据（例如历史写入了别人的 account_id），流水列表也严禁把 victim 账户名称泄漏出来
    with flask_app.app.app_context():
        db = get_db()
        cur_dirty = db.execute("INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at, account_id) VALUES (?, '2026-10-18', 'expense', '购物', 50.0, 'DirtyTx', 'manual', datetime('now'), ?)", (admin_user_id, victim_acc_id))
        dirty_tx_id = cur_dirty.lastrowid
        db.commit()

    res_list = client.get('/api/native/transactions?search=DirtyTx', headers=headers)
    assert res_list.status_code == 200
    dirty_item = next(it for it in res_list.get_json()['items'] if it['id'] == dirty_tx_id)
    # 验证 JOIN 受到 a.user_id = t.user_id 约束，绝不会返回 VictimPrivateSwissBank
    assert dirty_item['account_name'] != 'VictimPrivateSwissBank'
    assert dirty_item['account_name'] == '未指定账户'


def test_native_transfer_pair_consistency_and_filter(flask_app, client, admin_user_id):
    """回归测试 [P1]: 账户转账原子成对生成、自转筛选、原子同步删除与概览收支隔离"""
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}

    with flask_app.app.app_context():
        db = get_db()
        c1 = db.execute("INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at) VALUES (?, 'FromBank', 'savings', 0, 'MYR', 1, datetime('now'))", (admin_user_id,))
        acc_from = c1.lastrowid
        c2 = db.execute("INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at) VALUES (?, 'ToWallet', 'e_wallet', 0, 'MYR', 1, datetime('now'))", (admin_user_id,))
        acc_to = c2.lastrowid
        db.commit()

    # 1. 发起内部划转 200.00
    transfer_payload = {
        "type": "transfer",
        "amount": 200.00,
        "account_id": acc_from,
        "to_account_id": acc_to,
        "note": "日常调拨资金"
    }
    res_tx = client.post('/api/native/transactions', json=transfer_payload, headers=headers)
    assert res_tx.status_code == 200
    out_tx_id = res_tx.get_json()['id']

    # 2. 测试自转筛选 (type=transfer)
    res_filter = client.get('/api/native/transactions?type=transfer', headers=headers)
    assert res_filter.status_code == 200
    t_items = res_filter.get_json()['items']
    assert len(t_items) >= 2  # 包含了出账与入账两笔记录
    assert any(it['amount'] == 200.00 and it['category'] == '内部划转' for it in t_items)

    # 3. 测试概览接口：内部划转不得污染日常外部总支出
    res_overview = client.get('/api/native/overview?period=month', headers=headers)
    assert res_overview.status_code == 200
    ov_data = res_overview.get_json()
    assert ov_data['transfer_amount'] >= 200.00
    # 确认 total_spending 不包含该 200.00 自转
    assert ov_data['total_spending'] == ov_data['expense_amount']

    # 4. 测试原子同步删除：删除出账，入账端也必须被一并清理，绝不残留单边孤儿账目
    res_del = client.delete(f'/api/native/transactions/{out_tx_id}', headers=headers)
    assert res_del.status_code == 200

    with flask_app.app.app_context():
        db = get_db()
        remain_transfers = db.execute("SELECT COUNT(*) AS cnt FROM transactions WHERE user_id = ? AND note LIKE '%日常调拨资金%' AND deleted_at IS NULL", (admin_user_id,)).fetchone()
        assert remain_transfers['cnt'] == 0
        deleted_transfers = db.execute("SELECT COUNT(*) AS cnt FROM transactions WHERE user_id = ? AND note LIKE '%日常调拨资金%' AND deleted_at IS NOT NULL", (admin_user_id,)).fetchone()
        assert deleted_transfers['cnt'] == 2


def test_native_subscription_monthly_calculation(flask_app, client, admin_user_id):
    """回归测试 [P2]: 订阅月均成本折算（年付、季付、周付）与货币单位转换"""
    headers = {"X-API-KEY": "test-auto-track-key"}

    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (admin_user_id,))
        # 年付 MYR 120 -> 月均 10.00
        db.execute("""
            INSERT INTO subscriptions (user_id, name, category, billing_cycle, cost, currency, next_billing_date, auto_renew, start_date)
            VALUES (?, 'iCloud Storage', '软件订阅', 'yearly', 120.0, 'MYR', '2027-10-18', 1, '2026-10-18')
        """, (admin_user_id,))
        # 季付 MYR 60 -> 月均 20.00
        db.execute("""
            INSERT INTO subscriptions (user_id, name, category, billing_cycle, cost, currency, next_billing_date, auto_renew, start_date)
            VALUES (?, 'Gym Pass', '运动健身', 'quarterly', 60.0, 'MYR', '2027-01-18', 1, '2026-10-18')
        """, (admin_user_id,))
        db.commit()

    res = client.get('/api/native/tools-summary', headers=headers)
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    # 期望月均: 120 / 12 (10) + 60 / 3 (20) = 30.00
    assert data['total_monthly_sub'] == 30.00

    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM subscriptions WHERE user_id = ?", (admin_user_id,))
        db.commit()


def test_offline_sync_with_accounts_tags_and_transfer(flask_app, client, admin_user_id):
    """验证离线同步契约能完整恢复 account_id、tags 以及两端配对转账"""
    with flask_app.app.app_context():
        db = get_db()
        cur = db.execute("INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at) VALUES (?, 'SyncAccA', 'savings', 0, 'MYR', 1, datetime('now'))", (admin_user_id,))
        acc_a = cur.lastrowid
        cur2 = db.execute("INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at) VALUES (?, 'SyncAccB', 'current', 0, 'MYR', 1, datetime('now'))", (admin_user_id,))
        acc_b = cur2.lastrowid
        db.commit()

    headers = {'X-API-KEY': 'test-auto-track-key'}
    payload = {
        "username": "admin",
        "transactions": [
            {
                "local_id": 9991,
                "date": "2026-10-18",
                "type": "expense",
                "category": "餐饮",
                "amount": 35.50,
                "note": "离线午餐",
                "account_id": acc_a,
                "tags": "lunch work"
            },
            {
                "local_id": 9992,
                "date": "2026-10-18",
                "type": "transfer",
                "amount": 150.00,
                "account_id": acc_a,
                "to_account_id": acc_b,
                "note": "离线A转B"
            }
        ]
    }
    res = client.post('/api/transactions/sync', json=payload, headers=headers)
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert 9991 in data['synced_ids']
    assert 9992 in data['synced_ids']

    with flask_app.app.app_context():
        db = get_db()
        # 1. 验证常规离线消费记录的 account_id 与 tags
        tx1 = db.execute("SELECT * FROM transactions WHERE user_id = ? AND note = '离线午餐'", (admin_user_id,)).fetchone()
        assert tx1 is not None
        assert tx1['account_id'] == acc_a
        assert "lunch" in tx1['tags']

        # 2. 验证离线转账成功生成两端配对分录
        transfers = db.execute("SELECT * FROM transactions WHERE user_id = ? AND (note LIKE '%离线A转B%' OR note LIKE '%SyncAcc%')", (admin_user_id,)).fetchall()
        assert len(transfers) >= 2
        acc_ids = {t['account_id'] for t in transfers}
        assert acc_a in acc_ids
        assert acc_b in acc_ids





