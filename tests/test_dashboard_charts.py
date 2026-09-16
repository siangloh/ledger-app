import pytest
from app import app


@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as c:
        with c.session_transaction() as sess:
            sess['user_id'] = 1
            sess['username'] = 'admin'
            sess['logged_in'] = True
        yield c


def test_index_chart_data_injection(client):
    """测试首页渲染时注入的 window.CHART_DATA 与 window.CATEGORY_DATA 格式安全且合法"""
    res = client.get('/')
    assert res.status_code == 200
    html = res.get_data(as_text=True)

    # 验证关键元素存在
    assert 'id="incomeChart"' in html
    assert 'id="expenseChart"' in html
    assert 'id="incomeChartLegend"' in html
    assert 'id="expenseChartLegend"' in html

    # 验证 JS 数据结构注入没有语法错误
    assert 'window.CHART_DATA =' in html
    assert 'month:' in html
    assert 'income:' in html
    assert 'expense:' in html
    assert 'renderDonutCharts();' in html


def test_api_dashboard_charts_endpoint(client):
    """测试 /api/dashboard-charts 接口在指定月份与空数据下的返回结构规范"""
    res = client.get('/api/dashboard-charts?month=2026-09')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['month'] == '2026-09'
    assert 'income' in data
    assert 'labels' in data['income']
    assert 'values' in data['income']
    assert len(data['income']['labels']) == 2
    assert 'expense' in data
    assert 'labels' in data['expense']
    assert 'values' in data['expense']
    assert isinstance(data['expense']['labels'], list)
    assert isinstance(data['expense']['values'], list)


def test_dashboard_budget_currency_follows_settings(logged_in_client, admin_user_id):
    """测试仪表盘月度预算监控的货币符号跟随用户定制配置"""
    from core.db import update_user_settings, get_db

    # 将用户货币设为 S$
    with app.app_context():
        db = get_db()
        update_user_settings(admin_user_id, {'currency_symbol': 'S$', 'default_currency': 'SGD'}, db=db)
        # 设置分类预算
        db.execute(
            "INSERT OR REPLACE INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            (admin_user_id, '餐饮', 500.0, '2026-09-01T00:00:00', '2026-09-01T00:00:00')
        )
        db.commit()

    res = logged_in_client.get('/partial/dashboard-budget?month=2026-09')
    assert res.status_code == 200
    html = res.get_data(as_text=True)
    assert '月度预算监控' in html
    # 验证货币符号不再是写死的 RM，而是用户的 S$
    assert 'S$' in html
    assert '花 S$' in html or '限 S$' in html


def test_category_insights_currency_and_color(logged_in_client, admin_user_id):
    """测试支出分类深度洞察报告与走势图使用用户自定义色彩与货币"""
    from core.db import update_user_settings, get_db

    with app.app_context():
        db = get_db()
        update_user_settings(admin_user_id, {'currency_symbol': 'S$', 'default_currency': 'SGD'}, db=db)
        # 更新餐饮分类颜色
        db.execute(
            "UPDATE categories SET color = '#10b981' WHERE user_id = ? AND name = '餐饮'",
            (admin_user_id,)
        )
        # 添加一笔餐饮交易
        db.execute(
            """INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
               VALUES (?, '2026-09-10', 'expense', '餐饮', 120.0, '聚餐', 'manual', '2026-09-10T12:00:00')""",
            (admin_user_id,)
        )
        db.commit()

    res = logged_in_client.get('/categories/insights?range=all')
    assert res.status_code == 200
    html = res.get_data(as_text=True)
    assert '支出分类深度洞察' in html
    assert '活跃支出分类' in html
    # 验证活跃支出分类不在 light mode 下产生 dark on dark 隐形文字
    assert 'card card-highlight' not in html
    assert 'S$' in html
    assert 'currencySym' in html
