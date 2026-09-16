import json
from core.db import get_db, get_user_settings, update_user_settings, DEFAULT_USER_SETTINGS


def test_settings_page_requires_login(client):
    """未登录用户访问 /settings 应重定向至登录页"""
    res = client.get('/settings', follow_redirects=False)
    assert res.status_code == 302
    assert '/login' in res.headers['Location']


def test_settings_page_renders_logged_in(logged_in_client):
    """已登录用户访问 /settings 正常渲染个性化配置页面"""
    res = logged_in_client.get('/settings')
    assert res.status_code == 200
    html = res.get_data(as_text=True)
    assert '个性化与偏好设置' in html
    assert '默认结算货币与符号' in html
    assert '月度预算与账单起始日' in html
    assert '数据备份与隐私主权' in html


def test_settings_page_htmx_partial(logged_in_client):
    """HTMX 请求 /settings 只渲染局部内容，不包含基础 HTML 外壳"""
    res = logged_in_client.get('/settings', headers={'HX-Request': 'true'})
    assert res.status_code == 200
    html = res.get_data(as_text=True)
    assert '<!DOCTYPE html>' not in html
    assert 'settings-container' in html


def test_api_update_settings_success(logged_in_client, flask_app, admin_user_id):
    """测试通过 API 成功更新偏好设置"""
    payload = {
        'theme_mode': 'dark',
        'currency_symbol': 'SGD',
        'budget_start_day': '25',
        'default_group': 'side',
        'default_dashboard_view': 'overview',
        'dedup_window_minutes': '60',
        'table_density': 'compact',
        'haptic_feedback': '1'
    }

    res = logged_in_client.post(
        '/api/settings/update',
        data=payload,
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True

    with flask_app.app.app_context():
        settings = get_user_settings(admin_user_id)
        assert settings['theme_mode'] == 'dark'
        assert settings['currency_symbol'] == 'SGD'
        assert settings['budget_start_day'] == 25
        assert settings['default_group'] == 'side'
        assert settings['default_dashboard_view'] == 'overview'
        assert settings['dedup_window_minutes'] == 60
        assert settings['table_density'] == 'compact'
        assert settings['haptic_feedback'] == 1


def test_api_reset_settings(logged_in_client, flask_app, admin_user_id):
    """测试恢复默认配置"""
    # 先修改
    logged_in_client.post(
        '/api/settings/update',
        data={'theme_mode': 'light', 'currency_symbol': 'EUR'},
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )

    # 恢复默认
    res = logged_in_client.post(
        '/api/settings/reset',
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True

    with flask_app.app.app_context():
        settings = get_user_settings(admin_user_id)
        assert settings['theme_mode'] == DEFAULT_USER_SETTINGS['theme_mode']
        assert settings['currency_symbol'] == DEFAULT_USER_SETTINGS['currency_symbol']


def test_api_export_data(logged_in_client):
    """测试全量数据导出接口"""
    res = logged_in_client.get('/api/settings/export-data')
    assert res.status_code == 200
    assert 'application/json' in res.content_type
    assert 'attachment; filename=' in res.headers.get('Content-Disposition', '')

    payload = json.loads(res.get_data(as_text=True))
    assert 'export_time' in payload
    assert 'user_id' in payload
    assert 'settings' in payload
    assert 'accounts' in payload
    assert 'categories' in payload
    assert 'transactions' in payload


def test_user_settings_isolation(flask_app):
    """测试不同用户之间的偏好配置隔离"""
    with flask_app.app.app_context():
        db = get_db()
        # 用户 A
        update_user_settings(101, {'currency_symbol': 'USD', 'budget_start_day': 15}, db=db)
        # 用户 B
        update_user_settings(102, {'currency_symbol': 'JPY', 'budget_start_day': 28}, db=db)

        settings_a = get_user_settings(101, db=db)
        settings_b = get_user_settings(102, db=db)

        assert settings_a['currency_symbol'] == 'USD'
        assert settings_a['budget_start_day'] == 15

        assert settings_b['currency_symbol'] == 'JPY'
        assert settings_b['budget_start_day'] == 28


def test_theme_and_density_rendered_in_html(logged_in_client):
    """测试用户偏好的 theme_mode 和 table_density 正确注入基础模板 html 标签"""
    # 设为 dark 和 compact
    logged_in_client.post(
        '/api/settings/update',
        data={'theme_mode': 'dark', 'table_density': 'compact'},
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )
    res_dark = logged_in_client.get('/settings')
    html_dark = res_dark.get_data(as_text=True)
    assert 'data-theme="dark"' in html_dark
    assert 'data-density="compact"' in html_dark

    # 设为 light 和 comfortable
    logged_in_client.post(
        '/api/settings/update',
        data={'theme_mode': 'light', 'table_density': 'comfortable'},
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )
    res_light = logged_in_client.get('/settings')
    html_light = res_light.get_data(as_text=True)
    assert 'data-theme="light"' in html_light
    assert 'data-density="compact"' not in html_light


def test_p0_user_settings_update_and_filters(logged_in_client, flask_app, admin_user_id):
    """测试 P0 定制配置：默认货币、日期格式、数字格式、时区更新及过滤器表现"""
    payload = {
        'default_currency': 'USD',
        'currency_symbol': '$',
        'date_format': 'DD/MM/YYYY',
        'number_format': 'space',
        'timezone': 'America/New_York'
    }

    res = logged_in_client.post(
        '/api/settings/update',
        data=payload,
        headers={'X-Requested-With': 'XMLHttpRequest'}
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True

    with flask_app.app.app_context():
        settings = get_user_settings(admin_user_id)
        assert settings['default_currency'] == 'USD'
        assert settings['currency_symbol'] == '$'
        assert settings['date_format'] == 'DD/MM/YYYY'
        assert settings['number_format'] == 'space'
        assert settings['timezone'] == 'America/New_York'

    # 访问页面，检查 window 全局配置注入
    res = logged_in_client.get('/settings')
    html = res.get_data(as_text=True)
    assert "window.LEDGER_CURRENCY_SYMBOL = '$'" in html
    assert "window.LEDGER_CURRENCY_CODE = 'USD'" in html
    assert "window.LEDGER_DATE_FORMAT = 'DD/MM/YYYY'" in html


def test_custom_currency_renders_on_liabilities_and_subscriptions_and_accounts(logged_in_client, flask_app, admin_user_id):
    """验证自定义货币能正确渗透到负债分期、订阅大厅与账户管理页面"""
    with flask_app.app.app_context():
        update_user_settings(admin_user_id, {
            'default_currency': 'SGD',
            'currency_symbol': 'S$'
        })

    # 1. 负债追踪页
    res_liab = logged_in_client.get('/liabilities')
    assert res_liab.status_code == 200
    html_liab = res_liab.get_data(as_text=True)
    assert '全景未还总负债本金' in html_liab
    assert 'S$ ' in html_liab or 'S$' in html_liab

    # 2. 订阅大厅页
    res_sub = logged_in_client.get('/subscriptions')
    assert res_sub.status_code == 200
    html_sub = res_sub.get_data(as_text=True)
    assert '月度平均订阅支出' in html_sub
    assert 'S$ ' in html_sub or 'S$' in html_sub

    # 3. 账户管理页
    res_acc = logged_in_client.get('/accounts')
    assert res_acc.status_code == 200
    html_acc = res_acc.get_data(as_text=True)
    assert '总信用额度' in html_acc
    assert 'SGD' in html_acc


