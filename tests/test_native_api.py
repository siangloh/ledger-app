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
    assert data['total_spending'] == 140.50  # 25.50 + 100.00 + 15.00
    assert data['expense_amount'] == 40.50   # 25.50 + 15.00
    assert data['transfer_amount'] == 100.00  # 100.00
    assert data['uncategorized_count'] >= 1
    assert any(item['note'] == 'Warung Makan' for item in data['uncategorized_items'])
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

