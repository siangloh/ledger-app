from services.merchant_service import get_merchant_suggestions


def test_merchant_service_empty_db():
    assert get_merchant_suggestions(user_id=1, query="Starbucks", db=None) == []
    assert get_merchant_suggestions(user_id=None, query="Starbucks", db="mock") == []


def test_merchant_service_suggestions(flask_app):
    with flask_app.app.app_context():
        from core.db import get_db
        db = get_db()
        user_id = 9999

        # 清理可能存在的历史测试数据
        db.execute("DELETE FROM transactions WHERE user_id = ?", (user_id,))
        db.execute("DELETE FROM merchant_category_overrides WHERE merchant_note IN ('Starbucks Midvalley', 'FamilyMart SS2', 'Shell Fuel')")

        # 插入若干笔测试交易
        # Starbucks 记录 3 次，分类 餐饮，最后金额 19.50
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-01', 'expense', '餐饮', 18.00, 'Starbucks Midvalley', 'manual', '2026-10-01T08:00:00')",
            (user_id,)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-02', 'expense', '餐饮', 22.00, 'Starbucks Midvalley', 'manual', '2026-10-02T08:00:00')",
            (user_id,)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-03', 'expense', '餐饮', 19.50, 'Starbucks Midvalley', 'manual', '2026-10-03T08:00:00')",
            (user_id,)
        )

        # FamilyMart 记录 1 次，分类 购物
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-04', 'expense', '购物', 12.80, 'FamilyMart SS2', 'manual', '2026-10-04T08:00:00')",
            (user_id,)
        )

        # Shell Fuel 记录 2 次，分类 交通
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-01', 'expense', '交通', 50.00, 'Shell Fuel', 'manual', '2026-10-01T08:00:00')",
            (user_id,)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, '2026-10-05', 'expense', '交通', 60.00, 'Shell Fuel', 'manual', '2026-10-05T08:00:00')",
            (user_id,)
        )
        db.commit()

        # 1. 无 query 时，应按频次排序 (Starbucks 3次 > Shell Fuel 2次 > FamilyMart 1次)
        top = get_merchant_suggestions(user_id=user_id, query="", db=db)
        assert len(top) == 3
        assert top[0]['name'] == 'Starbucks Midvalley'
        assert top[0]['count'] == 3
        assert top[0]['last_amount'] == 19.50
        assert top[0]['category'] == '餐饮'
        assert top[1]['name'] == 'Shell Fuel'
        assert top[1]['count'] == 2
        assert top[2]['name'] == 'FamilyMart SS2'
        assert top[2]['count'] == 1

        # 2. 带 query 筛选
        star_res = get_merchant_suggestions(user_id=user_id, query="star", db=db)
        assert len(star_res) == 1
        assert star_res[0]['name'] == 'Starbucks Midvalley'

        # 3. 验证 merchant_category_overrides 优先权
        db.execute(
            "INSERT OR REPLACE INTO merchant_category_overrides (merchant_note, category, updated_at) "
            "VALUES ('Starbucks Midvalley', '休闲娱乐', '2026-10-06T12:00:00')"
        )
        db.commit()

        override_res = get_merchant_suggestions(user_id=user_id, query="star", db=db)
        assert override_res[0]['category'] == '休闲娱乐'

        # 清理
        db.execute("DELETE FROM transactions WHERE user_id = ?", (user_id,))
        db.execute("DELETE FROM merchant_category_overrides WHERE merchant_note IN ('Starbucks Midvalley', 'FamilyMart SS2', 'Shell Fuel')")
        db.commit()


def test_api_merchant_suggestions(flask_app, logged_in_client):
    # 1. 未登录访问被重定向至登录页 (302) 或拒绝 (401)
    anonymous_client = flask_app.app.test_client()
    unauth_resp = anonymous_client.get('/api/merchants/suggestions')
    assert unauth_resp.status_code in (302, 401)

    # 2. 登录状态调用 API
    res = logged_in_client.get('/api/merchants/suggestions?limit=5')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert isinstance(data['suggestions'], list)
