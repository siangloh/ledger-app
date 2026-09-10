import os
import re
import json
import uuid
import sqlite3
import requests
from calendar import monthrange
from datetime import datetime, date

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

# Turso 云数据库凭证 (从环境变量读取，fail-fast)
TURSO_URL = turso_db.TURSO_URL
TURSO_AUTH_TOKEN = turso_db.TURSO_AUTH_TOKEN

from flask_wtf.csrf import CSRFProtect

app = Flask(__name__)
app.secret_key = os.environ.get('FLASK_SECRET_KEY') or os.urandom(24).hex()

# CSRF 保护 (全局启用，自动化 Webhook 使用 @csrf.exempt 排除)
csrf = CSRFProtect(app)

# 安全 Session Cookie 标志 (生产环境/HTTPS 开启 Secure)
is_production = os.environ.get('RENDER') or os.environ.get('FLASK_ENV') == 'production' or os.environ.get('SESSION_COOKIE_SECURE', '0') == '1'
app.config['SESSION_COOKIE_SECURE'] = bool(is_production)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'

# 限制上传文件大小最大 10MB
app.config['MAX_CONTENT_LENGTH'] = 10 * 1024 * 1024

# 自动记账 API 鉴权密钥 (支持用户指定 key、环境变量及数据库配置)
DEFAULT_AUTO_TRACK_KEY = 'zo}SxK_}_%0LO8w;'
AUTO_TRACK_KEY = os.environ.get('AUTO_TRACK_KEY')
AUTO_TRACK_DEBUG_LOG = os.environ.get('AUTO_TRACK_DEBUG_LOG', '0') == '1'

# 获取有效的 AUTO_TRACK_KEY（优先环境变量，次选数据库 system_settings，保底指定默认 key）
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
    return DEFAULT_AUTO_TRACK_KEY


def is_valid_api_key(req_key):
    """检验 API Key 是否合法（支持去除首尾空格、兼容默认与环境变量 key）"""
    if not req_key:
        return False
    k = str(req_key).strip()
    effective = (get_auto_track_key() or '').strip()
    valid_set = {
        effective,
        DEFAULT_AUTO_TRACK_KEY,
        'my-secret-ledger-key',
        'ledger-auto-track-default-key'
    }
    if AUTO_TRACK_KEY and AUTO_TRACK_KEY.strip():
        valid_set.add(AUTO_TRACK_KEY.strip())
    return k in valid_set

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

# 单用户访问密码 (优先环境变量，次选数据库 system_settings，保底 admin123)
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
    return 'admin123'

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
    if request.is_json or request.path.startswith('/split-bill/ocr-upload'):
        return jsonify({'ok': False, 'message': '上传文件大小超出限制（最大允许 10MB）'}), 413
    flash('上传文件大小超出限制（最大允许 10MB）', 'error')
    return redirect(request.referrer or url_for('index'))


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
        request.endpoint in ('login', 'register', 'logout', 'static', 'health', 'api_realtime_check', 'manifest', 'service_worker', 'offline_page', 'api_check_username')
        or request.path in ('/login', '/register', '/logout', '/health', '/api/realtime/check', '/manifest.json', '/sw.js', '/offline.html', '/api/check-username')
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
        admin_pw = get_app_password() or 'admin123'
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
        db.execute("INSERT OR IGNORE INTO system_settings (key, value) VALUES ('auto_track_key', ?)", (DEFAULT_AUTO_TRACK_KEY,))
        db.execute("UPDATE system_settings SET value = ? WHERE key = 'auto_track_key' AND (value IS NULL OR value = '' OR value = 'ledger-auto-track-default-key')", (DEFAULT_AUTO_TRACK_KEY,))
        db.commit()
    except Exception:
        pass

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

    return render_template(
        'index.html',
        month=month,
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
            }
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

        if is_ajax_request():
            return jsonify({'ok': True, 'message': '记录已更新'})

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

    if is_ajax_request():
        return jsonify({'ok': True, 'message': f'成功批量修改 {len(valid_ids)} 条记录', 'edited_ids': valid_ids})

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
        "  \"reason\": \"short reason\",\n"
        "  \"amount\": float or null,\n"
        "  \"type\": \"expense\"|\"income\"|null,\n"
        "  \"merchant\": \"clean merchant or recipient/sender name\" or null,\n"
        "  \"category\": \"standard category name\" or null\n"
        "}"
    )
    prompt = f"Notification text to evaluate:\n\"\"\"{text}\"\"\""
    res = call_llm_json(prompt, system_instruction=system_instruction, timeout=3.5)
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
    # 鉴权检查：优先 Header X-API-KEY，其次 JSON/Form key，最后回退 URL 参数 ?key=xxx 保持向后兼容性
    req_key = request.headers.get('X-API-KEY')
    data = {}
    if request.is_json:
        data = request.get_json(silent=True) or {}
        if not req_key:
            req_key = data.get('key')
    else:
        req_key = req_key or request.form.get('key')

    if not req_key:
        req_key = request.args.get('key')

    if not is_valid_api_key(req_key):
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
        text = raw_payload

    text = (text or "").strip()
    # 如果 payload 是类似 text=... 的 urlencoded 形式，自动解出
    if text.startswith('text='):
        from urllib.parse import unquote
        text = unquote(text[5:]).strip()

    if AUTO_TRACK_DEBUG_LOG:
        print(f"[AUTO_TRACK DEBUG] Final Extracted text: {repr(text)}")

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

    # 智能增强：如果 LLM 提取到了更精准的分类或商户名称，且本地解析为缺省值，进行补充
    if llm_data and isinstance(llm_data, dict):
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

    return jsonify({
        'ok': True,
        'verdict': 'accepted',
        'message': f"成功自动记账：{parsed['note']} {money_filter(parsed['amount'])} ({parsed['category']})",
        'transaction_id': cur.lastrowid,
        'parsed': parsed,
        'notification_title': notification_title,
        'notification_body': notification_body
    }), 201


@app.route('/api/categories', methods=['GET'])
@csrf.exempt
def api_get_categories():
    """获取所有可用分类列表（支持 Android 端离线缓存与下拉选择）"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        if not session.get('logged_in'):
            return jsonify({'ok': False, 'message': 'API Key 无效或未登录'}), 401

    user_id = get_current_user_id()
    db = get_db()
    rows = db.execute('SELECT id, name, type, group_name FROM categories WHERE user_id = ? ORDER BY type, id', (user_id,)).fetchall()
    categories = [{'id': r['id'], 'name': r['name'], 'type': r['type'], 'group_name': r['group_name']} for r in rows]
    return jsonify({'ok': True, 'categories': categories})


@app.route('/api/transactions/sync', methods=['POST'])
@csrf.exempt
def api_sync_transactions():
    """批量同步移动端离线记账数据"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        if not session.get('logged_in'):
            return jsonify({'ok': False, 'message': 'API Key 无效或未登录'}), 401

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


@app.route('/auto-track')
def auto_track_page():
    """Auto Track 配置与测试页面"""
    base_url = request.host_url.rstrip('/')
    webhook_url = f"{base_url}/api/auto-track"
    return render_template(
        'auto_track.html',
        api_key=get_auto_track_key(),
        webhook_url=webhook_url,
        llm_info=get_active_llm_provider()
    )


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
    return render_template('categories.html', income_main=income_main, income_side=income_side, expense=expense, savings=savings)


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
    db.execute('DELETE FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id))
    db.commit()
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '分类已删除（历史记录中的旧数据不受影响）', 'id': cat_id})
    flash('分类已删除（历史记录中的旧数据不受影响）', 'success')
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
        except Exception:
            skipped += 1
            continue

    db.commit()
    os.remove(saved_path)
    flash(f'导入完成：成功 {inserted} 条，跳过 {skipped} 条', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# 小票识别与智能 AA 分账 (Split Bill)
# ---------------------------------------------------------------------------

def parse_receipt_text_to_items(raw_text):
    """
    从小票原始文本（或 OCR 识别出的行）中提取菜品项、服务费、税率与总金额。
    """
    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
    items = []
    subtotal = 0.0
    service_charge = 0.0
    service_rate = 0.0
    tax = 0.0
    tax_rate = 0.0
    total = 0.0

    # 常见行匹配：比如 "1 Chicken Rice 12.50" 或 "Latte  RM 14.00"
    for line in lines:
        lower = line.lower()

        # 匹配服务费 Service Charge / SVC
        if any(k in lower for k in ['service charge', 'svc charge', 'svc chg', 'service fee']):
            m = re.search(r'(?:RM|MYR)?\s*([0-9]+\.[0-9]{2})', line, re.IGNORECASE)
            if m:
                service_charge = float(m.group(1))
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            continue

        # 匹配政府税 / SST / GST / TAX
        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'tax']):
            # 排除非税总行
            if 'total' not in lower and 'subtotal' not in lower:
                m = re.search(r'(?:RM|MYR)?\s*([0-9]+\.[0-9]{2})', line, re.IGNORECASE)
                if m:
                    tax = float(m.group(1))
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                continue

        # 匹配小计 Subtotal
        if 'subtotal' in lower or 'sub-total' in lower:
            m = re.search(r'(?:RM|MYR)?\s*([0-9]+\.[0-9]{2})', line, re.IGNORECASE)
            if m:
                subtotal = float(m.group(1))
            continue

        # 匹配总计 Total / Grand Total / Net Total / Amount Due
        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'total', 'amount due']):
            m = re.search(r'(?:RM|MYR)?\s*([0-9]+\.[0-9]{2})', line, re.IGNORECASE)
            if m:
                total = float(m.group(1))
            continue

        # 排除其他干扰行（如日期、电话、找零、银行卡号等）
        if any(k in lower for k in ['cash', 'change', 'visa', 'mastercard', 'mydebit', 'table', 'date', 'tel', 'invoice', 'receipt', 'bill no']):
            continue

        # 提取常规菜品/消费条目：要求末尾有金额
        m_item = re.search(r'^(.*?)(?:RM|MYR)?\s*([0-9]+\.[0-9]{2})$', line, re.IGNORECASE)
        if m_item:
            name_raw = m_item.group(1).strip(' -:\t')
            price_val = float(m_item.group(2))
            # 过滤名称过短或纯数字的情况
            if name_raw and len(name_raw) >= 2 and price_val > 0:
                # 检查是否有数量前缀（如 "2x " 或 "1 "）
                qty = 1
                m_qty = re.match(r'^(\d+)\s*[xX*]?\s+(.*)$', name_raw)
                if m_qty:
                    qty = int(m_qty.group(1))
                    name_raw = m_qty.group(2).strip()
                items.append({
                    'name': name_raw,
                    'price': price_val,
                    'quantity': qty
                })

    # 若未找到 subtotal，则从 items 求和
    calc_subtotal = sum(i['price'] for i in items)
    if subtotal == 0:
        subtotal = round(calc_subtotal, 2)

    # 如果有百分比但没明确写金额，自动算出来
    if service_charge == 0 and service_rate > 0 and subtotal > 0:
        service_charge = round(subtotal * (service_rate / 100), 2)
    if tax == 0 and tax_rate > 0 and subtotal > 0:
        tax = round((subtotal + service_charge) * (tax_rate / 100), 2)

    if total == 0:
        total = round(subtotal + service_charge + tax, 2)

    return {
        'items': items,
        'subtotal': subtotal,
        'service_charge': service_charge,
        'tax': tax,
        'total': total
    }


@app.route('/split-bill')
def split_bill_page():
    """小票拍照 AA 分账页面"""
    return render_template('split_bill.html', today=date.today().isoformat())


@app.route('/split-bill/parse-text', methods=['POST'])
def split_bill_parse_text():
    """解析小票文本或粘贴内容"""
    text = request.form.get('text', '').strip()
    if not text:
        return jsonify({'ok': False, 'message': '未提供小票内容'}), 400

    parsed = parse_receipt_text_to_items(text)
    return jsonify({'ok': True, 'data': parsed})


@app.route('/split-bill/ocr-upload', methods=['POST'])
def split_bill_ocr_upload():
    """上传小票图片进行 OCR 提取"""
    file = request.files.get('file')
    if not file or file.filename == '':
        return jsonify({'ok': False, 'message': '请选择小票图片'}), 400

    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in ('.jpg', '.jpeg', '.png', '.webp', '.bmp'):
        return jsonify({'ok': False, 'message': '仅支持常见图片格式 (.jpg, .png, .webp)'}), 400

    token = uuid.uuid4().hex
    img_path = os.path.join(UPLOAD_DIR, token + ext)
    file.save(img_path)

    extracted_text = ""
    # 优先尝试本地 pytesseract 如果系统已安装
    try:
        import pytesseract
        from PIL import Image
        img = Image.open(img_path)
        extracted_text = pytesseract.image_to_string(img)
    except Exception:
        pass

    # 清理图片
    try:
        os.remove(img_path)
    except OSError:
        pass

    if extracted_text and extracted_text.strip():
        parsed = parse_receipt_text_to_items(extracted_text)
        return jsonify({'ok': True, 'raw_text': extracted_text, 'data': parsed})

    # 如果运行环境暂无 OCR 引擎（如未安装 tesseract 可执行文件），给出友好提示并提供内置小票模板样例
    return jsonify({
        'ok': False,
        'ocr_engine_ready': False,
        'message': '当前云端/本地未安装 Tesseract OCR 引擎，已为你开启「小票文本快速粘贴/录入」模式。'
    })


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
    msg = f'已成功记入支出：{note} {money_filter(amount)}'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg})
    flash(msg, 'success')
    return redirect(url_for('records'))


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

