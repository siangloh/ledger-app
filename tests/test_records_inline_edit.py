from core.db import get_db


def test_records_row_data_attributes_and_edit_button(flask_app, logged_in_client, admin_user_id):
    """验证 records 页面渲染的数据行包含完整的 data-* 属性以及调用 openEditRecordModal 的编辑按钮"""
    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM transactions WHERE user_id = ?", (admin_user_id,))
        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, tags, source, created_at) "
            "VALUES (?, '2026-10-06', 'expense', '餐饮', 38.50, 'A Cafe & Resto', '午餐, 特色', 'manual', datetime('now'))",
            (admin_user_id,)
        )
        tx_id = cur.lastrowid
        db.commit()

    res = logged_in_client.get('/records')
    assert res.status_code == 200
    html = res.get_data(as_text=True)

    # 验证行上挂载的 data 属性
    assert f'id="row-{tx_id}"' in html
    assert f'data-id="{tx_id}"' in html
    assert 'data-date="2026-10-06"' in html
    assert 'data-type="expense"' in html
    assert 'data-category="餐饮"' in html
    assert 'data-amount="38.50"' in html
    assert 'data-note="A Cafe &amp; Resto"' in html or 'data-note="A Cafe & Resto"' in html
    assert 'data-tags="午餐, 特色"' in html

    # 验证包含 openEditRecordModal 调用按钮且采用统一的幽灵胶囊按钮模式 (btn-edit-ghost)
    assert f'openEditRecordModal({tx_id})' in html
    assert 'btn-edit-ghost' in html
    assert 'window.openEditRecordModal = openEditRecordModal;' in html
    assert 'window.CATEGORY_DATA =' in html


def test_edit_record_json_endpoint(flask_app, logged_in_client, admin_user_id):
    """验证 GET /records/<id>/edit?format=json 能够正确返回单条交易的结构化详情与全部分类字典"""
    with flask_app.app.app_context():
        db = get_db()
        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-05', 'expense', '购物', 99.00, 'Supermarket ABC', 'auto_track', datetime('now'))",
            (admin_user_id,)
        )
        tx_id = cur.lastrowid
        db.commit()

    res = logged_in_client.get(f'/records/{tx_id}/edit?format=json')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['row']['id'] == tx_id
    assert data['row']['note'] == 'Supermarket ABC'
    assert 'expense_categories' in data
    assert 'income_categories' in data
    assert 'savings_categories' in data
    assert 'savings_pool_by_category' in data


def test_edit_record_ajax_post_and_merchant_override(flask_app, logged_in_client, admin_user_id):
    """验证通过 AJAX POST /records/<id>/edit 保存修改时返回 JSON，并成功记录商户-分类记忆学习"""
    with flask_app.app.app_context():
        db = get_db()
        # 清理商户记忆表
        db.execute("DELETE FROM merchant_category_overrides WHERE merchant_note = 'Ah Kow Kopitiam'")
        cur = db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-06', 'expense', '其他', 15.00, 'Ah Kow Kopitiam', 'auto_track', datetime('now'))",
            (admin_user_id,)
        )
        tx_id = cur.lastrowid
        db.commit()

    # 模拟用户在弹窗中将分类修改为「餐饮」并提交
    res = logged_in_client.post(
        f'/records/{tx_id}/edit',
        data={
            'type': 'expense',
            'amount': '15.00',
            'date': '2026-10-06',
            'category': '餐饮',
            'note': 'Ah Kow Kopitiam',
            'tags': '咖啡, 早餐'
        },
        headers={'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json'}
    )
    assert res.status_code == 200
    res_data = res.get_json()
    assert res_data['ok'] is True

    # 验证数据库更新
    with flask_app.app.app_context():
        db = get_db()
        tx = db.execute("SELECT * FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert tx['category'] == '餐饮'
        assert tx['tags'] == '咖啡, 早餐'

        # 验证商户-分类记忆学习表自动生成了记录
        override = db.execute(
            "SELECT * FROM merchant_category_overrides WHERE merchant_note = 'Ah Kow Kopitiam'"
        ).fetchone()
        assert override is not None
        assert override['category'] == '餐饮'
