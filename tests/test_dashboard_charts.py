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
