import codecs
from core.db import get_db, update_user_settings, get_user_settings
from core.utils import get_billing_cycle_dates


def test_get_billing_cycle_dates():
    # 1. 默认每月 1 日：整日历月
    start, end, label = get_billing_cycle_dates('2026-09', 1)
    assert start == '2026-09-01'
    assert end == '2026-09-30'
    assert label == '09-01 ~ 09-30'

    # 2. 发薪日每月 15 日：跨月至下月 14 日
    start, end, label = get_billing_cycle_dates('2026-09', 15)
    assert start == '2026-09-15'
    assert end == '2026-10-14'
    assert label == '09-15 ~ 10-14'

    # 3. 跨年：12 月 25 日起至次年 1 月 24 日
    start, end, label = get_billing_cycle_dates('2026-12', 25)
    assert start == '2026-12-25'
    assert end == '2027-01-24'
    assert label == '12-25 ~ 01-24'

    # 4. 月末边界：1 月 31 日起，次月 2 月只有 28 天
    start, end, label = get_billing_cycle_dates('2026-01', 31)
    assert start == '2026-01-31'
    assert end == '2026-02-27'
    assert label == '01-31 ~ 02-27'


def test_billing_cycle_dashboard_aggregation(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        # 将用户账单起始日设为 15 日
        update_user_settings(admin_user_id, {'budget_start_day': 15}, db=db)

        # 插入 2 笔交易：一笔在 5月10日 (属于 4月周期 04-15 ~ 05-14)，一笔在 5月20日 (属于 5月周期 05-15 ~ 06-14)
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, created_at) "
            "VALUES (?, '2028-05-10', 'expense', '餐饮', 88.0, '旧周期', datetime('now'))",
            (admin_user_id,)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, created_at) "
            "VALUES (?, '2028-05-20', 'expense', '餐饮', 150.0, '当期账单', datetime('now'))",
            (admin_user_id,)
        )
        db.commit()

    # 访问仪表盘 2028-05 周期
    res = logged_in_client.get('/?month=2028-05')
    assert res.status_code == 200
    html = res.data.decode('utf-8')
    # 周期标注存在
    assert '账单周期: 05-15 ~ 06-14' in html
    # 当期账单 150.00 应包含在支出中
    assert '150.00' in html


def test_nlp_confirm_required_settings_api(logged_in_client, flask_app, admin_user_id):
    # 默认 nlp_confirm_required 为 1
    with flask_app.app.app_context():
        settings = get_user_settings(admin_user_id)
        assert settings['nlp_confirm_required'] == 1

    # 通过 API 修改为 0 (免确认)
    res = logged_in_client.post('/api/settings/update', json={'nlp_confirm_required': 0})
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['settings']['nlp_confirm_required'] == 0

    with flask_app.app.app_context():
        settings = get_user_settings(admin_user_id)
        assert settings['nlp_confirm_required'] == 0


def test_csv_export_format(logged_in_client, flask_app, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, tags, created_at) "
            "VALUES (?, '2026-09-16', 'expense', '咖啡饮品', 25.5, '星巴克', '出差,下午茶', datetime('now'))",
            (admin_user_id,)
        )
        db.commit()

    res = logged_in_client.get('/api/settings/export-data?format=csv')
    assert res.status_code == 200
    assert 'text/csv' in res.headers.get('Content-Type', '')
    assert 'attachment' in res.headers.get('Content-Disposition', '')
    assert res.data.startswith(codecs.BOM_UTF8)

    csv_text = res.data.decode('utf-8-sig')
    assert '流水号,记账日期,收支类型' in csv_text
    assert '咖啡饮品' in csv_text
    assert '25.50' in csv_text
    assert '出差,下午茶' in csv_text
