from datetime import datetime
import pytest
from app import app
from core.db import get_db
from services.poster_service import get_monthly_poster_data


@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as c:
        with c.session_transaction() as sess:
            sess['user_id'] = 1
            sess['username'] = 'admin'
            sess['logged_in'] = True
            sess['locale'] = 'zh'
        yield c


def test_poster_service_empty_data():
    """测试无数据月份的海报数据生成与基础降级"""
    with app.app_context():
        db = get_db()
        data = get_monthly_poster_data(user_id=99999, month_str='2026-01', lang='zh', db=db)
        assert data['ok'] is True
        assert data['month'] == '2026-01'
        assert data['year'] == 2026
        assert data['month_num'] == 1
        assert data['metrics']['total_income'] == 0.0
        assert data['metrics']['total_expense'] == 0.0
        assert data['metrics']['savings_rate'] == 0.0
        assert data['persona']['title'] == '静候启程者'


def test_poster_service_with_transactions():
    """测试有交易数据月份的各项统计、分类排行、人格评定与多语言"""
    with app.app_context():
        db = get_db()
        user_id = 1
        test_month = '2026-05'
        now_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        # 插入测试数据
        db.execute('''
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, from_savings, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (user_id, '2026-05-01', 'income', 'main', '工资', 10000.0, '5月薪水', 0, now_str))

        db.execute('''
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, from_savings, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (user_id, '2026-05-02', 'income', 'side', '投资收益', 2000.0, '股息', 0, now_str))

        db.execute('''
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, from_savings, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (user_id, '2026-05-05', 'expense', 'main', '餐饮', 600.0, '请客聚餐', 0, now_str))

        db.execute('''
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, from_savings, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (user_id, '2026-05-10', 'expense', 'main', '数码', 2400.0, '买显示器', 0, now_str))

        db.commit()

        try:
            # 测试中文生成
            data_zh = get_monthly_poster_data(user_id=user_id, month_str=test_month, lang='zh', db=db)
            assert data_zh['ok'] is True
            assert data_zh['metrics']['total_income'] == 12000.0
            assert data_zh['metrics']['total_expense'] == 3000.0
            assert data_zh['metrics']['net_balance'] == 9000.0
            assert data_zh['metrics']['savings_rate'] == 75.0
            assert data_zh['persona']['title'] == '储蓄大师'
            assert data_zh['max_expense']['amount'] == 2400.0
            assert data_zh['max_expense']['category'] == '数码'
            assert len(data_zh['top_categories']) == 2
            assert data_zh['top_categories'][0]['name'] == '数码'

            # 测试英文人格生成与分类翻译
            data_en = get_monthly_poster_data(user_id=user_id, month_str=test_month, lang='en', db=db)
            assert data_en['ok'] is True
            assert data_en['persona']['title'] == 'Master Saver'
            assert 'Savings rate reached 75.0%' in data_en['persona']['commentary']
            assert data_en['top_categories'][0]['name'] == 'Digital & Tech'
            assert data_en['max_expense']['category'] == 'Digital & Tech'
            assert data_en['month_display_title'] == 'MAY 2026'
            assert data_en['budget_diagnostic']['status_title'] == 'Golden Allocation'

            # 验证环比、同比与储蓄策略数据结构
            assert 'comparison' in data_zh
            assert 'mom' in data_zh['comparison']
            assert data_zh['comparison']['mom']['has_baseline'] is not None
            assert 'yoy' in data_zh['comparison']
            assert 'budget_diagnostic' in data_zh
            assert data_zh['budget_diagnostic']['status_title'] == '黄金资产配置'
            assert 'savings_strategies' in data_zh
            assert len(data_zh['savings_strategies']) >= 3
            assert data_zh['savings_strategies'][0]['title'] == '50/30/20 经典资产配置法则'

            # 验证英文储蓄策略
            assert data_en['savings_strategies'][0]['title'] == 'The 50/30/20 Budgeting Rule'

            # 验证马来文储蓄策略与分类
            data_ms = get_monthly_poster_data(user_id=user_id, month_str=test_month, lang='ms', db=db)
            assert data_ms['ok'] is True
            assert data_ms['persona']['title'] == 'Pakar Penabung'
            assert data_ms['savings_strategies'][0]['title'] == 'Peraturan Belanjawan 50/30/20'
            assert data_ms['top_categories'][0]['name'] == 'Elektronik / Digital'
            assert data_ms['month_display_title'] == 'MEI 2026'
            assert data_ms['budget_diagnostic']['status_title'] == 'Agihan Emas'

            # 验证繁体中文分类与诊断
            data_tw = get_monthly_poster_data(user_id=user_id, month_str=test_month, lang='zh_TW', db=db)
            assert data_tw['ok'] is True
            assert data_tw['persona']['title'] == '儲蓄大師'
            assert data_tw['top_categories'][0]['name'] == '數碼'
            assert data_tw['budget_diagnostic']['status_title'] == '黃金資產配置'

        finally:
            # 清理测试数据
            db.execute("DELETE FROM transactions WHERE user_id = ? AND date LIKE '2026-05-%'", (user_id,))
            db.commit()


def test_api_monthly_poster_unauthorized():
    """测试未登录用户访问海报接口被拦截"""
    with app.test_client() as c:
        res = c.get('/api/reports/monthly_poster?month=2026-05')
        assert res.status_code in (302, 401)


def test_api_monthly_poster_success(client):
    """测试登录用户调用海报接口成功返回 200 与完整 JSON 数据结构"""
    res = client.get('/api/reports/monthly_poster?month=2026-05&lang=en')
    assert res.status_code == 200
    json_data = res.get_json()
    assert json_data['ok'] is True
    assert json_data['lang'] == 'en'
    assert 'metrics' in json_data
    assert 'top_categories' in json_data
    assert 'persona' in json_data
    assert 'currency_symbol' in json_data
    assert 'comparison' in json_data
    assert 'budget_diagnostic' in json_data
    assert 'savings_strategies' in json_data


def test_poster_pdf_translations():
    """测试海报 PDF 与图片导出文案在全部 4 种语言中完整存在"""
    from core.i18n import t, get_client_translations
    locales = ['zh', 'en', 'ms', 'zh_TW']
    for loc in locales:
        pdf_txt = t('poster.download_pdf', lang=loc)
        assert 'PDF' in pdf_txt
        png_txt = t('poster.download_png', lang=loc)
        assert 'PNG' in png_txt
        toast_txt = t('poster.pdf_downloaded_toast', lang=loc)
        assert len(toast_txt) > 0
        btn_txt = t('poster.generate_btn', lang=loc)
        assert 'PDF' in btn_txt

        client_dict = get_client_translations(loc)
        assert 'poster.download_pdf' in client_dict
        assert 'poster.download_png' in client_dict
        assert 'poster.pdf_downloaded_toast' in client_dict

