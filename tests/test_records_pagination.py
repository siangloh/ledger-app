from core.db import get_db


def test_records_pagination_initial_and_stream(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        # 清理旧数据，精准测试 30 条记录的分页行为
        db.execute("DELETE FROM transactions WHERE user_id = ?", (admin_user_id,))
        for i in range(1, 31):
            db.execute(
                "INSERT INTO transactions (user_id, date, type, category, amount, note, created_at) "
                "VALUES (?, '2026-09-15', 'expense', '餐饮', ?, ?, datetime('now'))",
                (admin_user_id, float(i), f"item-test-{i}")
            )
        db.commit()

    # 1. 首次打开 /records，默认只读取第一页（25条）
    res = logged_in_client.get('/records')
    assert res.status_code == 200
    html = res.get_data(as_text=True)

    # 验证存在下一页哨兵（加载更多）
    assert 'id="recordsLoadMoreSentinel"' in html
    assert 'page=2' in html
    assert 'partial_rows=1' in html
    assert 'item-test-30' in html  # 倒序排列

    # 2. 模拟前端触底流式拉取：请求 page=2 & partial_rows=1
    res_page2 = logged_in_client.get('/records?page=2&partial_rows=1')
    assert res_page2.status_code == 200
    html_page2 = res_page2.get_data(as_text=True)

    # 第二页返回剩余 5 条记录
    assert 'item-test-1' in html_page2
    # 全部加载完毕后，不再渲染哨兵，而是渲染触底提示
    assert 'id="recordsLoadMoreSentinel"' not in html_page2
    assert 'class="records-end-row"' in html_page2


def test_records_pagination_with_filters(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        db.execute("DELETE FROM transactions WHERE user_id = ?", (admin_user_id,))
        for i in range(1, 15):
            db.execute(
                "INSERT INTO transactions (user_id, date, type, category, amount, note, created_at) "
                "VALUES (?, '2026-09-15', 'expense', '购物', 20.0, 'shopping-filter', datetime('now'))",
                (admin_user_id,)
            )
        db.commit()

    # 使用 page_size=10 测试分页和筛选条件传递
    res = logged_in_client.get('/records?type=expense&category=购物&page=1&page_size=10')
    assert res.status_code == 200
    html = res.get_data(as_text=True)

    assert 'id="recordsLoadMoreSentinel"' in html
    assert 'type=expense' in html
    assert 'shopping-filter' in html
    assert 'page_size=10' in html
    assert 'hx-trigger="click"' in html
    assert 'sentinel-idle' in html
    assert 'sentinel-loading' in html

    # 拉取第 2 页 partial_rows
    res_page2 = logged_in_client.get('/records?type=expense&category=购物&page=2&page_size=10&partial_rows=1')
    assert res_page2.status_code == 200
    html_page2 = res_page2.get_data(as_text=True)
    assert 'id="recordsLoadMoreSentinel"' not in html_page2
    assert 'class="records-end-row"' in html_page2
