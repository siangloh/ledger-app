from core.db import get_db
from core.utils import money_filter, date_filter


def test_add_category_with_color(logged_in_client, flask_app, admin_user_id):
    """测试新增带有颜色属性的分类"""
    res = logged_in_client.post(
        '/categories/add',
        data={
            'name': '数码科技',
            'type': 'expense',
            'color': '#3b82f6'
        },
        follow_redirects=True
    )
    assert res.status_code == 200

    with flask_app.app.app_context():
        db = get_db()
        cat = db.execute(
            "SELECT * FROM categories WHERE user_id = ? AND name = ?",
            (admin_user_id, '数码科技')
        ).fetchone()
        assert cat is not None
        assert cat['color'] == '#3b82f6'
        assert cat['type'] == 'expense'


def test_edit_category_cascade(logged_in_client, flask_app, admin_user_id):
    """测试编辑分类名称与颜色，验证级联更新交易记录与分类预算"""
    with flask_app.app.app_context():
        db = get_db()
        # 插入原始分类
        db.execute(
            "INSERT INTO categories (user_id, name, type, color) VALUES (?, ?, ?, ?)",
            (admin_user_id, '原始分类', 'expense', '#ef4444')
        )
        cat_id = db.execute(
            "SELECT id FROM categories WHERE user_id = ? AND name = ?",
            (admin_user_id, '原始分类')
        ).fetchone()['id']

        # 插入关联交易
        db.execute(
            """INSERT INTO transactions (user_id, type, amount, category, note, date, group_name, created_at)
               VALUES (?, 'expense', 88.0, '原始分类', '测试级联', '2026-09-16', 'main', '2026-09-16T12:00:00')""",
            (admin_user_id,)
        )
        db.commit()

    # 调用编辑接口
    res = logged_in_client.post(
        f'/categories/{cat_id}/edit',
        data={
            'name': '更新后分类',
            'color': '#10b981'
        },
        follow_redirects=True
    )
    assert res.status_code == 200

    with flask_app.app.app_context():
        db = get_db()
        # 验证分类已更新
        cat = db.execute("SELECT * FROM categories WHERE id = ?", (cat_id,)).fetchone()
        assert cat['name'] == '更新后分类'
        assert cat['color'] == '#10b981'

        # 验证关联交易已被级联重命名
        tx = db.execute(
            "SELECT * FROM transactions WHERE user_id = ? AND note = '测试级联'",
            (admin_user_id,)
        ).fetchone()
        assert tx is not None
        assert tx['category'] == '更新后分类'


def test_add_and_edit_transaction_tags(logged_in_client, flask_app, admin_user_id):
    """测试记账时录入 tags 标签，以及编辑交易时更新 tags"""
    # 记账
    res = logged_in_client.post(
        '/transactions/add',
        data={
            'type': 'expense',
            'amount': '52.50',
            'category': '餐饮',
            'note': '午餐聚会',
            'date': '2026-09-16',
            'tags': '外食, 周末'
        },
        follow_redirects=True
    )
    assert res.status_code == 200

    with flask_app.app.app_context():
        db = get_db()
        tx = db.execute(
            "SELECT * FROM transactions WHERE user_id = ? AND note = '午餐聚会'",
            (admin_user_id,)
        ).fetchone()
        assert tx is not None
        assert '外食' in tx['tags']
        assert '周末' in tx['tags']
        tx_id = tx['id']

    # 编辑该交易的 tags
    res_edit = logged_in_client.post(
        f'/records/{tx_id}/edit',
        data={
            'type': 'expense',
            'amount': '60.00',
            'category': '餐饮',
            'note': '午餐聚会加饮料',
            'date': '2026-09-16',
            'tags': '外食, 聚会, 下午茶'
        },
        follow_redirects=True
    )
    assert res_edit.status_code == 200

    with flask_app.app.app_context():
        db = get_db()
        updated_tx = db.execute("SELECT * FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert '聚会' in updated_tx['tags']
        assert '下午茶' in updated_tx['tags']


def test_utils_date_and_money_filter():
    """测试核心工具格式化函数：date_filter 与 money_filter"""
    # date_filter
    assert date_filter('2026-09-16', 'YYYY-MM-DD') == '2026-09-16'
    assert date_filter('2026-09-16', 'DD/MM/YYYY') == '16/09/2026'
    assert date_filter('2026-09-16', 'MM/DD/YYYY') == '09/16/2026'
    assert date_filter('2026-09-16', 'YYYY/MM/DD') == '2026/09/16'

    # money_filter
    assert money_filter(1234.56, symbol='$', number_format='comma') == '$ 1,234.56'
    assert money_filter(1234.56, symbol='€', number_format='space') == '€ 1 234.56'
    assert money_filter(1234.56, symbol='RM', number_format='none') == 'RM 1234.56'
