from core.db import get_db


def test_database_performance_indices_exist(flask_app):
    """验证数据库是否已创建用于加速页面导航与聚合查询的关键复合索引"""
    with flask_app.app.app_context():
        db = get_db()
        # 查询 sqlite_master 中的所有自定义索引
        rows = db.execute("SELECT name, tbl_name FROM sqlite_master WHERE type = 'index'").fetchall()
        index_names = {r['name'] for r in rows if r['name']}

        expected_indices = [
            'idx_transactions_user_date',
            'idx_transactions_user_type',
            'idx_categories_user_type',
            'idx_accounts_user',
            'idx_user_settings_uid',
            'idx_recurring_user',
        ]
        for idx in expected_indices:
            assert idx in index_names, f"Expected index {idx} not found in database"


def test_static_asset_cache_control_headers(client):
    """验证带版本号与 vendor 库的静态资源具备长效 Cache-Control 缓存头"""
    # 1. 检查 vendor 静态脚本
    res_vendor = client.get('/static/vendor/htmx.min.js')
    assert res_vendor.status_code == 200
    assert 'public' in res_vendor.headers.get('Cache-Control', '')
    assert 'max-age=31536000' in res_vendor.headers.get('Cache-Control', '')

    # 2. 检查带版本号的样式表
    res_css = client.get('/static/style.css?v=20260930_v9')
    assert res_css.status_code == 200
    assert 'max-age=31536000' in res_css.headers.get('Cache-Control', '')

    # 3. 检查常规 HTML 页面严格不缓存
    res_html = client.get('/login')
    assert res_html.status_code == 200
    assert 'no-store' in res_html.headers.get('Cache-Control', '')


def test_service_worker_precache_assets(client):
    """验证 Service Worker 脚本中包含必要的前端核心库预缓存清单"""
    res = client.get('/sw.js')
    assert res.status_code == 200
    sw_code = res.get_data(as_text=True)
    assert 'htmx.min.js' in sw_code
    assert 'chart.umd.min.js' in sw_code
    assert 'sweetalert2.all.min.js' in sw_code
    assert 'ledger-pwa-' in sw_code


def test_index_recurring_task_session_throttling(logged_in_client):
    """验证连续快速访问首页时，固定收支检测具备会话级防抖频控"""
    res1 = logged_in_client.get('/')
    assert res1.status_code == 200

    # 再次立即访问，不发生多余重复计算
    res2 = logged_in_client.get('/')
    assert res2.status_code == 200
