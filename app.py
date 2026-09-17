import os
import sys
import hmac
import secrets
import logging
from calendar import monthrange
from datetime import date, timedelta
from werkzeug.middleware.proxy_fix import ProxyFix
from flask_wtf.csrf import CSRFError
from flask import (
    Flask,
    g,
    request,
    redirect,
    url_for,
    render_template,
    flash,
    jsonify,
    session,
    send_from_directory,
    make_response
)

logger = logging.getLogger(__name__)

# 确保在 Windows 控制台环境下输出中文不发生 charmap 编码崩溃
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
if hasattr(sys.stderr, 'reconfigure'):
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

# 核心配置与基础模块导入
from core.config import (
    BASE_DIR,
    DATA_DIR,
    DB_PATH,
    UPLOAD_DIR,
    TURSO_URL,
    TURSO_AUTH_TOKEN,
    DEFAULT_AUTO_TRACK_KEY,
    AUTO_TRACK_KEY,
    is_valid_api_key,
    get_auto_track_key,
    get_active_llm_provider,
    get_app_password
)
from core.db import (
    get_db,
    close_db,
    init_db,
    bump_data_version,
    get_data_version,
    get_latest_event,
    get_categories,
    get_current_user_id,
    init_user_default_categories,
    seed_learning_samples,
    get_user_settings,
    DATA_VERSION,
    LATEST_EVENT,
    USER_DATA_VERSIONS,
    USER_LATEST_EVENTS
)
from core.auth import is_ajax_request
from core.utils import (
    money_filter,
    date_filter,
    shift_month,
    get_savings_breakdown,
    get_category_budget_status,
    check_and_record_budget_alerts,
    generate_due_recurring,
    get_billing_cycle_dates
)
from core.extensions import csrf

# 业务服务与向后兼容导出
from services.notification_service import (
    parse_auto_track_notification,
    parse_nlp_text,
    MERCHANT_CATEGORY_MAPPING,
    EXPENSE_CATEGORY_KEYWORDS
)
from services.ai_service import (
    call_llm_json,
    classify_notification_with_llm,
    parse_nlp_with_llm
)
from services.ocr_service import (
    get_rapid_ocr,
    smart_orient_receipt_ocr,
    score_receipt_orientation,
    parse_receipt_text_to_items
)

# 蓝图导入
from blueprints.auth import auth_bp
from blueprints.auto_track import auto_track_bp
from blueprints.transactions import transactions_bp
from blueprints.liabilities import liabilities_bp, ACCOUNT_TYPES, ACCOUNT_CURRENCIES
from blueprints.subscriptions import subscriptions_bp
from blueprints.split_bill import split_bill_bp
from blueprints.analytics import analytics_bp, get_category_insights_data
from blueprints.settings import settings_bp

__all__ = [
    'app', 'get_db', 'close_db', 'init_db', 'DB_PATH', 'BASE_DIR', 'DATA_DIR', 'UPLOAD_DIR',
    'TURSO_URL', 'TURSO_AUTH_TOKEN', 'DEFAULT_AUTO_TRACK_KEY', 'AUTO_TRACK_KEY', 'is_valid_api_key',
    'get_auto_track_key', 'get_active_llm_provider', 'get_app_password', 'bump_data_version',
    'get_data_version', 'get_latest_event', 'get_categories', 'get_current_user_id',
    'init_user_default_categories', 'seed_learning_samples', 'get_user_settings', 'DATA_VERSION',
    'LATEST_EVENT', 'USER_DATA_VERSIONS', 'USER_LATEST_EVENTS', 'is_ajax_request', 'money_filter',
    'date_filter', 'shift_month', 'get_savings_breakdown', 'get_category_budget_status',
    'check_and_record_budget_alerts', 'generate_due_recurring', 'get_billing_cycle_dates',
    'csrf', 'parse_auto_track_notification', 'parse_nlp_text', 'MERCHANT_CATEGORY_MAPPING',
    'EXPENSE_CATEGORY_KEYWORDS', 'call_llm_json', 'classify_notification_with_llm',
    'parse_nlp_with_llm', 'get_rapid_ocr', 'smart_orient_receipt_ocr', 'score_receipt_orientation',
    'parse_receipt_text_to_items', 'auth_bp', 'auto_track_bp', 'transactions_bp', 'liabilities_bp',
    'ACCOUNT_TYPES', 'ACCOUNT_CURRENCIES', 'subscriptions_bp', 'split_bill_bp', 'analytics_bp',
    'get_category_insights_data', 'settings_bp'
]

# 创建 Flask 应用实例
app = Flask(__name__)

# 稳定 Session 密钥机制（跨 Gunicorn Worker、跨重启、跨唤醒恒定一致）
_secret_key = os.environ.get('FLASK_SECRET_KEY') or os.environ.get('SECRET_KEY')
if not _secret_key:
    _key_file = os.path.join(DATA_DIR, '.flask_secret_key')
    try:
        if os.path.exists(_key_file):
            with open(_key_file, 'r', encoding='utf-8') as _kf:
                _secret_key = _kf.read().strip()
        if not _secret_key:
            if TURSO_URL and TURSO_AUTH_TOKEN:
                _secret_key = hmac.new(
                    str(TURSO_AUTH_TOKEN).encode('utf-8'),
                    f"ledger-app-session-seed:{TURSO_URL}".encode('utf-8'),
                    'sha256'
                ).hexdigest()
            else:
                _secret_key = secrets.token_hex(32)
            try:
                with open(_key_file, 'w', encoding='utf-8') as _kf:
                    _kf.write(_secret_key)
            except Exception:
                pass
    except Exception:
        _secret_key = 'ledger-app-prod-secret-stable-key-8f4b2c1e9a7d-stable-2026'
app.secret_key = _secret_key

app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)
app.config['TEMPLATES_AUTO_RELOAD'] = True

# 反向代理适配
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1, x_prefix=1)

# 初始化 CSRF 保护
csrf.init_app(app)

# 安全 Cookie 与性能设置
is_production = os.environ.get('RENDER') or os.environ.get('FLASK_ENV') == 'production' or os.environ.get('SESSION_COOKIE_SECURE', '0') == '1'
app.config['SESSION_COOKIE_SECURE'] = bool(is_production)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'
app.config['MAX_CONTENT_LENGTH'] = 20 * 1024 * 1024
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0


# ---------------------------------------------------------------------------
# 错误处理
# ---------------------------------------------------------------------------

@app.errorhandler(413)
def request_entity_too_large(error):
    if request.is_json:
        return jsonify({'ok': False, 'message': '上传文件大小超出限制（最大允许 20MB）'}), 413
    flash('上传文件大小超出限制（最大允许 20MB）', 'error')
    return redirect(request.referrer or url_for('index'))


@app.errorhandler(500)
def internal_server_error(error):
    """确保 /split-bill/ 路由的 500 错误以 JSON 形式返回，而不是 HTML 错误页"""
    import traceback
    traceback.print_exc()
    if request.path.startswith('/split-bill/'):
        return jsonify({'ok': False, 'message': f'服务器内部错误，请稍后重试。({str(error)})'}), 200
    return error


@app.errorhandler(CSRFError)
def handle_csrf_error(error):
    """拦截 CSRF 令牌过期或丢失错误，以友好方式提示/重定向"""
    if request.is_json or request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/') or request.path.startswith('/split-bill/'):
        return jsonify({
            'ok': False,
            'message': '页面会话已超时失效，请下拉刷新当前网页后重试。'
        }), 400
    flash('页面停顿时间较长或服务刚更新，会话已自动重置，请重试提交。', 'warning')
    target = request.referrer
    if not target or target.rstrip('/').endswith('/login') or not session.get('logged_in'):
        target = url_for('login')
    return redirect(target)


# ---------------------------------------------------------------------------
# 请求中间件与生命周期钩子
# ---------------------------------------------------------------------------

@app.after_request
def add_cache_control_headers(response):
    """对 HTML 页面与敏感路由强制不缓存，确保每次加载都能获取最新会话和有效状态"""
    if response.mimetype == 'text/html' or (request.path and request.path in ('/login', '/register')):
        response.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate, max-age=0'
        response.headers['Pragma'] = 'no-cache'
        response.headers['Expires'] = '0'
    return response


@app.context_processor
def inject_globals():
    is_hx = bool(request.headers.get('HX-Request'))
    uid = session.get('user_id')
    user_theme = 'system'
    table_density = 'comfortable'
    currency_symbol = 'RM'
    currency_code = 'MYR'
    date_format = 'YYYY-MM-DD'
    number_format = 'comma'
    user_timezone = 'Asia/Kuala_Lumpur'
    budget_start_day = 1
    nlp_confirm_required = 1

    if uid:
        try:
            settings = get_user_settings(uid)
            user_theme = settings.get('theme_mode', 'system')
            table_density = settings.get('table_density', 'comfortable')
            currency_symbol = settings.get('currency_symbol', 'RM')
            currency_code = settings.get('default_currency', 'MYR')
            date_format = settings.get('date_format', 'YYYY-MM-DD')
            number_format = settings.get('number_format', 'comma')
            user_timezone = settings.get('timezone', 'Asia/Kuala_Lumpur')
            budget_start_day = settings.get('budget_start_day', 1)
            nlp_confirm_required = settings.get('nlp_confirm_required', 1)

            g.current_currency_symbol = currency_symbol
            g.current_currency_code = currency_code
            g.current_date_format = date_format
            g.current_number_format = number_format
            g.current_timezone = user_timezone
            g.current_budget_start_day = budget_start_day
            g.current_nlp_confirm_required = nlp_confirm_required
        except Exception as e:
            logger.debug("Failed to load user settings in context processor: %s", e, exc_info=True)
    return {
        'layout': 'partial.html' if is_hx else 'base.html',
        'is_hx': is_hx,
        'data_version': DATA_VERSION,
        'current_user_theme': user_theme,
        'current_table_density': table_density,
        'current_user_currency': currency_symbol,
        'current_user_currency_code': currency_code,
        'current_date_format': date_format,
        'current_number_format': number_format,
        'current_user_timezone': user_timezone,
        'current_budget_start_day': budget_start_day,
        'current_nlp_confirm_required': nlp_confirm_required,
    }


@app.before_request
def require_login():
    ep = request.endpoint or ''
    ep_short = ep.split('.')[-1]
    exempt_endpoints = (
        'login', 'register', 'logout', 'static', 'health', 'api_realtime_check',
        'manifest', 'service_worker', 'offline_page', 'api_check_username',
        'download_apk', 'split_bill_ocr_upload', 'split_bill_parse_text',
        'split_bill_ocr_preprocess_preview'
    )
    # 允许静态资源、登录/注册/登出路由、健康检查、PWA 核心资源以及外部自动记账 Webhook 豁免 Session 检查
    if (
        ep in exempt_endpoints
        or ep_short in exempt_endpoints
        or request.path in ('/login', '/register', '/logout', '/health', '/api/realtime/check', '/manifest.json', '/sw.js', '/offline.html', '/api/check-username', '/download/apk', '/split-bill/ocr-upload', '/split-bill/parse-text', '/split-bill/ocr-preprocess-preview')
        or (request.path and request.path.startswith('/static/'))
    ):
        return
    if request.path.startswith('/api/'):
        req_key = request.headers.get('X-API-KEY')
        if not req_key and request.is_json:
            req_key = (request.get_json(silent=True) or {}).get('key')
        if is_valid_api_key(req_key):
            return
        if request.path.startswith('/api/auto-track'):
            return

    if not session.get('logged_in') or not session.get('user_id'):
        if request.headers.get('X-Requested-With') == 'InstantNav':
            return jsonify({'error': 'unauthorized', 'redirect': url_for('login')}), 401
        target_next = request.full_path if request.full_path and request.full_path != '/?' else '/'
        return redirect(url_for('login', next=target_next))


def url_build_error_handler(error, endpoint, values):
    """自动兼容未加蓝图前缀的裸 endpoint（例如 'login' -> 'auth.login'）"""
    if '.' not in endpoint:
        for bp_name in app.blueprints:
            candidate = f"{bp_name}.{endpoint}"
            if candidate in app.view_functions:
                return url_for(candidate, **values)
    raise error


app.url_build_error_handlers.append(url_build_error_handler)


@app.teardown_appcontext
def app_close_db(exception=None):
    close_db(exception)


# ---------------------------------------------------------------------------
# 模板过滤器
# ---------------------------------------------------------------------------

@app.template_filter('money')
def jinja_money_filter(value, symbol=None, number_format=None):
    return money_filter(value, symbol=symbol, number_format=number_format)


@app.template_filter('user_date')
def jinja_date_filter(value, fmt=None):
    return date_filter(value, fmt=fmt)


# ---------------------------------------------------------------------------
# 静态资源与 PWA 基础端点
# ---------------------------------------------------------------------------

@app.route('/health')
def health():
    return jsonify({'ok': True, 'status': 'online'})


@app.route('/manifest.json')
def manifest():
    return send_from_directory('static', 'manifest.json', mimetype='application/manifest+json')


@app.route('/sw.js')
def service_worker():
    response = make_response(send_from_directory('static', 'sw.js', mimetype='application/javascript'))
    response.headers['Service-Worker-Allowed'] = '/'
    response.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate'
    return response


@app.route('/offline.html')
def offline_page():
    return send_from_directory('static', 'offline.html', mimetype='text/html')


# ---------------------------------------------------------------------------
# 仪表盘（首页）与实时卡片局部刷新
# ---------------------------------------------------------------------------

@app.route('/', endpoint='index')
def index():
    user_id = get_current_user_id()
    generated = generate_due_recurring(user_id)
    if generated:
        flash(f'已自动生成本月固定收支 {generated} 条', 'success')

    month = request.args.get('month') or date.today().strftime('%Y-%m')
    db = get_db()

    user_settings = get_user_settings(user_id, db=db)
    budget_start_day = user_settings.get('budget_start_day', 1)
    start, end, cycle_range_label = get_billing_cycle_dates(month, budget_start_day)

    rows = db.execute(
        'SELECT type, group_name, category, amount, COALESCE(from_savings, 0) as from_savings FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ?',
        (user_id, start, end)
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    regular_expense = sum(r['amount'] for r in rows if r['type'] == 'expense' and not r['from_savings'])
    savings_expense = sum(r['amount'] for r in rows if r['type'] == 'expense' and r['from_savings'])
    month_savings_in = sum(r['amount'] for r in rows if r['type'] == 'savings')

    # 本月净结余：收入 - 日常支出 - 存入储蓄（从储蓄扣除的支出不扣当月结余）
    balance = total_income - regular_expense - month_savings_in

    # 储蓄资金池明细与累计总储蓄
    savings_pool_by_category, total_savings_pool = get_savings_breakdown(db, user_id)

    income_group = {'main': 0.0, 'side': 0.0}
    expense_by_category = {}
    for r in rows:
        if r['type'] == 'income':
            gname = r['group_name'] or 'main'
            income_group[gname] = income_group.get(gname, 0.0) + r['amount']
        elif r['type'] == 'expense':
            c = r['category'] or '其他'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']

    # 支出分类按金额从高到低排序
    expense_by_category = dict(sorted(expense_by_category.items(), key=lambda x: x[1], reverse=True))

    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    budget_status = get_category_budget_status(db, user_id, month, start_day=budget_start_day)
    accounts = db.execute(
        "SELECT id, name, currency, type FROM accounts WHERE user_id = ? ORDER BY type, name",
        (str(user_id),)
    ).fetchall()

    return render_template(
        'index.html',
        month=month,
        cycle_range_label=cycle_range_label,
        budget_start_day=budget_start_day,
        budget_status=budget_status,
        accounts=accounts,
        prev_month=shift_month(month, -1),
        next_month=shift_month(month, 1),
        total_income=total_income,
        total_expense=total_expense,
        total_savings=month_savings_in,
        total_savings_pool=total_savings_pool,
        month_savings_in=month_savings_in,
        regular_expense=regular_expense,
        savings_expense=savings_expense,
        balance=balance,
        income_group=income_group,
        expense_by_category=expense_by_category,
        savings_by_category=savings_pool_by_category,
        savings_pool_by_category=savings_pool_by_category,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        today=date.today().isoformat(),
    )


@app.route('/api/realtime/check', endpoint='api_realtime_check')
def api_realtime_check():
    user_id = get_current_user_id()
    client_v = request.args.get('v', type=int)
    db = get_db()
    current_v = get_data_version(user_id, db=db)
    latest_evt = get_latest_event(user_id, db=db)
    has_update = False
    if client_v is not None and client_v < current_v:
        has_update = True
    return jsonify({
        'ok': True,
        'version': current_v,
        'has_update': has_update,
        'event': latest_evt if has_update else None
    })


@app.route('/partial/dashboard-cards', endpoint='partial_dashboard_cards')
def partial_dashboard_cards():
    user_id = get_current_user_id()
    month = request.args.get('month') or date.today().strftime('%Y-%m')
    db = get_db()
    year, mon = map(int, month.split('-'))
    last_day = monthrange(year, mon)[1]
    start = f'{month}-01'
    end = f'{month}-{last_day:02d}'

    rows = db.execute(
        'SELECT type, group_name, category, amount, COALESCE(from_savings, 0) as from_savings FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ?',
        (user_id, start, end)
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    regular_expense = sum(r['amount'] for r in rows if r['type'] == 'expense' and not r['from_savings'])
    savings_expense = sum(r['amount'] for r in rows if r['type'] == 'expense' and r['from_savings'])
    month_savings_in = sum(r['amount'] for r in rows if r['type'] == 'savings')

    balance = total_income - regular_expense - month_savings_in
    savings_pool_by_category, total_savings_pool = get_savings_breakdown(db, user_id)

    return render_template(
        'partials/dashboard_cards.html',
        total_income=total_income,
        total_expense=total_expense,
        total_savings=month_savings_in,
        total_savings_pool=total_savings_pool,
        month_savings_in=month_savings_in,
        regular_expense=regular_expense,
        savings_expense=savings_expense,
        balance=balance,
        savings_by_category=savings_pool_by_category,
    )


@app.route('/partial/dashboard-budget', endpoint='partial_dashboard_budget')
def partial_dashboard_budget():
    user_id = get_current_user_id()
    month = request.args.get('month') or date.today().strftime('%Y-%m')
    db = get_db()
    budget_status = get_category_budget_status(db, user_id, month)
    return render_template('partials/dashboard_budget.html', budget_status=budget_status)


# ---------------------------------------------------------------------------
# 注册所有模块化业务蓝图
# ---------------------------------------------------------------------------

app.register_blueprint(auth_bp)
app.register_blueprint(auto_track_bp)
app.register_blueprint(transactions_bp)
app.register_blueprint(liabilities_bp)
app.register_blueprint(subscriptions_bp)
app.register_blueprint(split_bill_bp)
app.register_blueprint(analytics_bp)
app.register_blueprint(settings_bp)

# 确保启动或导入时数据库初始化
init_db()

if __name__ == '__main__':
    for fn in os.listdir(UPLOAD_DIR):
        try:
            os.remove(os.path.join(UPLOAD_DIR, fn))
        except OSError:
            pass
    flask_debug = os.environ.get('FLASK_DEBUG', '0').lower() in ('1', 'true')
    port = int(os.environ.get('PORT', 5000))
    app.run(debug=flask_debug, host='0.0.0.0', port=port)
