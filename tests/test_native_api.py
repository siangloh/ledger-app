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
