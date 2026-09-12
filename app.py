import os
import re
import sys
import json
import uuid
import secrets
import sqlite3
import requests
from calendar import monthrange
from datetime import datetime, date
from decimal import Decimal

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

import pandas as pd
from werkzeug.security import generate_password_hash, check_password_hash
from flask import Flask, g, request, redirect, url_for, render_template, flash, jsonify, session, send_from_directory, make_response, has_request_context

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# 数据存储目录
DATA_DIR = os.environ.get('DATA_DIR', BASE_DIR)
os.makedirs(DATA_DIR, exist_ok=True)
DB_PATH = os.path.join(DATA_DIR, 'ledger.db')
UPLOAD_DIR = os.path.join(DATA_DIR, 'uploads')
os.makedirs(UPLOAD_DIR, exist_ok=True)

import turso_db
from liabilities_tracker import (
    generate_amortization_schedule,
    get_monthly_cashflow_events,
    sync_installments_to_monthly_statement
)
from subscription_tracker import (
    BillingCycle,
    SubscriptionStatus,
    AlertLevel,
    Subscription,
    calculateAnnualAndMonthlyBurnRate,
    rollToNextBillingDate,
    getUpcomingRenewalsWithAlerts,
    buildSubscriptionDashboardSummary,
    default_mock_exchange_rate_provider
)

# Turso 云数据库凭证 (从环境变量读取，fail-fast)
TURSO_URL = turso_db.TURSO_URL
TURSO_AUTH_TOKEN = turso_db.TURSO_AUTH_TOKEN

from flask_wtf.csrf import CSRFProtect, CSRFError
from datetime import timedelta

app = Flask(__name__)

# 稳定 Session 密钥机制（保证跨 Gunicorn Worker、跨重启、跨唤醒密钥 100% 恒定一致，杜绝会话漂移）
app.secret_key = (
    os.environ.get('FLASK_SECRET_KEY')
    or os.environ.get('SECRET_KEY')
    or 'ledger-app-prod-secret-stable-key-8f4b2c1e9a7d-stable-2026'
)
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)
app.config['TEMPLATES_AUTO_RELOAD'] = True

# 支持 Render 等反向代理正确识别 https 协议与客户端 IP
from werkzeug.middleware.proxy_fix import ProxyFix
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1, x_prefix=1)

# CSRF 保护 (全局启用，自动化 Webhook 使用 @csrf.exempt 排除)
csrf = CSRFProtect(app)

# 安全 Session Cookie 标志 (生产环境/HTTPS 开启 Secure)
is_production = os.environ.get('RENDER') or os.environ.get('FLASK_ENV') == 'production' or os.environ.get('SESSION_COOKIE_SECURE', '0') == '1'
app.config['SESSION_COOKIE_SECURE'] = bool(is_production)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'

# 限制上传文件大小最大 20MB (避免高像素手机照片超出限制)
app.config['MAX_CONTENT_LENGTH'] = 20 * 1024 * 1024

# 自动记账 API 鉴权密钥 (支持用户指定 key、环境变量及数据库配置；不再有任何硬编码保底值)
AUTO_TRACK_KEY = os.environ.get('AUTO_TRACK_KEY')
AUTO_TRACK_DEBUG_LOG = os.environ.get('AUTO_TRACK_DEBUG_LOG', '0') == '1'

# 获取有效的 AUTO_TRACK_KEY（优先环境变量，次选数据库 system_settings；两者都没设置就回传 None，
# 代表目前没有配置任何 key —— 这种情况下 is_valid_api_key() 一律拒绝，不会有任何后备值可用）
def get_auto_track_key():
    if AUTO_TRACK_KEY and AUTO_TRACK_KEY.strip():
        return AUTO_TRACK_KEY.strip()
    try:
        db = get_db()
        row = db.execute("SELECT value FROM system_settings WHERE key='auto_track_key'").fetchone()
        if row and row['value'] and str(row['value']).strip():
            return str(row['value']).strip()
    except Exception:
        pass
    return None


def is_valid_api_key(req_key):
    """检验 API Key 是否合法。只认目前实际配置的那一把 key，
    不接受任何写死在代码里的默认值或旧版曾经泄漏过的 key（那些已经被视为永久作废）。"""
    if not req_key:
        return False
    effective = get_auto_track_key()
    if not effective:
        # 完全没有配置任何 key 时，拒绝所有请求，不回退到任何默认值
        return False
    return str(req_key).strip() == effective

# LLM 智能服务配置 (优先 Google Gemini，其次 OpenAI/DeepSeek，再回退本地 Ollama 与快速规则引擎)
GEMINI_API_KEY = os.environ.get('GEMINI_API_KEY', '').strip()
GEMINI_MODEL = os.environ.get('GEMINI_MODEL', 'gemini-flash-lite-latest').strip()
GEMINI_FALLBACK_MODELS = ['gemini-flash-lite-latest', 'gemini-3.1-flash-lite', 'gemini-flash-latest', 'gemini-2.5-flash']
OPENAI_API_KEY = os.environ.get('OPENAI_API_KEY', '').strip()
OPENAI_BASE_URL = os.environ.get('OPENAI_BASE_URL', 'https://api.openai.com/v1').rstrip('/')
OPENAI_MODEL = os.environ.get('OPENAI_MODEL', 'gpt-4o-mini').strip()
DEEPSEEK_API_KEY = os.environ.get('DEEPSEEK_API_KEY', '').strip()
OLLAMA_URL = os.environ.get('OLLAMA_URL', 'http://localhost:11434').rstrip('/')
OLLAMA_MODEL = os.environ.get('OLLAMA_MODEL', 'qwen2.5').strip()
LLM_TIMEOUT = float(os.environ.get('LLM_TIMEOUT', '4.5'))


def get_active_llm_provider():
    """返回当前优先启用的 LLM 供应商名称与模型"""
    if GEMINI_API_KEY:
        return {'provider': 'gemini', 'name': f'Google Gemini ({GEMINI_MODEL})', 'available': True}
    if DEEPSEEK_API_KEY:
        return {'provider': 'deepseek', 'name': 'DeepSeek (deepseek-chat)', 'available': True}
    if OPENAI_API_KEY:
        return {'provider': 'openai', 'name': f'OpenAI ({OPENAI_MODEL})', 'available': True}
    return {'provider': 'ollama', 'name': f'Local Ollama ({OLLAMA_MODEL})', 'available': False}

# 单用户访问密码 (优先环境变量，次选数据库 system_settings；都没设置就回传 None，
# 不再有任何写死在代码里的保底密码)
def get_app_password():
    env_pw = os.environ.get('APP_PASSWORD')
    if env_pw:
        return env_pw
    try:
        db = get_db()
        row = db.execute("SELECT value FROM system_settings WHERE key='app_password'").fetchone()
        if row and row['value']:
            return row['value']
    except Exception:
        pass
    return None

APP_PASSWORD = os.environ.get('APP_PASSWORD')

# ---------------------------------------------------------------------------
# 实时同步与局部更新状态版本控制
# ---------------------------------------------------------------------------
import time

DATA_VERSION = int(time.time() * 1000)
LATEST_EVENT = None
USER_DATA_VERSIONS = {}
USER_LATEST_EVENTS = {}

def bump_data_version(event_type='update', data=None, user_id=None):
    global DATA_VERSION, LATEST_EVENT
    DATA_VERSION = int(time.time() * 1000)
    LATEST_EVENT = {
        'version': DATA_VERSION,
        'type': event_type,
        'timestamp': datetime.now().isoformat(),
        'data': data or {}
    }
    if not user_id and data and isinstance(data, dict):
        user_id = data.get('user_id')
    if not user_id and has_request_context():
        user_id = session.get('user_id')
    if user_id:
        USER_DATA_VERSIONS[user_id] = DATA_VERSION
        USER_LATEST_EVENTS[user_id] = LATEST_EVENT

# 本地单人使用的开发服务器：关闭静态文件缓存，避免浏览器缓存旧的 CSS/JS 导致改动看不到
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0


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
    """拦截 CSRF 令牌过期或丢失错误，以友好方式提示/重定向，不再展示原生生硬的 400 Bad Request 页面"""
    if request.is_json or request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/') or request.path.startswith('/split-bill/'):
        return jsonify({
            'ok': False,
            'message': '页面会话已超时失效，请下拉刷新当前网页后重试。'
        }), 400
    flash('页面停顿时间较长或服务刚更新，会话已自动重置，请重试提交。', 'warning')
    return redirect(request.referrer or url_for('index'))


@app.after_request
def add_cache_control_headers(response):
    """对 HTML 页面与敏感路由强制不缓存，确保每次加载都能获取最新会话和有效状态"""
    if response.mimetype == 'text/html' or (request.path and request.path in ('/login', '/register')):
        response.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate, max-age=0'
        response.headers['Pragma'] = 'no-cache'
        response.headers['Expires'] = '0'
    return response


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


def is_ajax_request():
    return (
        request.is_json
        or request.headers.get('X-Requested-With') == 'XMLHttpRequest'
        or 'application/json' in request.headers.get('Accept', '')
    )


@app.context_processor
def inject_globals():
    is_hx = bool(request.headers.get('HX-Request'))
    return {
        'layout': 'partial.html' if is_hx else 'base.html',
        'is_hx': is_hx,
        'data_version': DATA_VERSION
    }


def get_current_user_id():
    uid = session.get('user_id')
    if uid:
        return uid
    try:
        db = get_db()
        admin = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        if admin:
            return admin['id']
        first = db.execute("SELECT id FROM users ORDER BY created_at ASC LIMIT 1").fetchone()
        if first:
            return first['id']
    except Exception:
        pass
    return None


@app.before_request
def require_login():
    # 允许静态资源、登录/注册/登出路由、健康检查、PWA 核心资源以及外部自动记账 Webhook 豁免 Session 检查
    if (
        request.endpoint in ('login', 'register', 'logout', 'static', 'health', 'api_realtime_check', 'manifest', 'service_worker', 'offline_page', 'api_check_username', 'download_apk', 'split_bill_ocr_upload', 'split_bill_parse_text')
        or request.path in ('/login', '/register', '/logout', '/health', '/api/realtime/check', '/manifest.json', '/sw.js', '/offline.html', '/api/check-username', '/download/apk', '/split-bill/ocr-upload', '/split-bill/parse-text')
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


@app.route('/api/check-username')
def api_check_username():
    username = (request.args.get('username') or '').strip()
    if not username:
        return jsonify({'ok': False, 'available': False, 'message': '请输入用户名'})
    if len(username) < 3:
        return jsonify({'ok': False, 'available': False, 'message': f'用户名太短，至少需 3 个字符（当前 {len(username)} 个）'})
    if len(username) > 30:
        return jsonify({'ok': False, 'available': False, 'message': '用户名不能超过 30 个字符'})
    if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
        return jsonify({'ok': False, 'available': False, 'message': '仅支持中文、英文字母、数字、下划线及连字符'})
    db = get_db()
    existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
    if existing:
        return jsonify({'ok': True, 'available': False, 'message': '该用户名已被占用，请直接登录或更换'})
    return jsonify({'ok': True, 'available': True, 'message': '该用户名可用 ✓'})


@app.route('/register', methods=['GET', 'POST'])
def register():
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    if request.method == 'POST':
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password', '')
        confirm_password = request.form.get('confirm_password', '')

        if not username:
            flash('用户名不能为空', 'error')
            return render_template('register.html')
        if len(username) < 3 or len(username) > 30:
            flash('用户名长度需在 3 到 30 个字符之间', 'error')
            return render_template('register.html')
        if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
            flash('用户名仅支持中文、字母、数字及下划线', 'error')
            return render_template('register.html')
        if not password or len(password) < 6:
            flash('密码长度至少需要 6 个字符', 'error')
            return render_template('register.html')
        if not re.search(r'[A-Z]', password):
            flash('密码需包含至少一个大写字母 (A-Z)', 'error')
            return render_template('register.html')
        if not re.search(r'[a-z]', password):
            flash('密码需包含至少一个小写字母 (a-z)', 'error')
            return render_template('register.html')
        if not re.search(r'[0-9]', password):
            flash('密码需包含至少一个数字 (0-9)', 'error')
            return render_template('register.html')
        if not re.search(r'[^a-zA-Z0-9]', password):
            flash('密码需包含至少一个特殊符号（如 !@#$%^&* 等）', 'error')
            return render_template('register.html')
        if password != confirm_password:
            flash('两次输入的密码不一致', 'error')
            return render_template('register.html')

        db = get_db()
        existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
        if existing:
            flash('该用户名已被注册，请直接登录或换一个用户名', 'error')
            return render_template('register.html')

        user_id = str(uuid.uuid4())
        pw_hash = generate_password_hash(password)
        now_str = datetime.now().isoformat()
        db.execute(
            'INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)',
            (user_id, username, pw_hash, now_str)
        )
        db.commit()

        # 为新注册账号初始化专属独立的默认分类集
        init_user_default_categories(db, user_id)

        session.permanent = True
        session['logged_in'] = True
        session['user_id'] = user_id
        session['username'] = username
        flash(f'注册成功，欢迎使用多账本个人财务系统，{username}！', 'success')
        return redirect(url_for('index'))

    return render_template('register.html')


@app.route('/login', methods=['GET', 'POST'])
def login():
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    next_url = request.args.get('next') or request.form.get('next') or url_for('index')
    if not next_url.startswith('/') or next_url.startswith('//'):
        next_url = url_for('index')

    if request.method == 'POST':
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password', '')

        if not username or not password:
            flash('请输入用户名和密码', 'error')
            return render_template('login.html', next=next_url, username=username), 400

        db = get_db()
        user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()

        if user and check_password_hash(user['password_hash'], password):
            session.permanent = True
            session['logged_in'] = True
            session['user_id'] = user['id']
            session['username'] = user['username']
            flash(f'欢迎回来，{user["username"]}！', 'success')
            return redirect(next_url)
        elif username == 'admin' and password == get_app_password():
            if user:
                admin_id = user['id']
            else:
                admin_id = str(uuid.uuid4())
                db.execute(
                    'INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)',
                    (admin_id, 'admin', generate_password_hash(password), datetime.now().isoformat())
                )
                db.commit()
            session.permanent = True
            session['logged_in'] = True
            session['user_id'] = admin_id
            session['username'] = 'admin'
            flash('登录成功！', 'success')
            return redirect(next_url)
        else:
            flash('用户名或密码错误，请重试', 'error')
            return render_template('login.html', next=next_url, username=username), 401

    return render_template('login.html', next=next_url)


@app.route('/logout', methods=['GET', 'POST'])
def logout():
    session.clear()
    flash('您已成功退出登录。', 'success')
    return redirect(url_for('login'))



@app.template_filter('money')
def money_filter(value):
    """格式化为林吉特金额，如 RM 3,900.00"""
    try:
        value = float(value)
    except (TypeError, ValueError):
        value = 0.0
    return f"RM {value:,.2f}"


# ---------------------------------------------------------------------------
# 数据库
# ---------------------------------------------------------------------------

def get_db():
    if 'db' not in g:
        if TURSO_URL and TURSO_AUTH_TOKEN:
            g.db = turso_db.TursoConnection(TURSO_URL, TURSO_AUTH_TOKEN)
        else:
            g.db = sqlite3.connect(DB_PATH)
            g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exception=None):
    db = g.pop('db', None)
    if db is not None:
        db.close()


def init_user_default_categories(db, user_id):
    row = db.execute("SELECT COUNT(*) FROM categories WHERE user_id=?", (user_id,)).fetchone()
    count = row[0] if row else 0
    if count == 0:
        defaults = [
            (user_id, 'income', 'main', '工资'),
            (user_id, 'income', 'main', '奖金'),
            (user_id, 'income', 'side', '自由职业'),
            (user_id, 'income', 'side', '兼职'),
            (user_id, 'income', 'side', '投资'),
            (user_id, 'expense', None, '餐饮'),
            (user_id, 'expense', None, '交通'),
            (user_id, 'expense', None, '房租'),
            (user_id, 'expense', None, '购物'),
            (user_id, 'expense', None, '娱乐'),
            (user_id, 'expense', None, '医疗'),
            (user_id, 'expense', None, '通讯'),
            (user_id, 'expense', None, '其他'),
            (user_id, 'savings', None, '定期存款'),
            (user_id, 'savings', None, '应急基金'),
            (user_id, 'savings', None, '投资理财'),
            (user_id, 'savings', None, '心愿基金'),
        ]
        db.executemany('INSERT INTO categories (user_id, type, group_name, name) VALUES (?,?,?,?)', defaults)
        db.commit()


DEFAULT_LEARNING_SAMPLES = [
    {
        'text': 'Double Cashback! Apply & Get additional RM50 Cash Back ... Not a PB Credit Cardmember yet? Apply online for PB Credit Card to get a 4-in-1 Barry Smith Luggage Set or RM300 Cash Back...',
        'label_type': 'promo',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': 'Public Bank',
        'sample_category': None,
        'notes': '银行信用卡开卡活动营销广告，非动账通知'
    },
    {
        'text': 'Exclusive for you! Need extra cash? Apply for Maybank Personal Loan from 5.88% p.a. and get instant approval today. T&Cs apply.',
        'label_type': 'promo',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': 'Maybank',
        'sample_category': None,
        'notes': '银行个人贷款推销广告'
    },
    {
        'text': "Touch 'n Go eWallet: Stand a chance to win a Proton eMas 7 and RM50,000 cash prizes! Spend RM10 with DuitNow QR to earn entries. Promo ends 30 Sept.",
        'label_type': 'promo',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': "Touch 'n Go",
        'sample_category': None,
        'notes': '抽奖活动与消费达标竞赛宣传，非实际消费'
    },
    {
        'text': 'PB Alert: Your OTP is 582910 for First-Time Login. Do not reveal this OTP to anyone, including bank staff.',
        'label_type': 'otp_notice',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': 'Public Bank',
        'sample_category': None,
        'notes': '一次性登录验证码 / 安全提醒'
    },
    {
        'text': "Touch 'n Go eWallet: You have successfully paid RM 15.50 to FamilyMart SS15 on 10/09/2026. Ref: TNG8892182. Claim your cashback voucher now!",
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 15.50,
        'sample_merchant': 'FamilyMart SS15',
        'sample_category': '餐饮',
        'notes': '便利店扫码消费，末尾带营销卡券奖励，应判定为真实消费'
    },
    {
        'text': 'PB Payment Alert: You have paid RM 45.00 to PETRONAS SOLARIS on 10/09/2026 via debit card. Ref: PB491823.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 45.00,
        'sample_merchant': 'PETRONAS SOLARIS',
        'sample_category': '交通',
        'notes': '油站加油消费支出'
    },
    {
        'text': 'Payment of RM 28.00 to GrabCar completed via GrabPay on 10 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 28.00,
        'sample_merchant': 'GrabCar',
        'sample_category': '交通',
        'notes': '网约车打车出行支出'
    },
    {
        'text': 'MAE: RM 36.40 debited for payment at 99 SPEEDMART - 1482 on 10 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 36.40,
        'sample_merchant': '99 SPEEDMART',
        'sample_category': '购物',
        'notes': '连锁超市日常用品消费支出'
    },
    {
        'text': 'Transfer Successful. RM 120.00 has been successfully transferred to Tan Ah Kow via DuitNow Transfer. Ref: 20260910001.',
        'label_type': 'expense_transfer',
        'is_real_transaction': 1,
        'sample_amount': 120.00,
        'sample_merchant': 'Tan Ah Kow',
        'sample_category': '其他',
        'notes': '向他人转账付款 / 支出'
    },
    {
        'text': 'DuitNow Transfer: You have received RM 250.00 from Wong Mei Ling on 10 Sep 2026. Ref: DN982187.',
        'label_type': 'income_transfer',
        'is_real_transaction': 1,
        'sample_amount': 250.00,
        'sample_merchant': 'Wong Mei Ling',
        'sample_category': '其他',
        'notes': '收到他人 DuitNow 转账进账，记为收入'
    },
    {
        'text': 'Salary Credit: RM 8,500.00 credited into your account from ABC TECH SDN BHD on 28/08/2026. Salary payment.',
        'label_type': 'income_transfer',
        'is_real_transaction': 1,
        'sample_amount': 8500.00,
        'sample_merchant': 'ABC TECH SDN BHD',
        'sample_category': '工资',
        'notes': '公司薪资代发，主业收入入账'
    },
    {
        'text': 'JomPAY: RM 142.50 paid to Tenaga Nasional Berhad (TNB) via Maybank MAE on 05 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 142.50,
        'sample_merchant': 'Tenaga Nasional Berhad (TNB)',
        'sample_category': '通讯',
        'notes': '水电缴费支出'
    }
]


def seed_learning_samples(db):
    """将默认预设语料样本灌入数据库"""
    now = datetime.now().isoformat()
    for s in DEFAULT_LEARNING_SAMPLES:
        db.execute('''
            INSERT INTO llm_learning_samples (user_id, text, label_type, is_real_transaction, sample_amount, sample_merchant, sample_category, notes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            None,
            s['text'],
            s['label_type'],
            s['is_real_transaction'],
            s['sample_amount'],
            s['sample_merchant'],
            s['sample_category'],
            s['notes'],
            now
        ))
    db.commit()


def init_db():
    if TURSO_URL and TURSO_AUTH_TOKEN:
        db = turso_db.TursoConnection(TURSO_URL, TURSO_AUTH_TOKEN)
    else:
        db = sqlite3.connect(DB_PATH)
        db.row_factory = sqlite3.Row

    # 1. 用户表（UUID 主键，确保安全性和唯一性）
    db.execute('''
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
    );
    ''')
    db.commit()

    # 确保默认 admin 用户存在，分配独立 UUID
    admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
    if not admin_row:
        admin_id = str(uuid.uuid4())
        admin_pw = get_app_password()
        if not admin_pw:
            # 没有设置 APP_PASSWORD/system_settings，就生成一个随机的一次性密码，
            # 而不是用任何写死的默认密码 —— 密码只会印一次在 server log 里，
            # 你要用这个默认 admin 账号登录的话，去 log 里找这一行复制密码，
            # 登录后建议尽快改掉或改用 /register 建一个自己的账号。
            admin_pw = secrets.token_urlsafe(16)
            app.logger.warning(
                "未设置 APP_PASSWORD，已为默认 admin 账号生成一次性随机密码（仅显示这一次）：%s",
                admin_pw
            )
        db.execute(
            "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (admin_id, 'admin', generate_password_hash(admin_pw), datetime.now().isoformat())
        )
        db.commit()
    else:
        admin_id = admin_row['id'] if (isinstance(admin_row, sqlite3.Row) or isinstance(admin_row, dict)) else admin_row[0]

    # 2. 检查 categories 表是否已有 user_id 字段及独立复合唯一约束 UNIQUE(user_id, type, group_name, name)
    try:
        col_names = [r[1] for r in db.execute("PRAGMA table_info(categories)").fetchall()]
    except Exception:
        col_names = []

    if not col_names:
        db.execute('''
        CREATE TABLE categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            type TEXT NOT NULL,
            group_name TEXT,
            name TEXT NOT NULL,
            UNIQUE(user_id, type, group_name, name)
        );
        ''')
        db.commit()
    elif 'user_id' not in col_names:
        # 进行安全迁移，重构为支持多用户独立分类且保留历史数据
        db.execute("ALTER TABLE categories RENAME TO categories_old")
        db.execute('''
        CREATE TABLE categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            type TEXT NOT NULL,
            group_name TEXT,
            name TEXT NOT NULL,
            UNIQUE(user_id, type, group_name, name)
        );
        ''')
        db.execute('''
        INSERT INTO categories (id, user_id, type, group_name, name)
        SELECT id, ?, type, group_name, name FROM categories_old
        ''', (admin_id,))
        db.execute("DROP TABLE categories_old")
        db.commit()

    # 3. 交易表与多用户支持
    db.execute('''
    CREATE TABLE IF NOT EXISTS transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT,
        date TEXT NOT NULL,
        type TEXT NOT NULL,
        group_name TEXT,
        category TEXT,
        amount REAL NOT NULL,
        note TEXT,
        source TEXT DEFAULT 'manual',
        created_at TEXT NOT NULL
    );
    ''')
    db.commit()

    try:
        tx_cols = [r[1] for r in db.execute("PRAGMA table_info(transactions)").fetchall()]
    except Exception:
        tx_cols = []
    if 'user_id' not in tx_cols:
        try:
            db.execute("ALTER TABLE transactions ADD COLUMN user_id TEXT")
            db.commit()
        except Exception:
            pass
    db.execute("UPDATE transactions SET user_id = ? WHERE user_id IS NULL OR user_id = ''", (admin_id,))
    db.commit()

    # 4. 固定收支表与多用户支持
    db.execute('''
    CREATE TABLE IF NOT EXISTS recurring_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT,
        type TEXT NOT NULL,
        group_name TEXT,
        category TEXT,
        amount REAL NOT NULL,
        note TEXT,
        day_of_month INTEGER NOT NULL,
        is_active INTEGER DEFAULT 1,
        last_generated_month TEXT,
        created_at TEXT NOT NULL
    );
    ''')
    db.commit()

    try:
        rec_cols = [r[1] for r in db.execute("PRAGMA table_info(recurring_rules)").fetchall()]
    except Exception:
        rec_cols = []
    if 'user_id' not in rec_cols:
        try:
            db.execute("ALTER TABLE recurring_rules ADD COLUMN user_id TEXT")
            db.commit()
        except Exception:
            pass
    db.execute("UPDATE recurring_rules SET user_id = ? WHERE user_id IS NULL OR user_id = ''", (admin_id,))
    db.commit()

    # 5. 商户-分类记忆表与系统配置表
    db.executescript('''
    CREATE TABLE IF NOT EXISTS merchant_category_overrides (
        merchant_note TEXT PRIMARY KEY,
        category TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS system_settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    ''')
    db.commit()

    try:
        db.execute("ALTER TABLE transactions ADD COLUMN from_savings INTEGER DEFAULT 0")
        db.commit()
    except Exception:
        pass

    try:
        db.execute("ALTER TABLE transactions ADD COLUMN from_savings_category TEXT")
        db.commit()
    except Exception:
        pass

    # 索引优化
    try:
        db.execute("CREATE INDEX IF NOT EXISTS idx_transactions_user ON transactions(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_recurring_user ON recurring_rules(user_id)")
        db.commit()
    except Exception:
        pass

    # 6. LLM 学习样本表 (Few-Shot Datasheet)
    try:
        db.execute('''
        CREATE TABLE IF NOT EXISTS llm_learning_samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT,
            text TEXT NOT NULL,
            label_type TEXT NOT NULL,
            is_real_transaction INTEGER NOT NULL DEFAULT 0,
            sample_amount REAL,
            sample_merchant TEXT,
            sample_category TEXT,
            notes TEXT,
            created_at TEXT NOT NULL
        );
        ''')
        db.commit()

        sample_count = db.execute("SELECT COUNT(*) as cnt FROM llm_learning_samples").fetchone()
        cnt = sample_count['cnt'] if sample_count else 0
        if cnt == 0:
            seed_learning_samples(db)
    except Exception as e:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[INIT_DB] llm_learning_samples init error: {e}")

    # 7. 分类预算上限与超支提醒去重表
    db.executescript('''
    CREATE TABLE IF NOT EXISTS category_budgets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        category TEXT NOT NULL,
        monthly_limit REAL NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        UNIQUE(user_id, category)
    );

    CREATE TABLE IF NOT EXISTS category_budget_alerts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        category TEXT NOT NULL,
        month TEXT NOT NULL,
        threshold INTEGER NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(user_id, category, month, threshold)
    );

    -- 8. 负债、信用卡与分期付款追踪表
    CREATE TABLE IF NOT EXISTS accounts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        name TEXT NOT NULL,
        type TEXT NOT NULL,
        credit_limit REAL DEFAULT 0.0,
        statement_day INTEGER,
        due_day INTEGER,
        grace_period_days INTEGER DEFAULT 20,
        currency TEXT DEFAULT 'RM',
        is_active INTEGER DEFAULT 1,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS installments (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        account_id INTEGER,
        title TEXT NOT NULL,
        total_amount REAL NOT NULL,
        tenure_months INTEGER NOT NULL,
        paid_periods INTEGER DEFAULT 0,
        monthly_amount REAL NOT NULL,
        first_due_date TEXT NOT NULL,
        status TEXT DEFAULT 'active',
        last_synced_month TEXT,
        note TEXT,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS loans (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        debit_account_id INTEGER,
        title TEXT NOT NULL,
        loan_amount REAL NOT NULL,
        remaining_balance REAL NOT NULL,
        tenure_months INTEGER NOT NULL,
        paid_periods INTEGER DEFAULT 0,
        annual_interest_rate REAL NOT NULL,
        method TEXT NOT NULL,
        monthly_payment REAL NOT NULL,
        due_day INTEGER NOT NULL,
        start_date TEXT NOT NULL,
        status TEXT DEFAULT 'active',
        last_synced_month TEXT,
        note TEXT,
        created_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_installments_user_status ON installments(user_id, status);
    CREATE INDEX IF NOT EXISTS idx_loans_user_status ON loans(user_id, status);

    -- 9. 订阅服务与续费提醒表
    CREATE TABLE IF NOT EXISTS subscriptions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        name TEXT NOT NULL,
        category TEXT NOT NULL,
        billing_cycle TEXT NOT NULL,
        cost REAL NOT NULL,
        currency TEXT NOT NULL DEFAULT 'MYR',
        auto_renew INTEGER NOT NULL DEFAULT 1,
        start_date TEXT NOT NULL,
        next_billing_date TEXT NOT NULL,
        payment_method_id INTEGER,
        status TEXT NOT NULL DEFAULT 'ACTIVE',
        cancellation_reminder_days INTEGER NOT NULL DEFAULT 3,
        is_trial INTEGER NOT NULL DEFAULT 0,
        trial_end_date TEXT,
        target_to_cancel INTEGER NOT NULL DEFAULT 0,
        anchor_day INTEGER,
        note TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_subscriptions_user_status ON subscriptions(user_id, status);
    CREATE INDEX IF NOT EXISTS idx_subscriptions_next_billing ON subscriptions(status, next_billing_date);
    ''')
    db.commit()

    # 确保 admin 用户具备默认分类
    init_user_default_categories(db, admin_id)
    db.close()


def get_categories(db, type_, group_name, user_id=None):
    if not user_id:
        user_id = get_current_user_id()
    if type_ in ('expense', 'savings'):
        rows = db.execute('SELECT name FROM categories WHERE user_id=? AND type=? ORDER BY id', (user_id, type_)).fetchall()
    else:
        rows = db.execute(
            'SELECT name FROM categories WHERE user_id=? AND type=? AND group_name=? ORDER BY id',
            (user_id, type_, group_name)
        ).fetchall()
    return [r['name'] for r in rows]


def get_savings_breakdown(db, user_id=None):
    """
    计算当前用户所有历史储蓄分类的累计结余（储蓄资金池）：
    各分类累计存入 - 从该分类扣除的历史支出
    返回: (savings_pool_by_category: dict, total_savings_pool: float)
    """
    if not user_id:
        user_id = get_current_user_id()
    in_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id=? AND type='savings' GROUP BY category",
        (user_id,)
    ).fetchall()
    savings_in = {}
    for r in in_rows:
        cat = r['category'] or '储蓄'
        savings_in[cat] = savings_in.get(cat, 0.0) + float(r['total'] or 0.0)

    out_rows = db.execute(
        "SELECT COALESCE(NULLIF(from_savings_category, ''), category, '其他') as scat, SUM(amount) as total "
        "FROM transactions WHERE user_id=? AND type='expense' AND COALESCE(from_savings, 0)=1 GROUP BY scat",
        (user_id,)
    ).fetchall()
    savings_out = {}
    for r in out_rows:
        scat = r['scat'] or '其他'
        savings_out[scat] = savings_out.get(scat, 0.0) + float(r['total'] or 0.0)

    configured_cats = [c['name'] for c in db.execute("SELECT name FROM categories WHERE user_id=? AND type='savings'", (user_id,)).fetchall()]
    all_cats = list(dict.fromkeys(list(savings_in.keys()) + list(savings_out.keys()) + configured_cats))

    savings_pool = {}
    for c in all_cats:
        c_in = savings_in.get(c, 0.0)
        c_out = savings_out.get(c, 0.0)
        bal = c_in - c_out
        if c_in > 0 or c_out > 0:
            savings_pool[c] = round(bal, 2)

    sorted_pool = dict(sorted(savings_pool.items(), key=lambda item: item[1], reverse=True))

    all_in_total = sum(savings_in.values())
    all_out_total = sum(savings_out.values())
    total_savings_pool = max(all_in_total - all_out_total, 0.0)

    return sorted_pool, round(total_savings_pool, 2)


def get_category_budget_status(db, user_id=None, month=None):
    """
    返回当前用户每个已设置月度预算的支出分类的花费进度：
    [{category, limit, spent, remaining, pct, level}], 按 pct 从高到低排序。
    level: 'over' (>=100%) / 'warn' (>=70%) / 'ok'
    """
    if not user_id:
        user_id = get_current_user_id()
    if not month:
        month = date.today().strftime('%Y-%m')

    budgets = db.execute(
        'SELECT category, monthly_limit FROM category_budgets WHERE user_id = ?', (user_id,)
    ).fetchall()
    if not budgets:
        return []

    year, mon = map(int, month.split('-'))
    last_day = monthrange(year, mon)[1]
    start = f'{month}-01'
    end = f'{month}-{last_day:02d}'

    spent_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions "
        "WHERE user_id = ? AND type = 'expense' AND date BETWEEN ? AND ? GROUP BY category",
        (user_id, start, end)
    ).fetchall()
    spent_by_category = {(r['category'] or '其他'): float(r['total'] or 0.0) for r in spent_rows}

    result = []
    for b in budgets:
        cat = b['category']
        limit = float(b['monthly_limit'])
        spent = spent_by_category.get(cat, 0.0)
        pct = (spent / limit * 100.0) if limit > 0 else 0.0
        # 严格区分超支与满额：只有真正超过限额 (pct > 100) 才是 over (超支)
        # 刚好用满 100% 是 reached (已达上限/满额)，绝不是超支
        if pct > 100.0:
            level = 'over'
        elif pct == 100.0:
            level = 'reached'
        elif pct >= 70.0:
            level = 'warn'
        else:
            level = 'ok'
        result.append({
            'category': cat,
            'limit': round(limit, 2),
            'spent': round(spent, 2),
            'remaining': round(limit - spent, 2),
            'pct': round(pct, 1),
            'level': level,
        })
    result.sort(key=lambda x: x['pct'], reverse=True)
    return result


def check_and_record_budget_alerts(db, user_id, category, month=None):
    """
    检查某个分类本月花费是否新跨越了一个提醒阈值（70% / 100% / 150%）。
    每个 (用户, 分类, 月份, 阈值) 组合只提醒一次，避免同一档位反复弹出提醒。
    若确实跨越了新的阈值，返回该提醒的详情 dict 并落库；否则返回 None。
    """
    if not category:
        return None
    if not month:
        month = date.today().strftime('%Y-%m')

    budget = db.execute(
        'SELECT monthly_limit FROM category_budgets WHERE user_id = ? AND category = ?',
        (user_id, category)
    ).fetchone()
    if not budget:
        return None
    limit = float(budget['monthly_limit'])
    if limit <= 0:
        return None

    year, mon = map(int, month.split('-'))
    last_day = monthrange(year, mon)[1]
    start = f'{month}-01'
    end = f'{month}-{last_day:02d}'
    spent_row = db.execute(
        "SELECT SUM(amount) as total FROM transactions "
        "WHERE user_id = ? AND type = 'expense' AND category = ? AND date BETWEEN ? AND ?",
        (user_id, category, start, end)
    ).fetchone()
    spent = float(spent_row['total'] or 0.0) if spent_row else 0.0
    pct = spent / limit * 100.0

    crossed = None
    for threshold in (150, 100, 70):
        if pct >= threshold:
            crossed = threshold
            break
    if crossed is None:
        return None

    already_alerted = db.execute(
        'SELECT 1 FROM category_budget_alerts WHERE user_id = ? AND category = ? AND month = ? AND threshold = ?',
        (user_id, category, month, crossed)
    ).fetchone()
    if already_alerted:
        return None

    db.execute(
        'INSERT INTO category_budget_alerts (user_id, category, month, threshold, created_at) VALUES (?,?,?,?,?)',
        (user_id, category, month, crossed, datetime.now().isoformat())
    )
    db.commit()

    return {
        'category': category,
        'month': month,
        'threshold': crossed,
        'limit': round(limit, 2),
        'spent': round(spent, 2),
        'pct': round(pct, 1),
        'message': f'预算提醒：「{category}」本月已花 RM{spent:.2f} / RM{limit:.2f}（{round(pct)}%）',
    }


def shift_month(month_str, delta):
    y, m = map(int, month_str.split('-'))
    m += delta
    while m < 1:
        m += 12
        y -= 1
    while m > 12:
        m -= 12
        y += 1
    return f'{y:04d}-{m:02d}'


def generate_due_recurring(user_id=None):
    """按当前真实月份生成到期的固定收支记录（每个规则每月只生成一次）。"""
    db = get_db()
    current_month = date.today().strftime('%Y-%m')
    year, mon = map(int, current_month.split('-'))
    last_day = monthrange(year, mon)[1]

    if user_id:
        rules = db.execute('SELECT * FROM recurring_rules WHERE user_id=? AND is_active=1', (user_id,)).fetchall()
    else:
        rules = db.execute('SELECT * FROM recurring_rules WHERE is_active=1').fetchall()

    count = 0
    for r in rules:
        if r['last_generated_month'] == current_month:
            continue
        day = min(r['day_of_month'], last_day)
        tx_date = f'{current_month}-{day:02d}'
        r_uid = r['user_id'] if ('user_id' in r.keys() and r['user_id']) else (user_id or get_current_user_id())
        db.execute(
            'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
            'VALUES (?,?,?,?,?,?,?,?,?)',
            (r_uid, tx_date, r['type'], r['group_name'], r['category'], r['amount'], r['note'] or '',
             'recurring', datetime.now().isoformat())
        )
        db.execute('UPDATE recurring_rules SET last_generated_month=? WHERE id=?', (current_month, r['id']))
        count += 1
    db.commit()
    if count > 0:
        bump_data_version('recurring_generate', {'count': count})
    return count


# ---------------------------------------------------------------------------
# 自然语言快速记账解析
# ---------------------------------------------------------------------------

INCOME_MAIN_KEYWORDS = ['工资', '薪资', '发薪', '奖金', '年终奖', '绩效']
INCOME_SIDE_KEYWORDS = ['副业', '自由职业', '兼职', '稿费', '私活', '投资', '理财', '分红', '利息', '外快']

INCOME_CATEGORY_KEYWORDS = {
    '工资': ['工资', '薪资', '发薪'],
    '奖金': ['奖金', '年终奖', '绩效'],
    '自由职业': ['自由职业', '稿费', '私活', '写作', '设计费'],
    '兼职': ['兼职', '外快'],
    '投资': ['投资', '理财', '分红', '利息'],
}

EXPENSE_CATEGORY_KEYWORDS = {
    '餐饮': [
        '吃', '饭', '餐', '外卖', '奶茶', '咖啡', '早饭', '午饭', '晚饭', '夜宵', '零食',
        'kfc', 'mcd', 'mcdonald', 'starbucks', 'zus', 'chagee', 'tealive', 'subway',
        'familymart', 'family mart', 'rotiboy', 'baker', 'kopitiam', 'restaurant',
        'nasi', 'cafe', 'food', 'din', 'bbq', 'sushi', 'pizza'
    ],
    '交通': [
        '打车', '地铁', '公交', '高铁', '火车', '机票', '油费', '停车', '交通', '出行',
        'petronas', 'shell', 'caltex', 'bhp', 'petron', 'grab', 'touch n go', 'tng rfid',
        'parking', 'tng reload', 'toll', 'rapidkl', 'mrt', 'lrt', 'airasia'
    ],
    '房租': ['房租', '租金', '物业费', 'rental', 'maintenance fee'],
    '购物': [
        '购物', '淘宝', '京东', '衣服', '超市', 'shopee', 'lazada', 'watsons', 'guardian',
        'uniqlo', 'lotus', 'aeon', 'jaya grocer', 'village grocer', 'mr diy', 'econsave',
        '99 speedmart', 'speedmart', 'donki', 'supermarket', 'mall'
    ],
    '娱乐': ['电影', '游戏', '娱乐', '唱歌', '旅游', '景点', 'steam', 'netflix', 'spotify', 'cinema', 'gsc', 'tgv'],
    '医疗': ['医院', '看病', '医疗', '体检', '药', 'clinic', 'hospital', 'pharmacy', 'dental'],
    '通讯': ['话费', '流量', '网费', '通讯', 'maxis', 'digi', 'celcom', 'umobile', 'unifi', 'tnb', 'air selangor'],
}

MERCHANT_CATEGORY_MAPPING = {
    # 交通加油
    'petronas': '交通', 'shell': '交通', 'caltex': '交通', 'bhp': '交通', 'petron': '交通',
    'grab': '交通', 'touch n go': '交通', 'parking': '交通', 'toll': '交通', 'rapidkl': '交通',
    # 餐饮
    'familymart': '餐饮', 'family mart': '餐饮', 'kfc': '餐饮', 'mcdonald': '餐饮', 'mcd': '餐饮',
    'starbucks': '餐饮', 'zus': '餐饮', 'chagee': '餐饮', 'tealive': '餐饮', 'subway': '餐饮',
    'foodpanda': '餐饮', 'grabfood': '餐饮', 'kopitiam': '餐饮', 'restaurant': '餐饮', 'cafe': '餐饮',
    # 购物超市
    '99 speedmart': '购物', 'speedmart': '购物', 'lotus': '购物', 'aeon': '购物', 'watsons': '购物',
    'guardian': '购物', 'mr diy': '购物', 'shopee': '购物', 'lazada': '购物', 'jaya grocer': '购物',
    'village grocer': '购物', 'econsave': '购物', 'donki': '购物',
    # 水电通讯
    'tnb': '通讯', 'unifi': '通讯', 'maxis': '通讯', 'celcom': '通讯', 'digi': '通讯', 'umobile': '通讯'
}


def parse_auto_track_notification(raw_text):
    """
    解析来自 TnG eWallet / Maybank MAE / Public Bank (MyPB) / 银行短信 / 通知栏的文本。
    提取：金额 (RM)、商户名/接收方、时间、自动匹配分类。
    自动过滤：营销广告、信用卡/贷款推广、返现活动宣传、安全提醒、OTP/TAC验证码等非动账通知。
    """
    text = raw_text.strip()
    if not text:
        return None

    lower_text = text.lower()

    # 0. 强力过滤非动账类通知（营销推广、信用卡/贷款推销、返现活动宣传、抽奖、条款、OTP/TAC验证码、安全提醒等）
    PROMO_AND_AD_KEYWORDS = [
        'apply online', 'apply & get', 'apply for', 'apply now', 'apply today', 'application for',
        'cardmember yet', 'credit cardmember', 'not a pb', 'not a member', 'eligible for',
        'double cashback', 'cash back', 'stand a chance', 'lucky draw', 'win a', 'win up to',
        'contest', 'rewards point', 'free gift', 'luggage set', 'gift voucher', 'earn entries',
        't&cs apply', "t&c's apply", 'terms and conditions apply', 'terms and conditions', 'spend requirements', 'campaign period',
        'exclusive offer', 'special offer', 'limited time offer', 'limited time only',
        'balance transfer', 'flexi payment', 'personal loan', 'home loan', 'car loan', 'hire purchase',
        'unit trust', 'fixed deposit promo', 'interest rate',
        'maintenance notice', 'system maintenance', 'system upgrade', 'scheduled downtime',
        'security reminder', 'stay alert', 'scam alert', 'fraud alert',
        'otp', 'tac', 'one-time password', 'verification code', 'authorization code', 'do not share',
        'your password', 'reset password', 'login alert', 'new login'
    ]
    if any(k in lower_text for k in PROMO_AND_AD_KEYWORDS):
        return {'is_promo': True, 'reason': '命中营销推广活动或非动账安全词库'}

    # 1. 动账行为动词硬性检查（必须具备明确真实的财务收支动作，杜绝普通资讯/广告被误记账）
    is_expense = any(k in lower_text for k in [
        'paid', 'spent', 'payment to', 'payment of', 'payment successful', 'payment has been made',
        'deducted', 'debited', 'charged', 'transfer to', 'transferred to', 'transfer of',
        'purchase at', 'purchase of', 'withdrawal', 'withdrawn', 'duitnow qr', 'duitnow transfer to',
        '付款', '支出', '扣款', '转账给', '已支付', '买单', '消费', '成功支付', '成功转账', '成功扣款'
    ])

    is_income = any(k in lower_text for k in [
        'received from', 'received', 'credited', 'refund', 'cash in', 'deposit', 'salary', 'dividend',
        'duitnow transfer from', 'transfer from',
        '转入', '收款', '存入', '退款', '到账', '收到转账', '入账'
    ])

    # 若既不是明确的支出动词，也不是明确的收入动词，直接判定为非交易动账通知并忽略
    if not is_expense and not is_income:
        return None

    tx_type = 'income' if is_income and not is_expense else 'expense'

    # 2. 提取金额：支持 "RM 15.00", "RM15.50", "RM 1,250.00", "MYR 20", "15.00"
    amount = None
    # 优先匹配带 RM / MYR 的格式 (允许千分位逗号)
    m_rm = re.search(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
    if m_rm:
        try:
            val_str = m_rm.group(1).replace(',', '')
            amount = float(val_str)
        except ValueError:
            amount = None

    if amount is None:
        # 回退提取普通数字（允许千分位）
        nums = list(re.finditer(r'\b([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\b', text))
        if nums:
            try:
                val_str = nums[-1].group(1).replace(',', '')
                amount = float(val_str)
            except ValueError:
                pass

    if not amount or amount <= 0:
        return None

    # 3. 提取商户 / 交易对手
    # 常见格式模式匹配：
    # - "paid RM 15.00 to FamilyMart"
    # - "spent RM 45.00 at PETRONAS"
    # - "Transfer of RM 20.00 to Ali"
    # - "Payment to Starbucks of RM 12"
    merchant = ''
    m_to = re.search(r'(?:to|at|from|paid to|transfer to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,35})', text, re.IGNORECASE)
    if m_to:
        m_str = m_to.group(1).strip()
        # 清理后续干扰词如 on, via, using, ref, date, claim, cashback, voucher 等以及句号/换行
        m_cleaned = re.split(r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|for|date|txid|claim|get|earn|earned|cashback|voucher|was|is|successful)\b', m_str, flags=re.IGNORECASE)[0]
        merchant = m_cleaned.strip(' .,-')

    if not merchant:
        # 尝试中文格式：“在【全家】消费”、“向【张三】转账”
        m_cn = re.search(r'(?:在|向)\s*([A-Za-z0-9\u4e00-\u9fa5\s&]{2,20})\s*(?:消费|转账|付款)', text)
        if m_cn:
            merchant = m_cn.group(1).strip()

    if not merchant:
        merchant = '自动追踪消费' if tx_type == 'expense' else '自动追踪入账'

    # 4. 自动归类分类 (Category)
    category = '其他'
    if tx_type == 'income':
        category = '其他'
        group_name = 'side'
    else:
        group_name = None
        # 优先通过商户名匹配映射表
        matched_cat = None
        m_lower = merchant.lower()
        for kw, cat in MERCHANT_CATEGORY_MAPPING.items():
            if kw in m_lower or kw in lower_text:
                matched_cat = cat
                break

        if not matched_cat:
            # 次优按通用支出分类关键词词库匹配
            for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
                if any(k in m_lower or k in lower_text for k in kws):
                    matched_cat = cat
                    break

        category = matched_cat if matched_cat else '其他'

    # 5. 提取日期（若无法从文本中解析出 YYYY-MM-DD，则默认当前日期）
    tx_date = date.today().isoformat()
    m_date = re.search(r'(\d{4}[-/.]\d{1,2}[-/.]\d{1,2})', text)
    if m_date:
        try:
            d_str = m_date.group(1).replace('/', '-').replace('.', '-')
            # 格式化统一为 YYYY-MM-DD
            parts = d_str.split('-')
            tx_date = f'{int(parts[0]):04d}-{int(parts[1]):02d}-{int(parts[2]):02d}'
        except Exception:
            pass

    return {
        'date': tx_date,
        'type': tx_type,
        'group_name': group_name,
        'category': category,
        'amount': amount,
        'note': merchant,
        'raw_text': text
    }


def parse_nlp_text(text):
    """从一句自然语言文本中解析出金额/类型/分组/分类，仅返回草稿，不直接入库。"""
    text = text.strip()
    warnings = []

    amount_matches = list(re.finditer(r'\d+(\.\d+)?', text))
    if not amount_matches:
        return None, ['未能识别出金额，请手动填写']
    m = amount_matches[-1]
    amount = float(m.group())
    remainder = (text[:m.start()] + text[m.end():]).strip()

    is_income = any(k in text for k in INCOME_MAIN_KEYWORDS + INCOME_SIDE_KEYWORDS)
    tx_type = 'income' if is_income else 'expense'

    group_name = None
    category = None

    if tx_type == 'income':
        group_name = 'side' if any(k in text for k in INCOME_SIDE_KEYWORDS) else 'main'
        for cat, kws in INCOME_CATEGORY_KEYWORDS.items():
            if any(k in text for k in kws):
                category = cat
                break
        if category is None:
            category = '工资' if group_name == 'main' else '自由职业'
            warnings.append('未能精确匹配收入子分类，已使用默认分类，请检查')
    else:
        for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
            if any(k in text for k in kws):
                category = cat
                break
        if category is None:
            category = '其他'
            warnings.append('未能匹配支出分类，已归为"其他"，请检查')

    if not remainder:
        remainder = text

    return {
        'date': date.today().isoformat(),
        'type': tx_type,
        'group_name': group_name,
        'category': category,
        'amount': amount,
        'note': remainder,
    }, warnings


# ---------------------------------------------------------------------------
# 仪表盘
# ---------------------------------------------------------------------------

@app.route('/')
def index():
    user_id = get_current_user_id()
    generated = generate_due_recurring(user_id)
    if generated:
        flash(f'已自动生成本月固定收支 {generated} 条', 'success')

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

    # 支出分类按金额从高到低排序，确保图表与图例视觉统一且突出重点
    expense_by_category = dict(sorted(expense_by_category.items(), key=lambda x: x[1], reverse=True))

    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    budget_status = get_category_budget_status(db, user_id, month)

    return render_template(
        'index.html',
        month=month,
        budget_status=budget_status,
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


@app.route('/api/realtime/check')
def api_realtime_check():
    user_id = get_current_user_id()
    client_v = request.args.get('v', type=int)
    current_v = USER_DATA_VERSIONS.get(user_id, DATA_VERSION)
    latest_evt = USER_LATEST_EVENTS.get(user_id, LATEST_EVENT)
    has_update = False
    if client_v is not None and client_v < current_v:
        has_update = True
    return jsonify({
        'ok': True,
        'version': current_v,
        'has_update': has_update,
        'event': latest_evt if has_update else None
    })


@app.route('/partial/dashboard-cards')
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


@app.route('/partial/dashboard-budget')
def partial_dashboard_budget():
    user_id = get_current_user_id()
    month = request.args.get('month') or date.today().strftime('%Y-%m')
    db = get_db()
    budget_status = get_category_budget_status(db, user_id, month)
    return render_template('partials/dashboard_budget.html', budget_status=budget_status)


@app.route('/api/dashboard-charts')
def api_dashboard_charts():
    user_id = get_current_user_id()
    month = (request.args.get('month') or '').strip()
    if not month or len(month.split('-')) != 2:
        month = date.today().strftime('%Y-%m')
    try:
        year, mon = map(int, month.split('-'))
        last_day = monthrange(year, mon)[1]
    except Exception:
        today = date.today()
        year, mon = today.year, today.month
        month = today.strftime('%Y-%m')
        last_day = monthrange(year, mon)[1]

    db = get_db()
    start = f'{month}-01'
    end = f'{month}-{last_day:02d}'

    rows = db.execute(
        'SELECT type, group_name, category, amount FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ?',
        (user_id, start, end)
    ).fetchall()

    income_group = {'main': 0.0, 'side': 0.0}
    expense_by_category = {}
    for r in rows:
        if r['type'] == 'income':
            gname = r['group_name'] or 'main'
            income_group[gname] = income_group.get(gname, 0.0) + r['amount']
        elif r['type'] == 'expense':
            c = r['category'] or '其他'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']

    sorted_expenses = sorted(expense_by_category.items(), key=lambda x: x[1], reverse=True)

    return jsonify({
        'ok': True,
        'month': month,
        'income': {
            'labels': ['主业收入', '副业收入'],
            'values': [round(income_group.get('main', 0.0), 2), round(income_group.get('side', 0.0), 2)]
        },
        'expense': {
            'labels': [k for k, _ in sorted_expenses],
            'values': [round(v, 2) for _, v in sorted_expenses]
        }
    })


@app.route('/api/overview')
def api_overview():
    user_id = get_current_user_id()
    db = get_db()
    time_range = request.args.get('range', 'all')
    today = date.today()
    current_month = today.strftime('%Y-%m')

    start_date = None
    end_date = None
    month_keys = []

    if time_range == '12m':
        start_month = shift_month(current_month, -11)
        start_date = f'{start_month}-01'
        last_day = monthrange(today.year, today.month)[1]
        end_date = f'{current_month}-{last_day:02d}'
        cur = start_month
        while cur <= current_month:
            month_keys.append(cur)
            cur = shift_month(cur, 1)
    elif time_range == 'ytd':
        start_date = f'{today.year}-01-01'
        last_day = monthrange(today.year, today.month)[1]
        end_date = f'{current_month}-{last_day:02d}'
        cur = f'{today.year}-01'
        while cur <= current_month:
            month_keys.append(cur)
            cur = shift_month(cur, 1)
    elif time_range == 'custom':
        start_date = (request.args.get('start') or '').strip() or None
        end_date = (request.args.get('end') or '').strip() or None
    # 'all': start_date and end_date stay None

    query = 'SELECT date, type, group_name, category, amount, COALESCE(from_savings, 0) as from_savings FROM transactions WHERE user_id = ?'
    params = [user_id]
    if start_date:
        query += ' AND date >= ?'
        params.append(start_date)
    if end_date:
        query += ' AND date <= ?'
        params.append(end_date)
    query += ' ORDER BY date ASC'

    rows = db.execute(query, params).fetchall()

    # 如果是 all 或 custom，动态根据记录或参数生成连续月份
    if time_range in ('all', 'custom'):
        if rows:
            min_m = rows[0]['date'][:7]
            max_m = rows[-1]['date'][:7]
            if start_date and start_date[:7] < min_m:
                min_m = start_date[:7]
            if end_date and end_date[:7] > max_m:
                max_m = end_date[:7]
            cur = min_m
            while cur <= max_m:
                month_keys.append(cur)
                cur = shift_month(cur, 1)
        elif start_date and end_date and start_date[:7] <= end_date[:7]:
            cur = start_date[:7]
            while cur <= end_date[:7]:
                month_keys.append(cur)
                cur = shift_month(cur, 1)

    total_income = 0.0
    total_expense = 0.0
    regular_expense = 0.0
    savings_expense = 0.0
    total_savings = 0.0
    monthly_stats = {m: {'income': 0.0, 'expense': 0.0, 'regular_expense': 0.0, 'savings_expense': 0.0, 'savings': 0.0} for m in month_keys}
    expense_cats = {}
    savings_cats = {}
    income_group = {'main': 0.0, 'side': 0.0}

    for r in rows:
        amt = float(r['amount'] or 0)
        m = r['date'][:7]
        t = r['type']
        if m not in monthly_stats:
            monthly_stats[m] = {'income': 0.0, 'expense': 0.0, 'regular_expense': 0.0, 'savings_expense': 0.0, 'savings': 0.0}
            if m not in month_keys:
                month_keys.append(m)

        if t == 'income':
            total_income += amt
            monthly_stats[m]['income'] += amt
            gname = r['group_name'] or 'main'
            income_group[gname] = income_group.get(gname, 0.0) + amt
        elif t == 'expense':
            total_expense += amt
            monthly_stats[m]['expense'] += amt
            is_from_savings = bool(r['from_savings'])
            if is_from_savings:
                savings_expense += amt
                monthly_stats[m]['savings_expense'] += amt
            else:
                regular_expense += amt
                monthly_stats[m]['regular_expense'] += amt
            cat = r['category'] or '其他'
            expense_cats[cat] = expense_cats.get(cat, 0.0) + amt
        elif t == 'savings':
            total_savings += amt
            monthly_stats[m]['savings'] = monthly_stats[m].get('savings', 0.0) + amt
            cat = r['category'] or '储蓄'
            savings_cats[cat] = savings_cats.get(cat, 0.0) + amt

    month_keys.sort()
    monthly_trend = []
    for m in month_keys:
        inc = round(monthly_stats[m]['income'], 2)
        exp = round(monthly_stats[m]['expense'], 2)
        reg_exp = round(monthly_stats[m]['regular_expense'], 2)
        sav = round(monthly_stats[m].get('savings', 0.0), 2)
        monthly_trend.append({
            'month': m,
            'income': inc,
            'expense': exp,
            'regular_expense': reg_exp,
            'savings_expense': round(monthly_stats[m]['savings_expense'], 2),
            'savings': sav,
            'balance': round(inc - reg_exp - sav, 2)
        })

    # 月均计算：有月份跨度按跨度算，否则按实际有记录的月份数，至少为 1
    num_months = max(len(month_keys), 1)
    avg_income = total_income / num_months
    avg_expense = total_expense / num_months
    avg_savings = total_savings / num_months
    net_savings = total_income - regular_expense - total_savings

    all_savings_in = db.execute("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE user_id = ? AND type='savings'", (user_id,)).fetchone()[0] or 0.0
    all_savings_out = db.execute("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE user_id = ? AND type='expense' AND COALESCE(from_savings, 0)=1", (user_id,)).fetchone()[0] or 0.0
    total_savings_pool = max(float(all_savings_in) - float(all_savings_out), 0.0)

    # 支出分类按金额降序排序
    sorted_exp = sorted(expense_cats.items(), key=lambda x: x[1], reverse=True)
    exp_labels = [k for k, v in sorted_exp]
    exp_values = [round(v, 2) for k, v in sorted_exp]

    side_income = income_group.get('side', 0.0)
    side_ratio = round((side_income / total_income * 100), 1) if total_income > 0 else 0.0

    return jsonify({
        'ok': True,
        'has_data': len(rows) > 0,
        'range': time_range,
        'start_date': start_date,
        'end_date': end_date,
        'num_months': num_months,
        'metrics': {
            'total_income': round(total_income, 2),
            'total_expense': round(total_expense, 2),
            'total_savings': round(total_savings, 2),
            'net_savings': round(net_savings, 2),
            'avg_income': round(avg_income, 2),
            'avg_expense': round(avg_expense, 2),
            'avg_savings': round(avg_savings, 2),
        },
        'trend': monthly_trend,
        'expense_categories': {
            'labels': exp_labels,
            'values': exp_values
        },
        'savings_categories': {
            'labels': list(savings_cats.keys()),
            'values': [round(v, 2) for v in savings_cats.values()]
        },
        'income_group': {
            'main': round(income_group.get('main', 0.0), 2),
            'side': round(side_income, 2),
            'side_ratio': side_ratio
        }
    })


# ---------------------------------------------------------------------------
# 支出分类深度洞察报告 (Category Breakdown & Insights)
# ---------------------------------------------------------------------------

def get_category_insights_data(db, time_range='all', start_date=None, end_date=None, user_id=None):
    if not user_id:
        user_id = get_current_user_id()
    today = date.today()
    current_month = today.strftime('%Y-%m')
    prev_month = shift_month(current_month, -1)

    month_keys = []
    if time_range == '12m':
        start_month = shift_month(current_month, -11)
        start_date = f'{start_month}-01'
        last_day = monthrange(today.year, today.month)[1]
        end_date = f'{current_month}-{last_day:02d}'
        cur = start_month
        while cur <= current_month:
            month_keys.append(cur)
            cur = shift_month(cur, 1)
    elif time_range == 'ytd':
        start_date = f'{today.year}-01-01'
        last_day = monthrange(today.year, today.month)[1]
        end_date = f'{current_month}-{last_day:02d}'
        cur = f'{today.year}-01'
        while cur <= current_month:
            month_keys.append(cur)
            cur = shift_month(cur, 1)

    query = "SELECT date, category, amount FROM transactions WHERE user_id = ? AND type='expense'"
    params = [user_id]
    if start_date:
        query += " AND date >= ?"
        params.append(start_date)
    if end_date:
        query += " AND date <= ?"
        params.append(end_date)
    query += " ORDER BY date ASC"

    rows = db.execute(query, params).fetchall()

    if time_range in ('all', 'custom'):
        if rows:
            min_m = rows[0]['date'][:7]
            max_m = rows[-1]['date'][:7]
            if start_date and start_date[:7] < min_m:
                min_m = start_date[:7]
            if end_date and end_date[:7] > max_m:
                max_m = end_date[:7]
            cur = min_m
            while cur <= max_m:
                month_keys.append(cur)
                cur = shift_month(cur, 1)
        elif start_date and end_date and start_date[:7] <= end_date[:7]:
            cur = start_date[:7]
            while cur <= end_date[:7]:
                month_keys.append(cur)
                cur = shift_month(cur, 1)

    month_keys.sort()
    num_months = max(len(month_keys), 1)

    # 统计当月与上月的绝对支出
    cur_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{current_month}%")
    ).fetchall()
    cur_month_map = {r['category'] or '其他': float(r['total'] or 0) for r in cur_month_rows}

    prev_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{prev_month}%")
    ).fetchall()
    prev_month_map = {r['category'] or '其他': float(r['total'] or 0) for r in prev_month_rows}

    # 统计分类汇总及月度分布
    cat_stats = {}
    for r in rows:
        cat = r['category'] or '其他'
        amt = float(r['amount'] or 0)
        m = r['date'][:7]
        if cat not in cat_stats:
            cat_stats[cat] = {'total': 0.0, 'monthly': {mk: 0.0 for mk in month_keys}}
        cat_stats[cat]['total'] += amt
        if m in cat_stats[cat]['monthly']:
            cat_stats[cat]['monthly'][m] += amt
        else:
            cat_stats[cat]['monthly'][m] = amt

    categories_list = []
    for cat, info in cat_stats.items():
        total_amt = round(info['total'], 2)
        avg_monthly = round(total_amt / num_months, 2)
        cur_amt = round(cur_month_map.get(cat, 0.0), 2)
        prev_amt = round(prev_month_map.get(cat, 0.0), 2)

        if prev_amt > 0:
            diff = cur_amt - prev_amt
            pct = round((diff / prev_amt) * 100, 1)
            if pct > 0:
                trend_dir = 'up'
                trend_text = f"+{pct}%"
            elif pct < 0:
                trend_dir = 'down'
                trend_text = f"{pct}%"
            else:
                trend_dir = 'flat'
                trend_text = "持平 0%"
        else:
            if cur_amt > 0:
                trend_dir = 'up'
                trend_text = "本月新增"
            else:
                trend_dir = 'flat'
                trend_text = "—"

        sorted_months = sorted(info['monthly'].keys())
        monthly_values = [round(info['monthly'][mk], 2) for mk in sorted_months]

        categories_list.append({
            'category': cat,
            'total_amt': total_amt,
            'this_month': cur_amt,
            'last_month': prev_amt,
            'avg_monthly': avg_monthly,
            'trend_dir': trend_dir,
            'trend_text': trend_text,
            'months': sorted_months,
            'monthly_values': monthly_values,
        })

    categories_list.sort(key=lambda x: (x['this_month'], x['total_amt']), reverse=True)

    return {
        'ok': True,
        'range': time_range,
        'start_date': start_date,
        'end_date': end_date,
        'current_month': current_month,
        'prev_month': prev_month,
        'num_months': num_months,
        'categories': categories_list,
        'total_categories': len(categories_list),
        'total_expense': round(sum(c['total_amt'] for c in categories_list), 2)
    }


@app.route('/api/category-insights')
def api_category_insights():
    user_id = get_current_user_id()
    db = get_db()
    time_range = request.args.get('range', 'all')
    start_date = (request.args.get('start') or '').strip() or None
    end_date = (request.args.get('end') or '').strip() or None
    data = get_category_insights_data(db, time_range, start_date, end_date, user_id=user_id)
    return jsonify(data)


@app.route('/categories/insights')
def category_insights_page():
    user_id = get_current_user_id()
    db = get_db()
    time_range = request.args.get('range', 'all')
    start_date = (request.args.get('start') or '').strip() or None
    end_date = (request.args.get('end') or '').strip() or None
    insights_data = get_category_insights_data(db, time_range, start_date, end_date, user_id=user_id)
    return render_template(
        'category_insights.html',
        insights=insights_data,
        today=date.today().isoformat()
    )


# ---------------------------------------------------------------------------
# 交易记录：新增 / 编辑 / 删除 / 列表
# ---------------------------------------------------------------------------

@app.route('/transactions/add', methods=['POST'])
def add_transaction():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    try:
        amount = float(f.get('amount') or 0)
    except ValueError:
        amount = 0
    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '金额必须是大于 0 的数字'}), 400
        flash('金额必须是大于 0 的数字', 'error')
        return redirect(url_for('index'))

    tx_type = f.get('type')
    group_name = f.get('group_name') or None
    if tx_type != 'income':
        group_name = None

    from_savings = 1 if (tx_type == 'expense' and f.get('from_savings') in ('1', 'true', 'on')) else 0
    from_savings_category = (f.get('from_savings_category') or '').strip() if from_savings else None
    if from_savings and not from_savings_category:
        from_savings_category = f.get('category') or '储蓄'

    tx_date = f.get('date') or date.today().isoformat()
    cur = db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at, from_savings, from_savings_category) '
        'VALUES (?,?,?,?,?,?,?,?,?,?,?)',
        (user_id, tx_date, tx_type, group_name, f.get('category'), amount, f.get('note', ''),
         f.get('source', 'manual'), datetime.now().isoformat(), from_savings, from_savings_category)
    )
    db.commit()
    bump_data_version('add', {
        'id': cur.lastrowid,
        'note': f.get('note', ''),
        'amount': amount,
        'category': f.get('category'),
        'type': tx_type,
        'date': tx_date,
        'from_savings': from_savings,
        'from_savings_category': from_savings_category,
        'source': f.get('source', 'manual'),
        'user_id': user_id
    })

    budget_alert = None
    if tx_type == 'expense':
        budget_alert = check_and_record_budget_alerts(db, user_id, f.get('category'), tx_date[:7])
        if budget_alert and not is_ajax_request():
            flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

    if is_ajax_request():
        savings_pool, total_pool = get_savings_breakdown(db, user_id)
        # 实时计算当月的最新收入构成与支出分类占比，供前端即时局部更新图表与图例
        tx_month = tx_date[:7]
        y, m = map(int, tx_month.split('-'))
        ld = monthrange(y, m)[1]
        m_start = f'{tx_month}-01'
        m_end = f'{tx_month}-{ld:02d}'
        m_rows = db.execute(
            'SELECT type, group_name, category, amount FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ?',
            (user_id, m_start, m_end)
        ).fetchall()
        m_inc = {'main': 0.0, 'side': 0.0}
        m_exp = {}
        for r in m_rows:
            if r['type'] == 'income':
                gn = r['group_name'] or 'main'
                m_inc[gn] = m_inc.get(gn, 0.0) + r['amount']
            elif r['type'] == 'expense':
                c = r['category'] or '其他'
                m_exp[c] = m_exp.get(c, 0.0) + r['amount']

        return jsonify({
            'ok': True,
            'message': '记录已添加',
            'version': DATA_VERSION,
            'transaction': {
                'id': cur.lastrowid,
                'date': tx_date,
                'type': tx_type,
                'group_name': group_name,
                'category': f.get('category'),
                'amount': amount,
                'note': f.get('note', ''),
                'from_savings': from_savings,
                'from_savings_category': from_savings_category
            },
            'savings_pool': savings_pool,
            'total_savings_pool': total_pool,
            'chart_data': {
                'month': tx_month,
                'income': {
                    'labels': ['主业收入', '副业收入'],
                    'values': [round(m_inc.get('main', 0.0), 2), round(m_inc.get('side', 0.0), 2)]
                },
                'expense': {
                    'labels': [k for k, _ in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)],
                    'values': [round(v, 2) for _, v in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)]
                }
            },
            'budget_alert': budget_alert
        })

    flash('记录已添加', 'success')
    return redirect(url_for('index', month=tx_date[:7]))


@app.route('/records')
def records():
    user_id = get_current_user_id()
    db = get_db()
    start = request.args.get('start', '')
    end = request.args.get('end', '')
    type_ = request.args.get('type', '')
    category = request.args.get('category', '')

    query = 'SELECT * FROM transactions WHERE user_id = ?'
    params = [user_id]
    if start:
        query += ' AND date >= ?'
        params.append(start)
    if end:
        query += ' AND date <= ?'
        params.append(end)
    if type_:
        query += ' AND type = ?'
        params.append(type_)
    if category:
        query += ' AND category = ?'
        params.append(category)
    query += ' ORDER BY date DESC, id DESC'

    rows = db.execute(query, params).fetchall()
    all_categories = db.execute(
        'SELECT DISTINCT category FROM transactions WHERE user_id = ? AND category IS NOT NULL ORDER BY category',
        (user_id,)
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    total_savings = sum(r['amount'] for r in rows if r['type'] == 'savings')

    latest_id = LATEST_EVENT['data'].get('id') if LATEST_EVENT and LATEST_EVENT.get('data') else None

    if request.args.get('partial') == '1':
        return render_template(
            'partials/records_content.html',
            rows=rows,
            total_income=total_income,
            total_expense=total_expense,
            total_savings=total_savings,
            latest_id=latest_id
        )

    return render_template(
        'records.html',
        rows=rows, start=start, end=end, type=type_, category=category,
        categories=[c['category'] for c in all_categories],
        total_income=total_income, total_expense=total_expense, total_savings=total_savings,
        latest_id=latest_id
    )


@app.route('/records/<int:tx_id>/edit', methods=['GET', 'POST'])
def edit_record(tx_id):
    user_id = get_current_user_id()
    db = get_db()
    if request.method == 'POST':
        f = request.form
        tx_type = f.get('type')
        group_name = f.get('group_name') or None
        if tx_type != 'income':
            group_name = None
        try:
            amount = float(f.get('amount') or 0)
        except ValueError:
            amount = 0
        new_category = f.get('category')

        # 检查是否为 auto_track 来源且修改了分类，若是则记忆商户-分类映射覆盖
        current_tx = db.execute('SELECT source, note, category FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id)).fetchone()
        if current_tx and current_tx['source'] == 'auto_track' and current_tx['note'] and new_category:
            merchant_note = current_tx['note'].strip()
            if merchant_note:
                now_str = datetime.now().isoformat()
                db.execute(
                    'INSERT OR REPLACE INTO merchant_category_overrides (merchant_note, category, updated_at) VALUES (?, ?, ?)',
                    (merchant_note, new_category, now_str)
                )

        from_savings = 1 if (tx_type == 'expense' and f.get('from_savings') in ('1', 'true', 'on')) else 0
        from_savings_category = (f.get('from_savings_category') or '').strip() if from_savings else None
        if from_savings and not from_savings_category:
            from_savings_category = f.get('category') or '储蓄'

        db.execute(
            'UPDATE transactions SET date=?, type=?, group_name=?, category=?, amount=?, note=?, from_savings=?, from_savings_category=? WHERE id=? AND user_id=?',
            (f.get('date'), tx_type, group_name, new_category, amount, f.get('note', ''), from_savings, from_savings_category, tx_id, user_id)
        )
        db.commit()
        bump_data_version('edit', {'id': tx_id, 'from_savings': from_savings, 'from_savings_category': from_savings_category})

        budget_alert = None
        if tx_type == 'expense':
            budget_alert = check_and_record_budget_alerts(db, user_id, new_category, (f.get('date') or '')[:7])
            if budget_alert and not is_ajax_request():
                flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

        if is_ajax_request():
            return jsonify({'ok': True, 'message': '记录已更新', 'budget_alert': budget_alert})

        flash('记录已更新', 'success')
        return redirect(url_for('records'))

    row = db.execute('SELECT * FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id)).fetchone()
    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    savings_pool_by_category, total_savings_pool = get_savings_breakdown(db, user_id)
    return render_template(
        'edit_record.html', row=row,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        savings_pool_by_category=savings_pool_by_category,
    )


@app.route('/records/<int:tx_id>/delete', methods=['POST'])
def delete_record(tx_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute('DELETE FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id))
    db.commit()
    bump_data_version('delete', {'id': tx_id})

    if is_ajax_request():
        return jsonify({'ok': True, 'message': '记录已删除', 'id': tx_id})

    flash('记录已删除', 'success')
    return redirect(url_for('records'))


@app.route('/records/batch-delete', methods=['POST'])
def batch_delete_records():
    user_id = get_current_user_id()
    db = get_db()
    ids = request.form.getlist('ids')
    if not ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中任何记录'}), 400
        flash('未选中任何记录', 'error')
        return redirect(url_for('records'))

    # 安全地过滤数字 ID
    valid_ids = []
    for i in ids:
        try:
            valid_ids.append(int(i))
        except ValueError:
            pass

    if valid_ids:
        placeholders = ','.join('?' * len(valid_ids))
        db.execute(f'DELETE FROM transactions WHERE user_id = ? AND id IN ({placeholders})', [user_id] + valid_ids)
        db.commit()
        bump_data_version('batch_delete', {'count': len(valid_ids)})

        if is_ajax_request():
            return jsonify({'ok': True, 'message': f'成功批量删除 {len(valid_ids)} 条记录', 'deleted_ids': valid_ids})

        flash(f'成功批量删除 {len(valid_ids)} 条记录', 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中有效的记录'}), 400
        flash('未选中有效的记录', 'error')

    return redirect(url_for('records'))


@app.route('/records/batch-edit', methods=['POST'])
def batch_edit_records():
    """批量修改记录的分类与类型"""
    user_id = get_current_user_id()
    db = get_db()
    ids = request.form.getlist('ids')
    new_type = request.form.get('type')
    new_category = request.form.get('category')
    group_name = request.form.get('group_name') or None
    if new_type and new_type != 'income':
        group_name = None

    if not ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中任何记录'}), 400
        flash('未选中任何记录', 'error')
        return redirect(url_for('records'))

    valid_ids = []
    for i in ids:
        try:
            valid_ids.append(int(i))
        except ValueError:
            pass

    if not valid_ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中有效的记录'}), 400
        flash('未选中有效的记录', 'error')
        return redirect(url_for('records'))

    if not new_type and not new_category:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未指定需要修改的分类或类型'}), 400
        flash('未指定需要修改的分类或类型', 'warning')
        return redirect(url_for('records'))

    updates = []
    params = []
    if new_type:
        updates.append("type = ?")
        params.append(new_type)
        if new_type == 'income' and group_name:
            updates.append("group_name = ?")
            params.append(group_name)
        elif new_type != 'income':
            updates.append("group_name = NULL")

    if new_category:
        updates.append("category = ?")
        params.append(new_category)

    set_clause = ", ".join(updates)
    placeholders = ','.join('?' * len(valid_ids))
    sql = f"UPDATE transactions SET {set_clause} WHERE user_id = ? AND id IN ({placeholders})"
    db.execute(sql, params + [user_id] + valid_ids)
    db.commit()
    bump_data_version('batch_edit', {'count': len(valid_ids)})

    # 批量修改后，对涉及到的每个支出分类各检查一次是否需要发出超支提醒（去重表保证不会重复弹出）
    budget_alerts = []
    if new_type is None or new_type == 'expense':
        touched_cats = db.execute(
            f"SELECT DISTINCT category FROM transactions WHERE user_id = ? AND type = 'expense' AND id IN ({placeholders})",
            [user_id] + valid_ids
        ).fetchall()
        this_month = date.today().strftime('%Y-%m')
        for row in touched_cats:
            alert = check_and_record_budget_alerts(db, user_id, row['category'], this_month)
            if alert:
                budget_alerts.append(alert)
                if not is_ajax_request():
                    flash(alert['message'], 'warning' if alert['threshold'] < 100 else 'error')

    if is_ajax_request():
        return jsonify({'ok': True, 'message': f'成功批量修改 {len(valid_ids)} 条记录', 'edited_ids': valid_ids, 'budget_alerts': budget_alerts})

    flash(f'成功批量修改 {len(valid_ids)} 条记录', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# 自然语言快速记账
# ---------------------------------------------------------------------------

def clean_and_parse_json(raw_str):
    """从 LLM 返回的文本中稳健提取并解析 JSON 对象"""
    if not raw_str or not isinstance(raw_str, str):
        return None
    s = raw_str.strip()
    if s.startswith('```'):
        lines = s.split('\n')
        if lines[0].startswith('```'):
            lines = lines[1:]
        if lines and lines[-1].startswith('```'):
            lines = lines[:-1]
        s = '\n'.join(lines).strip()
    start = s.find('{')
    end = s.rfind('}')
    if start != -1 and end != -1 and end > start:
        s = s[start:end+1]
    try:
        return json.loads(s)
    except Exception:
        return None


def call_llm_json(prompt, system_instruction=None, timeout=None):
    """
    通用多源 LLM JSON 接口：
    1. 优先 Google Gemini 2.5 Flash
    2. 次选 DeepSeek / OpenAI
    3. 次选 本地 Ollama
    4. 失败返回 None，调用方自动降级到规则引擎
    """
    t = timeout or LLM_TIMEOUT

    # 1. Google Gemini
    if GEMINI_API_KEY:
        for model in GEMINI_FALLBACK_MODELS:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
            payload = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "responseMimeType": "application/json",
                    "temperature": 0.1,
                    "maxOutputTokens": 250
                }
            }
            if system_instruction:
                payload["systemInstruction"] = {"parts": [{"text": system_instruction}]}
            try:
                resp = requests.post(url, json=payload, timeout=t)
                if resp.status_code == 200:
                    data = resp.json()
                    candidates = data.get('candidates') or []
                    if candidates:
                        part = candidates[0].get('content', {}).get('parts', [{}])[0].get('text', '')
                        parsed = clean_and_parse_json(part)
                        if parsed:
                            return parsed
                elif resp.status_code in (404, 429, 503):
                    continue
            except Exception as e:
                if AUTO_TRACK_DEBUG_LOG:
                    print(f"[LLM DEBUG] Gemini {model} error: {e}")

    # 2. DeepSeek
    if DEEPSEEK_API_KEY:
        try:
            resp = requests.post(
                "https://api.deepseek.com/chat/completions",
                headers={"Authorization": f"Bearer {DEEPSEEK_API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": "deepseek-chat",
                    "messages": [
                        *([{"role": "system", "content": system_instruction}] if system_instruction else []),
                        {"role": "user", "content": prompt}
                    ],
                    "response_format": {"type": "json_object"},
                    "temperature": 0.1
                },
                timeout=t
            )
            if resp.status_code == 200:
                content = resp.json()['choices'][0]['message']['content']
                parsed = clean_and_parse_json(content)
                if parsed:
                    return parsed
        except Exception as e:
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[LLM DEBUG] DeepSeek error: {e}")

    # 3. OpenAI
    if OPENAI_API_KEY:
        try:
            resp = requests.post(
                f"{OPENAI_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": OPENAI_MODEL,
                    "messages": [
                        *([{"role": "system", "content": system_instruction}] if system_instruction else []),
                        {"role": "user", "content": prompt}
                    ],
                    "response_format": {"type": "json_object"},
                    "temperature": 0.1
                },
                timeout=t
            )
            if resp.status_code == 200:
                content = resp.json()['choices'][0]['message']['content']
                parsed = clean_and_parse_json(content)
                if parsed:
                    return parsed
        except Exception as e:
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[LLM DEBUG] OpenAI error: {e}")

    # 4. Local Ollama
    if OLLAMA_URL:
        try:
            resp = requests.post(
                f"{OLLAMA_URL}/api/generate",
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": (f"{system_instruction}\n\n{prompt}") if system_instruction else prompt,
                    "stream": False,
                    "format": "json"
                },
                timeout=t
            )
            if resp.status_code == 200:
                raw_response = resp.json().get('response', '{}')
                parsed = clean_and_parse_json(raw_response)
                if parsed:
                    return parsed
        except Exception as e:
            pass

    return None


def get_llm_learning_samples_prompt(user_id=None, limit=12):
    """从 llm_learning_samples 数据库读取样本，构造提供给 LLM 提示词的动态参考案例库"""
    try:
        db = get_db()
        rows = db.execute('''
            SELECT text, label_type, is_real_transaction, sample_amount, sample_merchant, sample_category, notes
            FROM llm_learning_samples
            ORDER BY id ASC LIMIT ?
        ''', (limit,)).fetchall()
        if not rows:
            return ""

        lines = [
            "[LEARNING SAMPLES & REFERENCE DATASHEET / 语言学习样本库与判定示范]:",
            "Refer closely to the following labeled real-world samples when evaluating notifications:"
        ]
        for idx, r in enumerate(rows, 1):
            is_real = bool(r['is_real_transaction'])
            tx_type = None
            if is_real:
                tx_type = 'income' if 'income' in r['label_type'] else 'expense'
            item = {
                "is_real_transaction": is_real,
                "label_type": r['label_type'],
                "type": tx_type,
                "amount": r['sample_amount'],
                "merchant": r['sample_merchant'],
                "category": r['sample_category'],
                "reason": r['notes'] or r['label_type']
            }
            lines.append(f"Sample {idx}: \"{r['text']}\" -> {json.dumps(item, ensure_ascii=False)}")
        return "\n".join(lines) + "\n\n"
    except Exception as e:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[LLM SAMPLES DEBUG] Error loading samples: {e}")
        return ""


def classify_notification_with_llm(text):
    """
    智能营销/广告过滤与关键要素提取：
    通过 LLM 深度校验通知是否为真实发生的交易（扣款/入账），还是营销推广、抽奖、信用卡办卡推广、返现活动或OTP验证码。
    返回: (is_real: bool, llm_data: dict or None)
    采用 Fail-open 策略：若 LLM 离线或超时，放行并返回 (True, None)，确保不漏记真实交易。
    """
    system_instruction = (
        "You are an expert financial transaction validator and parser for Malaysian banking and e-wallets "
        "(Touch 'n Go eWallet, MAE Maybank, Public Bank / MyPB, CIMB Octo, RHB, Hong Leong, GrabPay, Boost, BigPay, etc.).\n"
        "Your task: Decide whether the mobile notification describes an ACTUAL COMPLETED financial transaction "
        "(payment, transfer, debit, credit) or is a PROMOTIONAL MARKETING AD / LOAN OFFER / CREDIT CARD PROMOTION / OTP / SYSTEM NOTICE.\n\n"
        "Rules:\n"
        "1. Genuine Malaysian e-wallet transaction receipts frequently append marketing rewards at the end "
        "(e.g. 'Paid RM 15.00 to FamilyMart. Claim your RM2 voucher!'). If money was ACTUALLY spent, transferred, or received, "
        "is_real_transaction MUST BE TRUE.\n"
        "2. If the message is a promotional campaign inviting the user to apply for cards/loans, join a contest, win prizes, "
        "earn cash back on future spends, or an advertisement (e.g. 'Apply online for PB Credit Card to get RM300 Cash Back'), "
        "is_real_transaction MUST BE FALSE.\n"
        "3. If it is an OTP, verification code, login alert, or system downtime notice, is_real_transaction MUST BE FALSE.\n"
        "4. Standard expense categories: 餐饮, 交通, 购物, 娱乐, 居住, 医疗, 教育, 通讯, 旅行, 人情, 其他.\n"
        "5. Standard income categories: 工资, 奖金, 投资, 自由职业, 其他.\n"
        "Reply with ONLY valid JSON: {\n"
        "  \"is_real_transaction\": true/false,\n"
        "  \"label_type\": \"promo\"|\"expense\"|\"income_transfer\"|\"expense_transfer\"|\"otp_notice\",\n"
        "  \"reason\": \"short reason\",\n"
        "  \"amount\": float or null,\n"
        "  \"type\": \"expense\"|\"income\"|null,\n"
        "  \"merchant\": \"clean merchant or recipient/sender name\" or null,\n"
        "  \"category\": \"standard category name\" or null\n"
        "}"
    )
    samples_block = get_llm_learning_samples_prompt()
    prompt = (
        f"{samples_block}"
        f"Notification text to evaluate:\n\"\"\"{text}\"\"\""
    )
    res = call_llm_json(prompt, system_instruction=system_instruction, timeout=4.5)
    if isinstance(res, dict) and 'is_real_transaction' in res:
        is_real = bool(res['is_real_transaction'])
        return is_real, res

    # 无法通过 LLM 判定时，执行 Fail-open，放行真实交易
    return True, None


def parse_nlp_with_llm(text):
    """
    通过 LLM 将口语化自然语言文本解析为标准记账对象。
    返回 dict 或 None
    """
    system_instruction = (
        "You are an intelligent accounting parser for a personal ledger app in Malaysia.\n"
        "Parse colloquial natural language entries (in Chinese or English or Malay) into a structured ledger transaction.\n"
        "Categories allowed:\n"
        "- expense: 餐饮, 交通, 购物, 娱乐, 居住, 医疗, 教育, 通讯, 旅行, 人情, 其他\n"
        "- income: 工资, 奖金, 投资, 自由职业, 其他 (group_name is 'main' for salary/main job, 'side' for side gig/investment)\n"
        "- savings: 应急金, 养老, 旅游, 心愿, 其他\n"
        f"- Assume today is {date.today().isoformat()}. Parse relative dates like '昨天', '前天', 'yesterday' correctly.\n"
        "Return ONLY a JSON object: {\n"
        "  \"amount\": positive float,\n"
        "  \"type\": \"expense\" | \"income\" | \"savings\",\n"
        "  \"group_name\": \"main\" | \"side\" | null,\n"
        "  \"category\": \"category name\",\n"
        "  \"note\": \"short descriptive summary of merchant or item\",\n"
        "  \"date\": \"YYYY-MM-DD\"\n"
        "}"
    )
    prompt = f"Ledger entry:\n\"{text}\""
    res = call_llm_json(prompt, system_instruction=system_instruction, timeout=3.5)
    if isinstance(res, dict) and res.get('amount'):
        try:
            amt = float(res['amount'])
            if amt > 0:
                tx_type = res.get('type')
                if tx_type not in ('expense', 'income', 'savings'):
                    tx_type = 'expense'
                group_name = res.get('group_name')
                if tx_type != 'income':
                    group_name = None
                elif group_name not in ('main', 'side'):
                    group_name = 'main'

                parsed_date = res.get('date') or date.today().isoformat()
                if not re.match(r'^\d{4}-\d{2}-\d{2}$', str(parsed_date)):
                    parsed_date = date.today().isoformat()

                return {
                    'date': str(parsed_date),
                    'type': tx_type,
                    'group_name': group_name,
                    'category': str(res.get('category') or '其他'),
                    'amount': amt,
                    'note': str(res.get('note') or text).strip()
                }
        except Exception:
            pass
    return None


@app.route('/nlp/parse', methods=['POST'])
def nlp_parse():
    text = request.form.get('text', '').strip()
    if not text:
        return {'ok': False, 'message': '请输入内容后再点智能解析'}

    # 1. 优先调用 LLM 深度智能解析
    llm_parsed = parse_nlp_with_llm(text)
    if llm_parsed and llm_parsed.get('amount'):
        return {
            'ok': True,
            'parsed': llm_parsed,
            'source': 'llm',
            'warnings': [],
            'original_text': text
        }

    # 2. 回退到本地规则解析器
    parsed, warnings = parse_nlp_text(text)
    if parsed is None:
        return {'ok': False, 'message': '解析失败：' + '；'.join(warnings) + '。请改用下方快速录入表单手动填写。'}

    return {'ok': True, 'parsed': parsed, 'source': 'rule', 'warnings': warnings, 'original_text': text}


@app.route('/api/auto-track', methods=['GET', 'POST'])
@csrf.exempt
def api_auto_track():
    # 鉴权检查：只接受 Header X-API-KEY 或 JSON/表单 body 里的 key，不再接受 URL 参数 ?key=xxx。
    # URL 参数里的密钥很容易被 server access log、浏览器历史记录、反向代理日志留下痕迹，
    # 之前泄漏的那把 key 就是从类似的地方外流的，所以这里刻意不留这个入口。
    req_key = request.headers.get('X-API-KEY')
    data = {}
    if request.is_json:
        data = request.get_json(silent=True) or {}
        if not req_key:
            req_key = data.get('key')
    else:
        req_key = req_key or request.form.get('key')

    print(f"[AUTO_TRACK] Request from {request.remote_addr}, Method={request.method}, KeyProvided={'YES' if req_key else 'NO'}, ContentType={request.content_type}")

    if not is_valid_api_key(req_key):
        print(f"[AUTO_TRACK] Rejected: Invalid API Key")
        return jsonify({'ok': False, 'message': 'API Key 无效或未在服务器配置，拒绝访问'}), 401

    # 获取通知文本：优先从 Query 参数获取，再从 JSON / 表单 / Raw Payload 获取
    raw_payload = request.get_data(as_text=True)
    text = request.args.get('text') or ""

    # 1. 尝试从 JSON 提取（如果 Query 参数未提供）
    if not text and request.is_json:
        try:
            data = request.get_json(silent=True) or {}
            if isinstance(data, dict):
                text = data.get('text') or data.get('body') or data.get('message') or ""
        except Exception:
            pass

    # 2. 尝试从 Form 表单提取
    if not text:
        text = request.form.get('text') or request.form.get('body') or request.form.get('message') or ""

    # 3. 尝试从 Raw Payload 提取 (过滤无意义的空或极短字符)
    if not text and raw_payload and len(raw_payload.strip()) > 3:
        # 如果是 JSON 字符串但含换行导致 get_json 失败，做宽容正则提取
        if raw_payload.strip().startswith('{'):
            try:
                import re
                m = re.search(r'"(?:text|body|message)"\s*:\s*"(.*?)"(?:\s*,\s*"|\s*})', raw_payload, re.DOTALL)
                if m:
                    text = m.group(1).replace('\\"', '"').replace('\\n', '\n')
            except Exception:
                pass
        if not text:
            text = raw_payload

    text = (text or "").strip()
    # 如果 payload 是类似 text=... 的 urlencoded 形式，自动解出
    if text.startswith('text='):
        from urllib.parse import unquote
        text = unquote(text[5:]).strip()

    print(f"[AUTO_TRACK] Extracted text: {repr(text[:120])}")

    if not text or text == "None" or text == "null":
        return jsonify({
            'ok': False,
            'message': '未收到有效的通知文本内容（若为手动测试，请确保当前通知栏存在真实的扣款通知）'
        }), 400

    parsed = parse_auto_track_notification(text)
    if AUTO_TRACK_DEBUG_LOG:
        print(f"[AUTO_TRACK DEBUG] Parsed result: {parsed}")

    if parsed and parsed.get('is_promo'):
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Rejected as promo by blacklist: {parsed.get('reason')}")
        return jsonify({
            'ok': False,
            'verdict': 'rejected_promo',
            'message': '通知被识别为营销推广活动或非动账通知，已自动忽略入账',
            'reason': parsed.get('reason'),
            'raw_text': text
        }), 200

    if not parsed or not parsed.get('amount'):
        if AUTO_TRACK_DEBUG_LOG:
            print("[AUTO_TRACK DEBUG] Failed to parse amount! Returning 422")
        return jsonify({
            'ok': False,
            'message': '未能从通知中提取出有效金额或商户信息',
            'raw_text': text
        }), 422

    # 优先检查是否存在商户历史手动纠偏记录（精确匹配提取到的商户/备注名，优先级高于默认推断与 LLM 分类）
    merchant_note = (parsed.get('note') or '').strip()
    if merchant_note:
        db = get_db()
        override = db.execute(
            'SELECT category FROM merchant_category_overrides WHERE merchant_note = ?',
            (merchant_note,)
        ).fetchone()
        if override and override['category']:
            parsed['category'] = override['category']
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[AUTO_TRACK DEBUG] Applied remembered merchant override: '{merchant_note}' -> '{override['category']}'")

    # Phase-2: LLM 营销广告二次校验与要素智能增强（Fail-open 策略）
    is_real, llm_data = classify_notification_with_llm(text)

    if not is_real:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Notification rejected by Phase-2 LLM as promotional: {repr(text)}")
        return jsonify({
            'ok': False,
            'verdict': 'rejected_promo',
            'message': '通知被识别为营销推广或非真实交易，已忽略入账',
            'parsed': parsed,
            'raw_text': text
        }), 200

    # 智能增强：如果 LLM 提取到了更精准的分类、商户名称或转账进账方向
    if llm_data and isinstance(llm_data, dict):
        label_type = llm_data.get('label_type')
        if label_type == 'income_transfer':
            parsed['type'] = 'income'
            if not parsed.get('group_name'):
                parsed['group_name'] = 'main' if any(k in text.lower() for k in ['salary', 'payroll', '工资', '薪资', '薪水']) else 'side'
        elif label_type in ('expense', 'expense_transfer'):
            parsed['type'] = 'expense'
            parsed['group_name'] = None

        if parsed.get('category') == '其他' and llm_data.get('category') and llm_data['category'] != '其他':
            parsed['category'] = str(llm_data['category']).strip()
        if parsed.get('note') in ('自动追踪消费', '自动追踪入账') and llm_data.get('merchant'):
            parsed['note'] = str(llm_data['merchant']).strip()

    # 入库写入交易记录
    db = get_db()
    now = datetime.now().isoformat()
    # 确定入账归属用户（支持参数指定 user_id 或 username，保底使用 admin 或首位用户）
    target_user_id = data.get('user_id') or request.args.get('user_id')
    target_username = data.get('username') or request.args.get('username')
    if target_username and not target_user_id:
        u_row = db.execute("SELECT id FROM users WHERE username = ?", (target_username,)).fetchone()
        if u_row:
            target_user_id = u_row['id']
    if not target_user_id:
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        if admin_row:
            target_user_id = admin_row['id']
        else:
            first_row = db.execute("SELECT id FROM users ORDER BY created_at ASC LIMIT 1").fetchone()
            target_user_id = first_row['id'] if first_row else None

    cur = db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        (
            target_user_id,
            parsed['date'],
            parsed['type'],
            parsed['group_name'],
            parsed['category'],
            parsed['amount'],
            parsed['note'],
            'auto_track',
            now
        )
    )
    db.commit()
    bump_data_version('auto_track', {
        'id': cur.lastrowid,
        'note': parsed['note'],
        'amount': parsed['amount'],
        'category': parsed['category'],
        'type': parsed['type'],
        'date': parsed['date'],
        'source': 'auto_track',
        'user_id': target_user_id
    })

    type_text = '支出' if parsed['type'] == 'expense' else '收入' if parsed['type'] == 'income' else '储蓄'
    note_str = f" ({parsed['note']})" if parsed['note'] else ""
    notification_title = "自动记账成功 💸"
    notification_body = f"已自动记入【{type_text} · {parsed['category']}】{money_filter(parsed['amount'])}{note_str}"

    budget_alert = None
    if parsed['type'] == 'expense':
        budget_alert = check_and_record_budget_alerts(db, target_user_id, parsed['category'], parsed['date'][:7])

    return jsonify({
        'ok': True,
        'verdict': 'accepted',
        'message': f"成功自动记账：{parsed['note']} {money_filter(parsed['amount'])} ({parsed['category']})",
        'transaction_id': cur.lastrowid,
        'parsed': parsed,
        'notification_title': notification_title,
        'notification_body': notification_body,
        'budget_alert': budget_alert
    }), 201


@app.route('/api/categories', methods=['GET'])
@csrf.exempt
def api_get_categories():
    """获取所有可用分类列表（专供 Android 端离线缓存与下拉选择使用）。
    只认 X-API-KEY，不接受 session cookie 登录状态 —— 这个端点从未被网页端调用过，
    保留 cookie 当备用认证方式只会平白让它暴露在 CSRF 攻击面下，没有实际用途。"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key 无效'}), 401

    user_id = get_current_user_id()
    db = get_db()
    rows = db.execute('SELECT id, name, type, group_name FROM categories WHERE user_id = ? ORDER BY type, id', (user_id,)).fetchall()
    categories = [{'id': r['id'], 'name': r['name'], 'type': r['type'], 'group_name': r['group_name']} for r in rows]
    return jsonify({'ok': True, 'categories': categories})


@app.route('/api/transactions/sync', methods=['POST'])
@csrf.exempt
def api_sync_transactions():
    """批量同步移动端离线记账数据。只认 X-API-KEY，不接受 session cookie —— 这个端点
    从未被网页端调用过，保留 cookie 备用认证只会平白让写入操作暴露在 CSRF 攻击面下。"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key 无效'}), 401

    payload = request.get_json(silent=True) or {}
    txs = payload.get('transactions', [])
    if not txs:
        return jsonify({'ok': True, 'synced_count': 0, 'synced_ids': []})

    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()
    synced_ids = []
    for item in txs:
        try:
            cur = db.execute(
                'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
                (
                    user_id,
                    item.get('date') or date.today().isoformat(),
                    item.get('type') or 'expense',
                    item.get('group_name') or 'personal',
                    item.get('category') or '其他',
                    float(item.get('amount') or 0.0),
                    item.get('note') or '离线录入',
                    item.get('source') or 'offline_sync',
                    now
                )
            )
            local_id = item.get('local_id') or item.get('id')
            if local_id is not None:
                synced_ids.append(local_id)
        except Exception as e:
            print(f"[SYNC ERROR] Failed to insert offline transaction: {e}")

    db.commit()
    return jsonify({'ok': True, 'synced_count': len(synced_ids), 'synced_ids': synced_ids})


@app.route('/download/apk')
def download_apk():
    """下载 100% 原生专属 Android 伴侣 App 安装包 (免 MacroDroid / 零第三方工具)"""
    download_dir = os.path.join(app.root_path, 'static', 'download')
    return send_from_directory(download_dir, 'ledger-app.apk', as_attachment=True, download_name='我的账本.apk')


@app.route('/auto-track')
def auto_track_page():
    """Auto Track 配置与测试页面"""
    scheme = request.headers.get('X-Forwarded-Proto', request.scheme)
    if 'onrender.com' in request.host:
        scheme = 'https'
    base_url = f"{scheme}://{request.host}".rstrip('/')
    api_key = get_auto_track_key()
    webhook_url = f"{base_url}/api/auto-track"
    db = get_db()
    samples = db.execute("SELECT * FROM llm_learning_samples ORDER BY id ASC").fetchall()
    return render_template(
        'auto_track.html',
        api_key=api_key,
        webhook_url=webhook_url,
        llm_info=get_active_llm_provider(),
        samples=samples
    )


# ---------------------------------------------------------------------------
# LLM 语言学习样本库 (Few-Shot Datasheet 管理接口)
# ---------------------------------------------------------------------------

@app.route('/api/llm-samples', methods=['GET'])
def api_llm_samples_list():
    db = get_db()
    rows = db.execute("SELECT * FROM llm_learning_samples ORDER BY id ASC").fetchall()
    return jsonify({'ok': True, 'samples': [dict(r) for r in rows]})


@app.route('/api/llm-samples/add', methods=['POST'])
def api_llm_samples_add():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录后再添加样本'}), 401
    db = get_db()
    data = request.get_json(silent=True) or request.form
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'ok': False, 'message': '样本通知文本不能为空'}), 400

    label_type = (data.get('label_type') or 'expense').strip()
    is_real = 1 if data.get('is_real_transaction') in (True, 1, '1', 'true', 'True') else 0
    if label_type in ('promo', 'otp_notice'):
        is_real = 0

    amount = data.get('sample_amount')
    try:
        amount = float(amount) if amount not in (None, '', 'null') else None
    except Exception:
        amount = None

    merchant = (data.get('sample_merchant') or '').strip() or None
    category = (data.get('sample_category') or '').strip() or None
    notes = (data.get('notes') or '').strip() or None
    now = datetime.now().isoformat()
    user_id = session.get('user_id')

    db.execute('''
        INSERT INTO llm_learning_samples (user_id, text, label_type, is_real_transaction, sample_amount, sample_merchant, sample_category, notes, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (user_id, text, label_type, is_real, amount, merchant, category, notes, now))
    db.commit()

    return jsonify({'ok': True, 'message': '成功录入学习样本库！大模型下次遇到类似通知将照此学习。'})


@app.route('/api/llm-samples/delete/<int:sample_id>', methods=['POST'])
def api_llm_samples_delete(sample_id):
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录'}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples WHERE id = ?", (sample_id,))
    db.commit()
    return jsonify({'ok': True, 'message': '样本已成功删除'})


@app.route('/api/llm-samples/reset', methods=['POST'])
def api_llm_samples_reset():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录'}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples")
    db.commit()
    seed_learning_samples(db)
    return jsonify({'ok': True, 'message': '已成功将学习样本库恢复为官方预设语料库！'})


# ---------------------------------------------------------------------------
# 分类管理
# ---------------------------------------------------------------------------

@app.route('/categories')
def categories_page():
    user_id = get_current_user_id()
    db = get_db()
    income_main = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='income' AND group_name='main' ORDER BY id", (user_id,)).fetchall()
    income_side = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='income' AND group_name='side' ORDER BY id", (user_id,)).fetchall()
    expense = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='expense' ORDER BY id", (user_id,)).fetchall()
    savings = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='savings' ORDER BY id", (user_id,)).fetchall()
    budget_status = get_category_budget_status(db, user_id)
    budget_map = {b['category']: b for b in budget_status}
    return render_template(
        'categories.html', income_main=income_main, income_side=income_side, expense=expense, savings=savings,
        budget_map=budget_map,
    )


@app.route('/categories/add', methods=['POST'])
def add_category():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    type_ = f.get('type')
    group_name = f.get('group_name') or None
    if type_ != 'income':
        group_name = None
    name = (f.get('name') or '').strip()
    if not name:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '分类名称不能为空'}), 400
        flash('分类名称不能为空', 'error')
        return redirect(url_for('categories_page'))
    try:
        db.execute('INSERT INTO categories (user_id, type, group_name, name) VALUES (?,?,?,?)', (user_id, type_, group_name, name))
        db.commit()
        if is_ajax_request():
            return jsonify({'ok': True, 'message': '分类已添加'})
        flash('分类已添加', 'success')
    except sqlite3.IntegrityError:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '该分类已存在'}), 400
        flash('该分类已存在', 'error')
    return redirect(url_for('categories_page'))


@app.route('/categories/<int:cat_id>/delete', methods=['POST'])
def delete_category(cat_id):
    user_id = get_current_user_id()
    db = get_db()
    cat = db.execute('SELECT name FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id)).fetchone()
    db.execute('DELETE FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id))
    if cat:
        db.execute('DELETE FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name']))
    db.commit()
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '分类已删除（历史记录中的旧数据不受影响）', 'id': cat_id})
    flash('分类已删除（历史记录中的旧数据不受影响）', 'success')
    return redirect(url_for('categories_page'))


@app.route('/categories/<int:cat_id>/budget', methods=['POST'])
def set_category_budget(cat_id):
    """设置或取消某个支出分类的月度预算上限"""
    user_id = get_current_user_id()
    db = get_db()
    cat = db.execute("SELECT name FROM categories WHERE id = ? AND user_id = ? AND type = 'expense'", (cat_id, user_id)).fetchone()
    if not cat:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '分类不存在'}), 404
        flash('分类不存在', 'error')
        return redirect(url_for('categories_page'))

    raw_limit = (request.form.get('monthly_limit') or '').strip()
    now = datetime.now().isoformat()

    if not raw_limit:
        db.execute('DELETE FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name']))
        db.commit()
        bump_data_version('budget', {'category': cat['name'], 'user_id': user_id})
        msg = f'已取消「{cat["name"]}」的月度预算'
    else:
        try:
            limit = float(raw_limit)
        except ValueError:
            limit = -1
        if limit <= 0:
            if is_ajax_request():
                return jsonify({'ok': False, 'message': '预算金额必须是大于 0 的数字'}), 400
            flash('预算金额必须是大于 0 的数字', 'error')
            return redirect(url_for('categories_page'))

        existing = db.execute('SELECT id FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name'])).fetchone()
        if existing:
            db.execute('UPDATE category_budgets SET monthly_limit = ?, updated_at = ? WHERE id = ?', (limit, now, existing['id']))
        else:
            db.execute(
                'INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at) VALUES (?,?,?,?,?)',
                (user_id, cat['name'], limit, now, now)
            )
        db.commit()
        bump_data_version('budget', {'category': cat['name'], 'limit': limit, 'user_id': user_id})
        msg = f'已设置「{cat["name"]}」的月度预算为 RM{limit:.2f}'

    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg})
    flash(msg, 'success')
    return redirect(url_for('categories_page'))


# ---------------------------------------------------------------------------
# 固定 / 重复收支
# ---------------------------------------------------------------------------

@app.route('/recurring')
def recurring_page():
    user_id = get_current_user_id()
    db = get_db()
    rules = db.execute('SELECT * FROM recurring_rules WHERE user_id = ? ORDER BY id DESC', (user_id,)).fetchall()
    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    return render_template(
        'recurring.html', rules=rules,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        current_month=date.today().strftime('%Y-%m'),
    )


@app.route('/recurring/add', methods=['POST'])
def add_recurring():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    tx_type = f.get('type')
    group_name = f.get('group_name') or None
    if tx_type != 'income':
        group_name = None
    try:
        day = int(f.get('day_of_month') or 1)
    except ValueError:
        day = 1
    day = max(1, min(day, 31))
    try:
        amount = float(f.get('amount') or 0)
    except ValueError:
        amount = 0

    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '金额必须是大于 0 的数字'}), 400
        flash('金额必须是大于 0 的数字', 'error')
        return redirect(url_for('recurring_page'))

    db.execute(
        'INSERT INTO recurring_rules '
        '(user_id, type, group_name, category, amount, note, day_of_month, is_active, last_generated_month, created_at) '
        'VALUES (?,?,?,?,?,?,?,1,NULL,?)',
        (user_id, tx_type, group_name, f.get('category'), amount, f.get('note', ''), day, datetime.now().isoformat())
    )
    db.commit()
    bump_data_version('recurring_add', {'type': tx_type, 'amount': amount, 'day_of_month': day, 'category': f.get('category'), 'user_id': user_id})
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '固定收支规则已添加'})
    flash('固定收支规则已添加', 'success')
    return redirect(url_for('recurring_page'))


@app.route('/recurring/<int:rule_id>/delete', methods=['POST'])
def delete_recurring(rule_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute('DELETE FROM recurring_rules WHERE id = ? AND user_id = ?', (rule_id, user_id))
    db.commit()
    bump_data_version('recurring_delete', {'id': rule_id, 'user_id': user_id})
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '规则已删除', 'id': rule_id})
    flash('规则已删除', 'success')
    return redirect(url_for('recurring_page'))


@app.route('/recurring/<int:rule_id>/toggle', methods=['POST'])
def toggle_recurring(rule_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT is_active FROM recurring_rules WHERE id = ? AND user_id = ?', (rule_id, user_id)).fetchone()
    if row:
        new_active = 0 if row['is_active'] else 1
        db.execute('UPDATE recurring_rules SET is_active = ? WHERE id = ? AND user_id = ?', (new_active, rule_id, user_id))
        db.commit()
        bump_data_version('recurring_toggle', {'id': rule_id, 'user_id': user_id})
        msg = '规则已停用' if row['is_active'] else '规则已启用'
        if is_ajax_request():
            return jsonify({'ok': True, 'message': msg, 'id': rule_id, 'is_active': new_active})
        flash(msg, 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未找到对应规则'}), 404
    return redirect(url_for('recurring_page'))


@app.route('/recurring/generate', methods=['POST'])
def manual_generate_recurring():
    user_id = get_current_user_id()
    count = generate_due_recurring(user_id)
    if count:
        bump_data_version('recurring_generate', {'count': count, 'user_id': user_id})
        msg = f'已生成 {count} 条本月固定收支记录'
    else:
        msg = '本月固定收支已全部生成，无需重复生成'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'count': count})
    flash(msg, 'success')
    return redirect(url_for('recurring_page'))


# ---------------------------------------------------------------------------
# 批量导入 (CSV / Excel)
# ---------------------------------------------------------------------------

def read_import_file(path):
    if path.lower().endswith('.csv'):
        return pd.read_csv(path, dtype=str)
    return pd.read_excel(path, dtype=str)


@app.route('/import')
def import_page():
    return render_template('import.html')


@app.route('/import/upload', methods=['POST'])
def import_upload():
    file = request.files.get('file')
    if not file or file.filename == '':
        flash('请选择要导入的文件', 'error')
        return redirect(url_for('import_page'))

    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in ('.csv', '.xlsx', '.xls'):
        flash('仅支持 .csv / .xlsx / .xls 文件', 'error')
        return redirect(url_for('import_page'))

    token = uuid.uuid4().hex
    saved_path = os.path.join(UPLOAD_DIR, token + ext)
    file.save(saved_path)

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        os.remove(saved_path)
        flash(f'文件读取失败：{e}', 'error')
        return redirect(url_for('import_page'))

    if df.empty:
        os.remove(saved_path)
        flash('文件中没有数据', 'error')
        return redirect(url_for('import_page'))

    columns = df.columns.tolist()
    preview_rows = df.head(10).fillna('').values.tolist()

    db = get_db()
    user_id = get_current_user_id()
    expense_categories = get_categories(db, 'expense', None, user_id)
    income_categories_flat = get_categories(db, 'income', 'main', user_id) + get_categories(db, 'income', 'side', user_id)

    return render_template(
        'import_preview.html',
        token=token, ext=ext, columns=columns, preview_rows=preview_rows, row_count=len(df),
        expense_categories=expense_categories, income_categories_flat=income_categories_flat,
    )


@app.route('/import/confirm', methods=['POST'])
def import_confirm():
    f = request.form
    token = f.get('token')
    ext = f.get('ext')
    saved_path = os.path.join(UPLOAD_DIR, token + ext) if token and ext else None

    if not saved_path or not os.path.exists(saved_path):
        flash('导入会话已过期，请重新上传文件', 'error')
        return redirect(url_for('import_page'))

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        flash(f'文件读取失败：{e}', 'error')
        return redirect(url_for('import_page'))

    date_col = f.get('date_col')
    amount_col = f.get('amount_col')
    type_mode = f.get('type_mode')  # sign / fixed_expense / fixed_income / column
    type_col = f.get('type_col')
    category_col = f.get('category_col')
    note_col = f.get('note_col')
    default_category = (f.get('default_category') or '未分类').strip()
    default_group = f.get('default_group') or 'main'
    date_format = f.get('date_format') or None

    user_id = get_current_user_id()
    db = get_db()
    inserted = 0
    skipped = 0
    now = datetime.now().isoformat()
    touched_expense_cat_months = set()

    for _, row in df.iterrows():
        try:
            raw_date = str(row[date_col]).strip()
            if date_format:
                dt = datetime.strptime(raw_date, date_format)
            else:
                dt = pd.to_datetime(raw_date)
            tx_date = dt.strftime('%Y-%m-%d')

            raw_amount = str(row[amount_col])
            cleaned = re.sub(r'[^\d.\-]', '', raw_amount)
            amount = float(cleaned)

            if type_mode == 'fixed_expense':
                tx_type = 'expense'
            elif type_mode == 'fixed_income':
                tx_type = 'income'
            elif type_mode == 'column' and type_col:
                raw_type = str(row[type_col]).strip()
                tx_type = 'income' if ('收入' in raw_type or raw_type.lower() == 'income') else 'expense'
            else:  # sign
                tx_type = 'income' if amount >= 0 else 'expense'

            amount = abs(amount)
            if amount == 0:
                skipped += 1
                continue

            group_name = default_group if tx_type == 'income' else None
            category = default_category
            if category_col:
                raw_cat = str(row[category_col]).strip()
                if raw_cat:
                    category = raw_cat
            note = str(row[note_col]).strip() if note_col else ''

            db.execute(
                'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?,?,?,?,?,?,?,?,?)',
                (user_id, tx_date, tx_type, group_name, category, amount, note, 'import', now)
            )
            inserted += 1
            if tx_type == 'expense':
                touched_expense_cat_months.add((category, tx_date[:7]))
        except Exception:
            skipped += 1
            continue

    db.commit()
    os.remove(saved_path)

    for cat, cat_month in touched_expense_cat_months:
        alert = check_and_record_budget_alerts(db, user_id, cat, cat_month)
        if alert:
            flash(alert['message'], 'warning' if alert['threshold'] < 100 else 'error')

    flash(f'导入完成：成功 {inserted} 条，跳过 {skipped} 条', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# 小票识别与智能 AA 分账 (Split Bill)
# ---------------------------------------------------------------------------

def parse_receipt_text_to_items(raw_text):
    """
    全球通用小票解析引擎 (Global Universal Receipt Engine)
    支持美欧、中日韩、东南亚等多国货币符号、国际数字格式、多语种税制与小费/服务费结构。
    """
    if not raw_text:
        return {'items': [], 'subtotal': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'total': 0.0, 'currency_symbol': 'RM'}

    # 1. 货币符号自适应探测
    currency_symbol = 'RM'
    if re.search(r'\b(?:RM|MYR)\b', raw_text, re.IGNORECASE):
        currency_symbol = 'RM'
    elif re.search(r'(?:S\$|\bSGD\b|GST\s*REG)', raw_text, re.IGNORECASE):
        currency_symbol = 'S$'
    elif re.search(r'(?:€|\bEUR\b|TTC|TVA|HT\b)', raw_text):
        currency_symbol = '€'
    elif re.search(r'(?:£|\bGBP\b)', raw_text):
        currency_symbol = '£'
    elif re.search(r'(?:¥|円|\bJPY\b|お会計|消費税)', raw_text):
        currency_symbol = '¥'
    elif re.search(r'(?:₩|원|\bKRW\b|결제|부가세)', raw_text):
        currency_symbol = '₩'
    elif re.search(r'(?:฿|\bTHB\b)', raw_text):
        currency_symbol = '฿'
    elif re.search(r'(?:Rp|\bIDR\b)', raw_text):
        currency_symbol = 'Rp'
    elif re.search(r'(?:₫|\bVND\b)', raw_text):
        currency_symbol = '₫'
    elif re.search(r'(?:NT\$|\bTWD\b)', raw_text):
        currency_symbol = 'NT$'
    elif re.search(r'(?:HK\$|\bHKD\b)', raw_text):
        currency_symbol = 'HK$'
    elif re.search(r'\$', raw_text):
        currency_symbol = '$'
    elif re.search(r'[\u4e00-\u9fa5]', raw_text):
        currency_symbol = '¥' if ('¥' in raw_text or '元' in raw_text or '微信' in raw_text or '支付宝' in raw_text) else 'RM'

    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]

    items = []
    subtotal = 0.0
    service_charge = 0.0
    service_rate = 0.0
    tax = 0.0
    tax_rate = 0.0
    discount = 0.0
    rounding = 0.0
    total = 0.0

    # 常见支付与结算方式（必须排除，不能当作消费菜品）
    exclude_payment_patterns = [
        r'\b(?:cash|change|change\s*due|tendered|due)\b',
        r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|nets|eftpos)\b',
        r'\b(?:tng|touch\s*[\'’]?n\s*go|grabpay|boost|alipay|wechat|duit\s*now|duitnow|paypay|line\s*pay|kakaopay|promptpay)\b',
        r'\b(?:carte\s*bancaire|rendu|barzahlung|kartenzahlung|r[uü]ckgeld|efectivo|cambio|contanti|resto)\b',
        r'(?:现金|找零|实收|找回|微信支付|支付宝|扫码支付|刷卡|お釣り|預り|クレジット|電子マネー|決済|현금|거스름돈|신용카드|tunai|baki|kembalian)'
    ]

    # 票头、地址、邮编、流水号、桌号、问候语等非商品行
    exclude_header_noise = [
        r'\b(?:invoice|receipt|bill\s*no|table|date|time|tel|phone|drawer|reg|cashier|server|chk|check\s*closed)\b',
        r'\b(?:terminal|merchant|auth|approval|ref|pax|order|order\s*#|siret|gst\s*reg|gst\s*no|co\s*no)\b',
        r'\b(?:items?\s*count|item\s*count|total\s*qty|qty\s*total|qty\s*item|price\s*\(myr\))\b',
        r'\b(?:thank\s*you|please\s*come|merci|danke|terima\s*kasih|arigato|grazia)\b',
        r'(?:单号|台号|客数|收银员|时间|品名|数量|金额|谢谢惠顾|欢迎再次光临|毎度ありがとうございます|またのお越しを|감사합니다|テーブル|人数|レジ|レシート)',
        r'^[x*\-_=+#\s\d|.:/]+$',
        r'\b[x*]{4,}\b'
    ]

    # 地址与邮编特征 (防止将 New York NY 10010 或 Singapore 329801 误认为商品)
    address_keywords = [
        'road', 'street', 'avenue', 'boulevard', 'jalan', 'lorong', 'lane', 'park', 'block',
        'blvd', 'ave', 'st.', 'rd.', 'singapore', 'new york', 'penang', 'paris', 'tokyo',
        '区', '路', '街', '号', '巷', '道', '市', '省'
    ]

    def normalize_numbers(raw_str):
        s = raw_str
        # 欧洲逗号小数：6,40 € 或 23,55 -> 6.40, 23.55
        s = re.sub(r'(\d+),(\d{2})(?:\s*(?:€|EUR|\b))', r'\1.\2', s)
        # 常见千分位：1,480 或 1,480.00 -> 1480 或 1480.00
        s = re.sub(r'(\d+),(\d{3})\b', r'\1\2', s)
        # 异常空格小数：4 .95 -> 4.95
        s = re.sub(r'(\d+)\s*\.\s*(\d+)', r'\1.\2', s)
        # 修正热敏纸误识别 ¥ 字母 (如 ¥t -> Vt)
        s = re.sub(r'¥([A-Za-z])', r'V\1', s)
        return s

    for line in lines:
        clean_line = normalize_numbers(line)
        lower = clean_line.lower()

        # 1. 匹配小费与服务费 (Tip, Gratuity, Service Charge, Svc Chg, SC, 服务费, 席料)
        is_sc_line = (any((re.search(r'\b' + re.escape(k) + r'\b', lower) is not None) if len(k) <= 3 else (k in lower) for k in ['tip', 'gratuity', 'pourboire', 'trinkgeld', 'service charge', 'svc charge', 'svc chg', 'service fee', 'sc']) or
                      any(k in clean_line for k in ['服务费', '服務費', 'お通し', '席料', '봉사료']))
        if is_sc_line and not any(k in clean_line for k in ['茶位', '调料']):
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                service_charge = float(amounts[-1])
            continue

        # 2. 匹配政府税 / 增值税 / VAT / GST / SST / TVA / MwSt / IVA / 消费税 / 부가세
        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'sales tax', 'vat', 'tva', 'mwst', 'ust', 'iva', 'tax']) or any(k in clean_line for k in ['消费税', '消費税', '增值税', '税费', '税额', '内税', '外税', '부가세']):
            if 'total' not in lower and 'subtotal' not in lower and '合计' not in clean_line and '小计' not in clean_line and '小計' not in clean_line:
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    tax = float(amounts[-1])
                continue

        # 3. 匹配抹零与舍入 (Rounding, Bill Rounding, Rnd, 抹零)
        if any(k in lower for k in ['rounding', 'bill rounding', 'round adj', 'rnd']) or '抹零' in clean_line or '舍入' in clean_line:
            m_rnd = re.search(r'([-+]?\s*[0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if m_rnd:
                rnd_val = float(m_rnd.group(1).replace(' ', ''))
                # 抹零金额本质上只会是很小的调整（通常在 -1 到 +1 之间），如果 OCR 把这行
                # 认错成一个离谱的大数字（比如把 "0.00" 认成 "8"），与其照单全收一个明显不
                # 合理的抹零金额，不如直接当作没认出来，维持默认的 0.00。
                if abs(rnd_val) <= 1.0:
                    rounding = rnd_val
            continue

        # 4. 匹配优惠与折扣 (Discount, Promo, Voucher, Rebate, 优惠, 折扣, 满减, 割引, 할인)
        if any(k in lower for k in ['discount', 'promo', 'voucher', 'rebate', 'remise', 'rabatt', 'descuento']) or any(k in clean_line for k in ['优惠', '折扣', '满减', '抵扣', '割引', '値引', '할인']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                discount = float(amounts[-1])
            continue

        # 5. 匹配小计 Subtotal / Total HT / Zwischensumme / 小计 / 小計 / 消费小计
        if any(k in lower for k in ['subtotal', 'sub-total', 'total ht', 'zwischensumme', 'sous-total', 'net amount']) or any(k in clean_line for k in ['小计', '小計', '消费小计', '小 計']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                subtotal = float(amounts[-1])
            continue

        # 6. 匹配总金额 Total / Grand Total / Total TTC / Gesamtbetrag / 合计 / 总计 / 实付 / お会計 / 合計金額 / 결제금액 / Jumlah
        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'amount due', 'total payable', 'amount payable', 'total ttc', 'gesamtbetrag', 'endbetrag', 'importe total', 'totale', 'total', 'jumlah']) or any(k in clean_line for k in ['合计', '总计', '实付', '实收', '应收', '结算', 'お会計', '合計金額', '合計', '합계', '결제금액', '총금액']):
            if 'total ht' not in lower and 'subtotal' not in lower and '消费小计' not in clean_line:
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    total = float(amounts[-1])
                continue

        # 7. 排除支付方式行与噪声行
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_payment_patterns):
            continue
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_header_noise):
            continue

        # 8. 排除地址行中的邮编识别 (如 NY 10010, Singapore 329801)
        if any(kw in lower for kw in address_keywords):
            if re.search(r'\b\d{4,6}\b\s*$', clean_line):
                continue

        # 8.5 排除"单价/份"标注的延续行 (如 "(Takeaway) (14.90/ea)")
        # 有些收银系统 (如 FEEDME SMART POS) 会把品项拆成好几个原始行输出：第一行是
        # "数量 品名 ... 价格"，接下来还会有一行专门重复标注 "(单价/ea)"。这种延续行本身
        # 不是新的品项，只是把已经在第一行抓到的价格再讲一次，如果不排除掉，会被误判成
        # 一笔新的、品名乱七八糟的品项 (比如把 "(Takeaway)" 这几个字当成品名)。
        if re.search(r'[0-9]+\.?[0-9]*\s*/\s*ea\b', lower):
            continue

        # 9. 提取常规单品行：找这一行里"最后一个长得像价格的数字"，价格前面当品名，
        # 价格后面不管是什么内容（行尾常见的 OCR 乱码符号、单位标注、多余空白等）一律丢弃，
        # 不要求行尾必须精确符合某个允许字符的白名单——真实拍照识别出来的文字，行尾常常会
        # 带一两个杂讯符号（比如全形逗号、竖线），只要求"精确匹配到行尾"很容易被这类杂讯拖累
        # 到整行都抓不到，这里改成只找价格本身，价格后面的东西直接忽略。
        price_pattern = re.compile(
            r'(?:RM|MYR|\$|S\$|€|EUR|£|GBP|¥|円|₩|원|฿|Rp|₫)\s*[0-9]+(?:\.[0-9]{1,2})?'
            r'|(?<![0-9.])[0-9]+\.[0-9]{1,2}(?![0-9])',
            re.IGNORECASE
        )
        price_matches = list(price_pattern.finditer(clean_line))
        m_item = price_matches[-1] if price_matches else None
        if m_item:
            name_raw = clean_line[:m_item.start()].strip(' -:\t#$*¥€£“"\'|.,;，、')
            name_raw = re.sub(r'^(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'\s*(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*$', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?(?:Takeaway|TA|Dine[- ]in)[)）]?\s*(?:\([0-9.]+/ea\))?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?[0-9.]+/ea[)）]?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = name_raw.strip(' -:\t#$*¥“"\'|.,;，、')

            num_match = re.search(r'[0-9]+(?:\.[0-9]{1,2})?', m_item.group())
            price_val = float(num_match.group()) if num_match else 0.0

            # 异常超大金额保护 (非 JPY/KRW/VND/IDR 等大面额货币时，单品价格不应超过 5000)
            if currency_symbol in ['$', '€', '£', 'RM', 'S$'] and price_val > 5000:
                continue

            if name_raw and len(name_raw) >= 2 and price_val > 0:
                qty = 1
                m_qty_prefix = re.match(r'^(\d+)\s*[xX*]?\s+(.*)$', name_raw)
                m_qty_suffix = re.search(r'^(.*?)\s+(\d+)\s*$', name_raw)
                if m_qty_prefix:
                    qty = int(m_qty_prefix.group(1))
                    name_raw = m_qty_prefix.group(2).strip(' -:\t#$*')
                elif m_qty_suffix and len(m_qty_suffix.group(1)) >= 2:
                    qty = int(m_qty_suffix.group(2))
                    name_raw = m_qty_suffix.group(1).strip(' -:\t#$*')

                # 常见"数量 + 品名 + 单价 + 小计"同一行的排版（如 "2 Roti Canai 1.10 2.20"），
                # 上面已经把最后一个数字(小计)抓成 price，但单价可能还残留在 name_raw 尾部，
                # 例如 name_raw 会变成 "Roti Canai 1.10"。这里把这种残留的单价数字去掉，
                # 不然品名会被误黏上一个价钱。
                name_raw = re.sub(
                    r'\s+(?:RM|MYR|\$|S\$|€|£|¥|円|₩)?\s*[0-9]+\.[0-9]{2}\s*$',
                    '',
                    name_raw,
                    flags=re.IGNORECASE
                ).strip(' -:\t#$*')

                items.append({
                    'name': name_raw,
                    'price': price_val,
                    'quantity': qty
                })

    # 若未找到 subtotal，则从 items 求和
    calc_subtotal = sum(i['price'] for i in items)
    if subtotal == 0:
        subtotal = round(calc_subtotal, 2)

    # 比例换算
    if service_charge == 0 and service_rate > 0 and subtotal > 0:
        service_charge = round(subtotal * (service_rate / 100), 2)
    if tax == 0 and tax_rate > 0 and subtotal > 0:
        tax = round((subtotal + service_charge) * (tax_rate / 100), 2)

    if total == 0:
        total = round(subtotal - discount + service_charge + tax + rounding, 2)

    return {
        'items': items,
        'subtotal': subtotal,
        'service_charge': service_charge,
        'tax': tax,
        'discount': discount,
        'rounding': rounding,
        'total': total,
        'currency_symbol': currency_symbol
    }


@app.route('/split-bill')
def split_bill_page():
    """小票拍照 AA 分账页面"""
    return render_template('split_bill.html', today=date.today().isoformat())


@app.route('/split-bill/parse-text', methods=['POST'])
@csrf.exempt
def split_bill_parse_text():
    """解析小票文本或粘贴内容"""
    text = request.form.get('text', '').strip()
    if not text:
        return jsonify({'ok': False, 'message': '未提供小票内容'}), 400

    parsed = parse_receipt_text_to_items(text)
    return jsonify({'ok': True, 'data': parsed})


_rapid_ocr_engine = None

def get_rapid_ocr():
    global _rapid_ocr_engine
    if _rapid_ocr_engine is None:
        try:
            from rapidocr_onnxruntime import RapidOCR
            _rapid_ocr_engine = RapidOCR(
                det_unclip_ratio=1.9,
                det_db_box_thresh=0.4,
                det_db_unclip_ratio=1.9
            )
            app.logger.info("RapidOCR engine initialized successfully with receipt-optimized params")
        except Exception as e:
            app.logger.warning("RapidOCR engine unavailable: %s", e)
            _rapid_ocr_engine = False
    return _rapid_ocr_engine if _rapid_ocr_engine is not False else None


ACCOUNT_TYPES = [
    ('savings',      '储蓄账户 (Savings)'),
    ('current',      '活期账户 (Current)'),
    ('credit_card',  '信用卡 (Credit Card)'),
    ('e_wallet',     '电子钱包 (E-Wallet)'),
    ('investment',   '投资账户 (Investment)'),
    ('cash',         '现金 (Cash)'),
    ('other',        '其他 (Other)'),
]

ACCOUNT_CURRENCIES = ['MYR', 'USD', 'SGD', 'CNY', 'EUR', 'GBP', 'AUD', 'JPY']


def cluster_ocr_blocks_to_lines(ocr_result):
    """按垂直坐标与水平坐标几何对齐同一行文本（品名在左，单价在右）"""
    if not ocr_result:
        return ""
    blocks = []
    for box, text, score in ocr_result:
        cy = (box[0][1] + box[2][1]) / 2
        cx = (box[0][0] + box[1][0]) / 2
        h = abs(box[2][1] - box[0][1])
        blocks.append({'cx': cx, 'cy': cy, 'h': h, 'text': text.strip()})

    blocks.sort(key=lambda b: b['cy'])
    rows = []
    for b in blocks:
        merged = False
        for r in rows:
            avg_cy = sum(item['cy'] for item in r) / len(r)
            avg_h = sum(item['h'] for item in r) / len(r)
            if abs(b['cy'] - avg_cy) < max(12.0, avg_h * 0.7):
                r.append(b)
                merged = True
                break
        if not merged:
            rows.append([b])

    merged_lines = []
    for r in rows:
        r.sort(key=lambda item: item['cx'])
        merged_lines.append(' '.join(item['text'] for item in r))

    return '\n'.join(merged_lines)


def get_ocr_orientation_stats(ocr_res, img_h):
    """分析 OCR 识别框的长宽比与底部结算关键词位置，评估当前图片的朝向是否为正立"""
    if not ocr_res:
        return {'horiz': 0, 'vert': 0, 'footer_bottom': 0, 'footer_top': 0, 'count': 0}
    horiz = 0
    vert = 0
    footer_bottom = 0
    footer_top = 0
    footer_keywords = [
        'total', 'subtotal', 'sub-total', 'grand total', 'net total', 'change', 'rounding',
        'duitnow', 'cash', 'card', 'visa', 'mastercard', 'thank', 'scan', 'pos', 'powered',
        'feedme', 'tax', 'service', 'balance', '合计', '总计', '小计', '实收', '找零', '谢谢',
        'お会計', '合計', '합계'
    ]
    import numpy as np

    for box, text, score in ocr_res:
        w = np.linalg.norm(np.array(box[1]) - np.array(box[0]))
        h = np.linalg.norm(np.array(box[3]) - np.array(box[0]))
        cy = (box[0][1] + box[2][1]) / 2
        if w > h * 1.15:
            horiz += 1
        elif h > w * 1.15:
            vert += 1

        t_lower = text.lower()
        if any(k in t_lower for k in footer_keywords):
            if cy > img_h * 0.45:
                footer_bottom += 1
            else:
                footer_top += 1

    return {
        'horiz': horiz,
        'vert': vert,
        'footer_bottom': footer_bottom,
        'footer_top': footer_top,
        'count': len(ocr_res)
    }


def preprocess_receipt_for_ocr(pil_img):
    """
    针对热敏纸小票的轻量预处理管道：
    1. 动态自适应缩放（防止字号过小导致漏检）
    2. 灰度化 + CLAHE（限制对比度自适应直方图均衡化，消除阴影并增强文字反差）
    3. 轻度保边去噪（避免热敏纸噪点被误检为标点）
    """
    import cv2
    import numpy as np

    img = np.array(pil_img)

    # 统一通道：RGBA / RGB 转 BGR
    if len(img.shape) == 3:
        if img.shape[2] == 4:
            img = cv2.cvtColor(img, cv2.COLOR_RGBA2BGR)
        else:
            img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
    else:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)

    h, w = img.shape[:2]

    # 1. 动态缩放：小票宽度如果过小，文字笔画会粘连或丢失，保证短边至少在 1000px 左右
    min_side = min(h, w)
    if min_side < 1000 and min_side > 0:
        scale = 1200.0 / min_side
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

    # 2. 转灰度并应用 CLAHE（消除拍小票时的手部阴影与反光）
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced_gray = clahe.apply(gray)

    # 3. 轻度保边去噪（避免热敏纸噪点被误检为标点）
    denoised = cv2.bilateralFilter(enhanced_gray, d=5, sigmaColor=50, sigmaSpace=50)

    # 转回 3 通道供给 RapidOCR 推理
    final_img = cv2.cvtColor(denoised, cv2.COLOR_GRAY2BGR)
    return final_img


def smart_orient_receipt_ocr(pil_img, engine):
    """
    强化版小票 OCR 管道：
    加入 CLAHE 增强、动态放大与置信度保护
    """
    # 0. 预处理原图
    processed_0 = preprocess_receipt_for_ocr(pil_img)

    # 1. 初始角度 (0°) 测试识别
    res0, _ = engine(processed_0)
    if res0:
        stats0 = get_ocr_orientation_stats(res0, processed_0.shape[0])
        raw_text0 = cluster_ocr_blocks_to_lines(res0)
        parsed0 = parse_receipt_text_to_items(raw_text0)

        # 快速直出条件：横向文本占绝对优势，且解析出结构化结果
        if stats0['horiz'] > max(5, stats0['vert'] * 1.5) and (stats0['footer_bottom'] >= stats0['footer_top'] or len(parsed0['items']) > 0):
            return res0, raw_text0, parsed0, 0

        # 候选角度策略
        if stats0['vert'] >= stats0['horiz']:
            test_angles = [90, 270]
        else:
            test_angles = [180, 90, 270]

        score0 = (
            (stats0['horiz'] - stats0['vert'] * 2) +
            (stats0['footer_bottom'] - stats0['footer_top']) * 6 +
            len(parsed0['items']) * 15 +
            (20 if parsed0['total'] > 0 else 0)
        )
    else:
        # 0° 未检出任何文字框（多为纯侧向 90°/270° 或倒置 180°），必须穷举候选角度，绝不能在此直接放弃
        test_angles = [90, 270, 180]
        stats0 = {'horiz': 0, 'vert': 0, 'footer_bottom': 0, 'footer_top': 0, 'count': 0}
        raw_text0 = ""
        parsed0 = {'items': [], 'subtotal': 0.0, 'total': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'currency_symbol': 'RM'}
        score0 = -999

    candidates = []
    for angle in test_angles:
        rot_img = pil_img.rotate(angle, expand=True)
        # 对旋转后的图片同样走标准化预处理管道
        rot_processed = preprocess_receipt_for_ocr(rot_img)
        r_res, _ = engine(rot_processed)
        if not r_res:
            continue

        r_stats = get_ocr_orientation_stats(r_res, rot_processed.shape[0])
        r_text = cluster_ocr_blocks_to_lines(r_res)
        r_parsed = parse_receipt_text_to_items(r_text)

        score = (
            (r_stats['horiz'] - r_stats['vert'] * 2) +
            (r_stats['footer_bottom'] - r_stats['footer_top']) * 6 +
            len(r_parsed['items']) * 15 +
            (20 if r_parsed['total'] > 0 else 0)
        )
        candidates.append((score, angle, r_res, r_text, r_parsed))

    if candidates:
        candidates.sort(key=lambda x: x[0], reverse=True)
        best = candidates[0]
        if best[0] > score0:
            app.logger.info("Auto-corrected receipt orientation by %d° (score %d vs original %d)", best[1], best[0], score0)
            return best[2], best[3], best[4], best[1]

    return res0, raw_text0, parsed0, 0


@app.route('/split-bill/ocr-upload', methods=['POST'])
@csrf.exempt
def split_bill_ocr_upload():
    """本地 RapidOCR 深度学习小票识别接口（零云端依赖，支持全方向自适应纠偏与同行对齐）"""
    file = request.files.get('file') or request.files.get('receipt_image')
    if not file or not file.filename:
        return jsonify({'ok': False, 'message': '未检测到上传的小票照片'}), 400

    engine = get_rapid_ocr()
    if not engine:
        return jsonify({'ok': False, 'message': '本地 RapidOCR 引擎未安装或初始化失败'}), 500

    try:
        img_bytes = file.read()
        import io
        from PIL import Image, ImageOps
        pil_img = Image.open(io.BytesIO(img_bytes))
        # 优先读取 EXIF 标签纠正旋转
        try:
            pil_img = ImageOps.exif_transpose(pil_img)
        except Exception:
            pass
        if pil_img.mode != 'RGB':
            pil_img = pil_img.convert('RGB')

        # 核心：即使无 EXIF 标签（如 WhatsApp 压缩图），也能依据文字框几何与小票布局自动旋转纠正
        result, raw_text, parsed, rot = smart_orient_receipt_ocr(pil_img, engine)
        default_parsed = {
            'items': [],
            'subtotal': 0.0,
            'total': 0.0,
            'service_charge': 0.0,
            'tax': 0.0,
            'discount': 0.0,
            'rounding': 0.0,
            'currency_symbol': 'RM'
        }
        if not result or not raw_text:
            return jsonify({
                'ok': False,
                'message': '未能识别出文字，请确保小票清晰平整',
                'data': default_parsed,
                'raw_text': '',
                'rotation_applied': 0,
                'engine': 'rapidocr'
            }), 200

        if not parsed:
            parsed = default_parsed

        parsed['engine'] = 'rapidocr'
        parsed['orientation_corrected'] = bool(rot != 0)
        return jsonify({'ok': True, 'data': parsed, 'raw_text': raw_text, 'rotation_applied': rot, 'engine': 'rapidocr'})
    except Exception as e:
        app.logger.error("RapidOCR recognition failed: %s", e)
        return jsonify({'ok': False, 'message': f'小票识别失败: {str(e)}'}), 500


@app.route('/split-bill/save-record', methods=['POST'])
def split_bill_save_record():
    """将 AA 分账中属于自己的部分一键存入主账本"""
    f = request.form
    try:
        amount = float(f.get('amount', 0))
    except ValueError:
        amount = 0

    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '记账金额必须大于 0'}), 400
        flash('记账金额必须大于 0', 'error')
        return redirect(url_for('split_bill_page'))

    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()
    note = f.get('note', '').strip() or '聚餐 AA 分摊消费'
    tx_date = f.get('date') or date.today().isoformat()
    category = f.get('category') or '餐饮'

    db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?,?,?,?,?,?,?,?,?)',
        (user_id, tx_date, 'expense', None, category, amount, note, 'split_bill', now)
    )
    db.commit()
    bump_data_version('split_bill', {'note': note, 'amount': amount, 'category': category, 'type': 'expense', 'user_id': user_id})

    budget_alert = check_and_record_budget_alerts(db, user_id, category, tx_date[:7])
    if budget_alert and not is_ajax_request():
        flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

    msg = f'已成功记入支出：{note} {money_filter(amount)}'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'budget_alert': budget_alert})
    flash(msg, 'success')
    return redirect(url_for('records'))


# ============================================================
# 负债、信用卡与分期付款追踪路由 (Liabilities & Installment Tracker)
# ============================================================
@app.route('/liabilities')
def liabilities_page():
    user_id = get_current_user_id()
    db = get_db()
    
    target_month_str = request.args.get('month', '').strip()
    if not target_month_str or not re.match(r'^\d{4}-\d{2}$', target_month_str):
        target_month_str = date.today().strftime('%Y-%m')
    
    try:
        t_year, t_month = map(int, target_month_str.split('-'))
    except Exception:
        today = date.today()
        t_year, t_month = today.year, today.month
        target_month_str = f"{t_year:04d}-{t_month:02d}"

    # 1. 获取当月刚性还款现金流排程事件
    cashflow_data = get_monthly_cashflow_events(db, user_id, t_year, t_month)

    # 2. 获取分期付款列表
    installments = db.execute('''
        SELECT i.*, a.name as card_name, a.due_day as card_due_day
        FROM installments i
        LEFT JOIN accounts a ON i.account_id = a.id
        WHERE i.user_id = ?
        ORDER BY CASE WHEN i.status='active' THEN 0 ELSE 1 END, i.id DESC
    ''', (user_id,)).fetchall()

    # 计算各分期实时剩余本金与进度
    inst_list = []
    total_inst_debt = 0.0
    for inst in installments:
        item = dict(inst)
        tenure = item.get('tenure_months') or 1
        paid = item.get('paid_periods') or 0
        rem_periods = max(0, tenure - paid)
        total_amt = item.get('total_amount') or 0.0
        monthly_amt = item.get('monthly_amount') or 0.0
        rem_bal = max(0.0, round(total_amt - (monthly_amt * paid), 2))
        
        # 预估结清年月
        try:
            f_dt = datetime.strptime(item.get('first_due_date'), "%Y-%m-%d").date()
            from liabilities_tracker import add_months_clamped
            settle_dt = add_months_clamped(f_dt, tenure - 1)
            item['settle_month'] = settle_dt.strftime('%Y-%m')
        except Exception:
            item['settle_month'] = '—'

        item['remaining_periods'] = rem_periods
        item['remaining_balance'] = rem_bal
        item['progress_pct'] = min(100, int((paid / tenure) * 100)) if tenure > 0 else 100
        if item.get('status') == 'active':
            total_inst_debt += rem_bal
        inst_list.append(item)

    # 3. 获取固定贷款列表
    loans = db.execute('''
        SELECT l.*, a.name as account_name
        FROM loans l
        LEFT JOIN accounts a ON l.debit_account_id = a.id
        WHERE l.user_id = ?
        ORDER BY CASE WHEN l.status='active' THEN 0 ELSE 1 END, l.id DESC
    ''', (user_id,)).fetchall()

    loan_list = []
    total_loan_debt = 0.0
    for loan in loans:
        item = dict(loan)
        tenure = item.get('tenure_months') or 1
        paid = item.get('paid_periods') or 0
        item['remaining_periods'] = max(0, tenure - paid)
        item['progress_pct'] = min(100, int((paid / tenure) * 100)) if tenure > 0 else 100
        if item.get('status') == 'active':
            total_loan_debt += (item.get('remaining_balance') or 0.0)
        loan_list.append(item)

    # 4. 获取银行卡与信用卡列表
    accounts = db.execute('''
        SELECT * FROM accounts
        WHERE user_id = ? AND is_active = 1
        ORDER BY type, name
    ''', (user_id,)).fetchall()

    total_debt = total_inst_debt + total_loan_debt

    return render_template(
        'liabilities.html',
        target_month=target_month_str,
        cashflow=cashflow_data,
        installments=inst_list,
        loans=loan_list,
        accounts=accounts,
        total_debt=total_debt,
        total_inst_debt=total_inst_debt,
        total_loan_debt=total_loan_debt
    )


@app.route('/api/liabilities/installment', methods=['POST'])
def api_add_installment():
    user_id = get_current_user_id()
    db = get_db()
    
    title = (request.form.get('title') or '').strip()
    if not title:
        flash('请输入分期项目名称', 'error')
        return redirect(url_for('liabilities_page'))
    
    try:
        total_amount = float(request.form.get('total_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
    except (ValueError, TypeError):
        flash('分期金额或期数格式不正确', 'error')
        return redirect(url_for('liabilities_page'))

    first_due_date = request.form.get('first_due_date') or date.today().isoformat()
    account_id = request.form.get('account_id') or None
    note = (request.form.get('note') or '').strip()

    # 计算每月标准摊销
    monthly_amount = round(total_amount / tenure_months, 2) if tenure_months > 0 else total_amount
    status = 'completed' if paid_periods >= tenure_months else 'active'

    db.execute('''
        INSERT INTO installments (
            user_id, account_id, title, total_amount, tenure_months, paid_periods,
            monthly_amount, first_due_date, status, note, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        user_id, account_id, title, total_amount, tenure_months, paid_periods,
        monthly_amount, first_due_date, status, note, datetime.now().isoformat()
    ))
    db.commit()
    flash(f'已成功添加免息分期项目：{title}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/loan', methods=['POST'])
def api_add_loan():
    user_id = get_current_user_id()
    db = get_db()
    
    title = (request.form.get('title') or '').strip()
    if not title:
        flash('请输入贷款项目名称', 'error')
        return redirect(url_for('liabilities_page'))
    
    try:
        loan_amount = float(request.form.get('loan_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
        interest_rate = float(request.form.get('annual_interest_rate', 0.0))
        due_day = int(request.form.get('due_day', 5))
    except (ValueError, TypeError):
        flash('贷款金额、利率或期数格式不正确', 'error')
        return redirect(url_for('liabilities_page'))

    method = request.form.get('method') or 'reducing_balance'
    debit_account_id = request.form.get('debit_account_id') or None
    start_date = request.form.get('start_date') or date.today().isoformat()
    note = (request.form.get('note') or '').strip()

    # 若用户手动输入了月供金额，则优先使用；否则自动用财务算法推算
    custom_monthly = request.form.get('monthly_payment')
    try:
        if custom_monthly and float(custom_monthly) > 0:
            monthly_payment = float(custom_monthly)
            # 简单剩余本金预估
            remaining_balance = max(0.0, round(loan_amount - (loan_amount / tenure_months * paid_periods), 2))
        else:
            from liabilities_tracker import generate_amortization_schedule
            sched = generate_amortization_schedule(loan_amount, tenure_months, start_date, interest_rate, method)
            monthly_payment = sched[0]['total_amount']
            idx = min(paid_periods, len(sched) - 1)
            remaining_balance = sched[idx]['remaining_balance'] if paid_periods < len(sched) else 0.0
    except Exception as e:
        app.logger.warning("Calculate loan schedule failed: %s", e)
        monthly_payment = round(loan_amount / tenure_months, 2)
        remaining_balance = loan_amount

    status = 'completed' if paid_periods >= tenure_months else 'active'

    db.execute('''
        INSERT INTO loans (
            user_id, debit_account_id, title, loan_amount, remaining_balance,
            tenure_months, paid_periods, annual_interest_rate, method,
            monthly_payment, due_day, start_date, status, note, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        user_id, debit_account_id, title, loan_amount, remaining_balance,
        tenure_months, paid_periods, interest_rate, method,
        monthly_payment, due_day, start_date, status, note, datetime.now().isoformat()
    ))
    db.commit()
    flash(f'已成功添加贷款记录：{title}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/account', methods=['POST'])
def api_add_account():
    user_id = get_current_user_id()
    db = get_db()
    
    name = (request.form.get('name') or '').strip()
    acc_type = request.form.get('type') or 'credit_card'
    if not name:
        flash('请输入账户/卡片名称', 'error')
        return redirect(url_for('liabilities_page'))

    credit_limit = float(request.form.get('credit_limit') or 0.0)
    statement_day = int(request.form.get('statement_day') or 15)
    due_day = int(request.form.get('due_day') or 5)

    db.execute('''
        INSERT INTO accounts (
            user_id, name, type, credit_limit, statement_day, due_day, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    ''', (user_id, name, acc_type, credit_limit, statement_day, due_day, datetime.now().isoformat()))
    db.commit()
    flash(f'已成功添加卡片/账户：{name}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/sync', methods=['POST'])
def api_sync_liabilities():
    user_id = get_current_user_id()
    db = get_db()
    today_str = date.today().isoformat()
    res = sync_installments_to_monthly_statement(db, today_str)
    msg = f"同步完成：已自动挂账 {res['syncedRecords']} 笔流水，已结清归档 {res['completedInstallments']} 笔分期。"
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'data': res})
    flash(msg, 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/<string:item_type>/<int:item_id>/delete', methods=['POST'])
def api_delete_liability(item_type, item_id):
    user_id = get_current_user_id()
    db = get_db()
    
    if item_type == 'installment':
        db.execute('DELETE FROM installments WHERE id = ? AND user_id = ?', (item_id, user_id))
    elif item_type == 'loan':
        db.execute('DELETE FROM loans WHERE id = ? AND user_id = ?', (item_id, user_id))
    elif item_type == 'account':
        db.execute('DELETE FROM accounts WHERE id = ? AND user_id = ?', (item_id, user_id))
    db.commit()
    flash('已删除该负债记录', 'success')
    return redirect(url_for('liabilities_page'))


# ---------------------------------------------------------------------------
# 账户管理 (Account Management) - 银行账户与信用卡
# ---------------------------------------------------------------------------

@app.route('/accounts')
def accounts_page():
    user_id = get_current_user_id()
    db = get_db()
    rows = db.execute(
        """SELECT a.*,
                  (SELECT COUNT(*) FROM subscriptions s WHERE s.payment_method_id = a.id AND s.status = 'ACTIVE') as sub_count,
                  (SELECT COUNT(*) FROM installments i WHERE i.account_id = a.id AND i.status = 'active') as install_count
           FROM accounts a
           WHERE a.user_id = ?
           ORDER BY a.is_active DESC, a.type ASC, a.name ASC""",
        (user_id,)
    ).fetchall()
    accounts = [dict(r) for r in rows]
    return render_template(
        'accounts.html',
        accounts=accounts,
        account_types=ACCOUNT_TYPES,
        currencies=ACCOUNT_CURRENCIES,
    )


@app.route('/api/accounts', methods=['POST'])
def manage_add_account():
    user_id = get_current_user_id()
    db = get_db()
    name = request.form.get('name', '').strip()
    acc_type = request.form.get('type', 'savings')
    currency = request.form.get('currency', 'MYR')
    credit_limit = request.form.get('credit_limit', '0') or '0'
    statement_day = request.form.get('statement_day', '') or None
    due_day = request.form.get('due_day', '') or None
    grace_period_days = request.form.get('grace_period_days', '20') or '20'
    note = request.form.get('note', '').strip() or None

    if not name:
        flash('账户名称不能为空', 'error')
        return redirect(url_for('accounts_page'))

    try:
        credit_limit_val = float(credit_limit)
    except ValueError:
        credit_limit_val = 0.0

    db.execute(
        """INSERT INTO accounts (user_id, name, type, credit_limit, statement_day, due_day,
                                  grace_period_days, currency, is_active, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)""",
        (user_id, name, acc_type, credit_limit_val,
         int(statement_day) if statement_day else None,
         int(due_day) if due_day else None,
         int(grace_period_days),
         currency,
         datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    )
    db.commit()
    flash(f'账户「{name}」已成功添加', 'success')
    return redirect(url_for('accounts_page'))


@app.route('/api/accounts/<int:acc_id>/edit', methods=['POST'])
def manage_edit_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    name = request.form.get('name', '').strip()
    acc_type = request.form.get('type', 'savings')
    currency = request.form.get('currency', 'MYR')
    credit_limit = request.form.get('credit_limit', '0') or '0'
    statement_day = request.form.get('statement_day', '') or None
    due_day = request.form.get('due_day', '') or None
    grace_period_days = request.form.get('grace_period_days', '20') or '20'

    if not name:
        flash('账户名称不能为空', 'error')
        return redirect(url_for('accounts_page'))

    try:
        credit_limit_val = float(credit_limit)
    except ValueError:
        credit_limit_val = 0.0

    db.execute(
        """UPDATE accounts
           SET name=?, type=?, credit_limit=?, statement_day=?, due_day=?,
               grace_period_days=?, currency=?
           WHERE id=? AND user_id=?""",
        (name, acc_type, credit_limit_val,
         int(statement_day) if statement_day else None,
         int(due_day) if due_day else None,
         int(grace_period_days), currency,
         acc_id, user_id)
    )
    db.commit()
    flash(f'账户「{name}」已更新', 'success')
    return redirect(url_for('accounts_page'))


@app.route('/api/accounts/<int:acc_id>/toggle', methods=['POST'])
def manage_toggle_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT is_active FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash('账户不存在', 'error')
        return redirect(url_for('accounts_page'))
    new_state = 0 if row['is_active'] else 1
    db.execute('UPDATE accounts SET is_active=? WHERE id=? AND user_id=?', (new_state, acc_id, user_id))
    db.commit()
    flash('账户状态已更新', 'success')
    return redirect(url_for('accounts_page'))


@app.route('/api/accounts/<int:acc_id>/delete', methods=['POST'])
def manage_delete_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT name FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash('账户不存在', 'error')
        return redirect(url_for('accounts_page'))
    # Unlink subscriptions and installments before deletion
    db.execute('UPDATE subscriptions SET payment_method_id=NULL WHERE payment_method_id=? AND user_id=?', (acc_id, user_id))
    db.execute('UPDATE installments SET account_id=NULL WHERE account_id=? AND user_id=?', (acc_id, user_id))
    db.execute('DELETE FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id))
    db.commit()
    flash(f'账户「{row["name"]}」已删除', 'success')
    return redirect(url_for('accounts_page'))


# ---------------------------------------------------------------------------
# 订阅服务大厅与续费提醒 (Subscription Management & Renewal Alerts)
# ---------------------------------------------------------------------------

@app.route('/subscriptions')
def subscriptions_page():
    user_id = get_current_user_id()
    db = get_db()

    rows = db.execute("""
        SELECT s.*, a.name as payment_method_name 
        FROM subscriptions s
        LEFT JOIN accounts a ON s.payment_method_id = a.id
        WHERE s.user_id = ?
        ORDER BY s.status ASC, s.next_billing_date ASC
    """, (user_id,)).fetchall()

    subs = []
    for r in rows:
        d = dict(r)
        start_dt = datetime.strptime(d["start_date"], "%Y-%m-%d").date() if isinstance(d.get("start_date"), str) else (d.get("start_date") or date.today())
        next_dt = datetime.strptime(d["next_billing_date"], "%Y-%m-%d").date() if isinstance(d.get("next_billing_date"), str) else (d.get("next_billing_date") or date.today())
        trial_dt = datetime.strptime(d["trial_end_date"], "%Y-%m-%d").date() if d.get("trial_end_date") and isinstance(d.get("trial_end_date"), str) else None

        subs.append(Subscription(
            id=d["id"],
            name=d["name"],
            category=d["category"],
            billing_cycle=BillingCycle(d["billing_cycle"]),
            cost=Decimal(str(d["cost"])),
            currency=d.get("currency", "MYR"),
            auto_renew=bool(d.get("auto_renew", 1)),
            start_date=start_dt,
            next_billing_date=next_dt,
            payment_method_id=d.get("payment_method_id"),
            payment_method_name=d.get("payment_method_name"),
            status=SubscriptionStatus(d.get("status", "ACTIVE")),
            cancellation_reminder_days=int(d.get("cancellation_reminder_days") or 3),
            is_trial=bool(d.get("is_trial", 0)),
            trial_end_date=trial_dt,
            target_to_cancel=bool(d.get("target_to_cancel", 0)),
            anchor_day=d.get("anchor_day") or (start_dt.day if start_dt else next_dt.day),
            note=d.get("note")
        ))

    today = date.today()
    dashboard_summary = buildSubscriptionDashboardSummary(subs, currentDate=today, targetCurrency="MYR")

    accounts = db.execute(
        "SELECT id, name, type, currency FROM accounts WHERE user_id = ? AND is_active = 1 ORDER BY name ASC",
        (user_id,)
    ).fetchall()

    # 统计分类月均等效分布
    category_breakdown = {}
    for s in subs:
        if s.status == SubscriptionStatus.ACTIVE:
            rate = default_mock_exchange_rate_provider(s.currency, "MYR")
            cost_myr = s.cost * rate
            if s.billing_cycle == BillingCycle.MONTHLY:
                m_cost = cost_myr
            elif s.billing_cycle == BillingCycle.WEEKLY:
                m_cost = (cost_myr * Decimal("52")) / Decimal("12")
            elif s.billing_cycle == BillingCycle.QUARTERLY:
                m_cost = cost_myr / Decimal("3")
            elif s.billing_cycle == BillingCycle.SEMI_ANNUAL:
                m_cost = cost_myr / Decimal("6")
            else:
                m_cost = cost_myr / Decimal("12")
            cat = s.category or "其他"
            category_breakdown[cat] = (category_breakdown.get(cat, Decimal("0")) + m_cost).quantize(Decimal("0.01"))

    return render_template(
        'subscriptions.html',
        summary=dashboard_summary,
        subscriptions=subs,
        accounts=accounts,
        category_breakdown=category_breakdown,
        today=today.isoformat(),
        today_date=today
    )


@app.route('/api/subscriptions', methods=['POST'])
def api_add_subscription():
    user_id = get_current_user_id()
    db = get_db()

    name = (request.form.get('name') or '').strip()
    category = (request.form.get('category') or '流媒体').strip()
    billing_cycle = (request.form.get('billing_cycle') or 'MONTHLY').upper()
    cost_str = (request.form.get('cost') or '0').strip()
    currency = (request.form.get('currency') or 'MYR').upper()
    start_date = request.form.get('start_date') or date.today().isoformat()
    next_billing_date = request.form.get('next_billing_date') or start_date
    payment_method_id = request.form.get('payment_method_id') or None
    cancellation_reminder_days = int(request.form.get('cancellation_reminder_days') or 3)
    auto_renew = 1 if request.form.get('auto_renew') in ('1', 'on', 'true') else 0
    is_trial = 1 if request.form.get('is_trial') in ('1', 'on', 'true') else 0
    trial_end_date = request.form.get('trial_end_date') or None
    target_to_cancel = 1 if request.form.get('target_to_cancel') in ('1', 'on', 'true') else 0
    note = (request.form.get('note') or '').strip()

    if not name:
        flash('请输入订阅服务名称', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
        if cost <= 0:
            raise ValueError()
    except ValueError:
        flash('请输入有效的扣费金额', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        anchor_day = datetime.strptime(next_billing_date, "%Y-%m-%d").day
    except Exception:
        try:
            anchor_day = datetime.strptime(start_date, "%Y-%m-%d").day
        except Exception:
            anchor_day = date.today().day

    db.execute("""
        INSERT INTO subscriptions (
            user_id, name, category, billing_cycle, cost, currency,
            auto_renew, start_date, next_billing_date, payment_method_id,
            status, cancellation_reminder_days, is_trial, trial_end_date,
            target_to_cancel, anchor_day, note, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
    """, (
        user_id, name, category, billing_cycle, cost, currency,
        auto_renew, start_date, next_billing_date, payment_method_id,
        cancellation_reminder_days, is_trial, trial_end_date,
        target_to_cancel, anchor_day, note
    ))
    db.commit()
    bump_data_version('subscription_add', user_id=user_id)
    flash(f'成功添加订阅服务：{name}', 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/edit', methods=['POST'])
def api_edit_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()

    name = (request.form.get('name') or '').strip()
    category = (request.form.get('category') or '流媒体').strip()
    billing_cycle = (request.form.get('billing_cycle') or 'MONTHLY').upper()
    cost_str = (request.form.get('cost') or '0').strip()
    currency = (request.form.get('currency') or 'MYR').upper()
    next_billing_date = request.form.get('next_billing_date') or date.today().isoformat()
    payment_method_id = request.form.get('payment_method_id') or None
    cancellation_reminder_days = int(request.form.get('cancellation_reminder_days') or 3)
    auto_renew = 1 if request.form.get('auto_renew') in ('1', 'on', 'true') else 0
    is_trial = 1 if request.form.get('is_trial') in ('1', 'on', 'true') else 0
    trial_end_date = request.form.get('trial_end_date') or None
    target_to_cancel = 1 if request.form.get('target_to_cancel') in ('1', 'on', 'true') else 0
    note = (request.form.get('note') or '').strip()

    if not name:
        flash('订阅名称不能为空', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
    except ValueError:
        flash('金额格式不正确', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        anchor_day = datetime.strptime(next_billing_date, "%Y-%m-%d").day
    except Exception:
        anchor_day = date.today().day

    db.execute("""
        UPDATE subscriptions
        SET name = ?, category = ?, billing_cycle = ?, cost = ?, currency = ?,
            next_billing_date = ?, payment_method_id = ?, cancellation_reminder_days = ?,
            auto_renew = ?, is_trial = ?, trial_end_date = ?, target_to_cancel = ?,
            anchor_day = ?, note = ?, updated_at = datetime('now')
        WHERE id = ? AND user_id = ?
    """, (
        name, category, billing_cycle, cost, currency,
        next_billing_date, payment_method_id, cancellation_reminder_days,
        auto_renew, is_trial, trial_end_date, target_to_cancel,
        anchor_day, note, sub_id, user_id
    ))
    db.commit()
    bump_data_version('subscription_edit', user_id=user_id)
    flash(f'已更新订阅服务：{name}', 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/toggle-status', methods=['POST'])
def api_toggle_subscription_status(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT status, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('找不到指定订阅记录', 'error')
        return redirect(url_for('subscriptions_page'))

    new_status = 'PAUSED' if row['status'] == 'ACTIVE' else 'ACTIVE'
    db.execute("UPDATE subscriptions SET status = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_status, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_status', user_id=user_id)
    state_desc = '已恢复活跃计费' if new_status == 'ACTIVE' else '已暂停扣款监控'
    flash(f"已将【{row['name']}】{state_desc}", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/toggle-cancel-target', methods=['POST'])
def api_toggle_cancel_target(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT target_to_cancel, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('找不到指定订阅记录', 'error')
        return redirect(url_for('subscriptions_page'))

    new_flag = 0 if row['target_to_cancel'] else 1
    db.execute("UPDATE subscriptions SET target_to_cancel = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_flag, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_cancel_target', user_id=user_id)
    tip = '已标记为【打算退订】，将在扣款前高亮预警拦截！' if new_flag else '已取消退订标记'
    flash(f"【{row['name']}】{tip}", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/roll', methods=['POST'])
def api_roll_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT * FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('找不到指定订阅记录', 'error')
        return redirect(url_for('subscriptions_page'))

    d = dict(row)
    curr_date = datetime.strptime(d['next_billing_date'], '%Y-%m-%d').date()
    cycle = BillingCycle(d['billing_cycle'])
    anchor_day = d.get('anchor_day') or curr_date.day

    new_date = rollToNextBillingDate(curr_date, cycle, anchor_day)

    # 可选：同步记入账本流水
    record_expense = request.form.get('record_expense') in ('1', 'on', 'true')
    if record_expense:
        db.execute("""
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at)
            VALUES (?, ?, 'expense', '订阅与周期固定', ?, ?, ?, 'subscription', datetime('now'))
        """, (
            user_id, curr_date.isoformat(), d['category'], float(d['cost']),
            f"订阅续费: {d['name']} ({d['billing_cycle']})"
        ))

    db.execute("""
        UPDATE subscriptions
        SET next_billing_date = ?, anchor_day = ?, updated_at = datetime('now')
        WHERE id = ? AND user_id = ?
    """, (new_date.isoformat(), anchor_day, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_roll', user_id=user_id)
    extra_msg = '，并已自动生成当期记账支出' if record_expense else ''
    flash(f"【{d['name']}】已成功续期至 {new_date.isoformat()}{extra_msg}！", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/delete', methods=['POST'])
def api_delete_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute("DELETE FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id))
    db.commit()
    bump_data_version('subscription_delete', user_id=user_id)
    flash('已删除该订阅服务记录', 'success')
    return redirect(url_for('subscriptions_page'))


# 启动时确保数据库初始化
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


