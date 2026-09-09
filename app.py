import os
import re
import uuid
import sqlite3
from calendar import monthrange
from datetime import datetime, date

import pandas as pd
from flask import Flask, g, request, redirect, url_for, render_template, flash, jsonify, session

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

# 自动记账 API 鉴权密钥 (无硬编码默认值)
AUTO_TRACK_KEY = os.environ.get('AUTO_TRACK_KEY')
AUTO_TRACK_DEBUG_LOG = os.environ.get('AUTO_TRACK_DEBUG_LOG', '0') == '1'

# 本地 LLM (Ollama) 配置用于过滤营销推广假通知 (Phase-2)
OLLAMA_URL = os.environ.get('OLLAMA_URL', 'http://localhost:11434')
OLLAMA_MODEL = os.environ.get('OLLAMA_MODEL', 'qwen2.5')

# 单用户访问密码 (无硬编码默认值)
APP_PASSWORD = os.environ.get('APP_PASSWORD')

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
        'is_hx': is_hx
    }


@app.before_request
def require_login():
    # 允许静态资源、登录/登出路由、健康检查以及外部自动记账 Webhook 豁免 Session 检查
    if request.endpoint in ('login', 'logout', 'static', 'health') or request.path == '/health' or (request.path and request.path.startswith('/static/')):
        return
    if request.path.startswith('/api/'):
        req_key = request.headers.get('X-API-KEY')
        if not req_key and request.is_json:
            req_key = (request.get_json(silent=True) or {}).get('key')
        if AUTO_TRACK_KEY and req_key == AUTO_TRACK_KEY:
            return
        if request.path.startswith('/api/auto-track'):
            return

    if not session.get('logged_in'):
        if request.headers.get('X-Requested-With') == 'InstantNav':
            return jsonify({'error': 'unauthorized', 'redirect': url_for('login')}), 401
        target_next = request.full_path if request.full_path and request.full_path != '/?' else '/'
        return redirect(url_for('login', next=target_next))


@app.route('/login', methods=['GET', 'POST'])
def login():
    if session.get('logged_in'):
        return redirect(url_for('index'))

    next_url = request.args.get('next') or request.form.get('next') or url_for('index')
    if not next_url.startswith('/') or next_url.startswith('//'):
        next_url = url_for('index')

    if request.method == 'POST':
        password = request.form.get('password', '')
        if not APP_PASSWORD:
            flash('系统未配置 APP_PASSWORD 环境变量，请在环境或控制台配置。', 'error')
            return render_template('login.html', next=next_url), 500

        if password == APP_PASSWORD:
            session['logged_in'] = True
            flash('登录成功！', 'success')
            return redirect(next_url)
        else:
            flash('访问密码错误，请重试。', 'error')
            return render_template('login.html', next=next_url), 401

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


def init_db():
    if TURSO_URL and TURSO_AUTH_TOKEN:
        db = turso_db.TursoConnection(TURSO_URL, TURSO_AUTH_TOKEN)
    else:
        db = sqlite3.connect(DB_PATH)
    db.executescript('''
    CREATE TABLE IF NOT EXISTS categories (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT NOT NULL,            -- income / expense
        group_name TEXT,               -- main / side，仅 income 使用
        name TEXT NOT NULL,
        UNIQUE(type, group_name, name)
    );

    CREATE TABLE IF NOT EXISTS transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        date TEXT NOT NULL,
        type TEXT NOT NULL,            -- income / expense
        group_name TEXT,               -- main / side，仅 income 使用
        category TEXT,
        amount REAL NOT NULL,
        note TEXT,
        source TEXT DEFAULT 'manual',  -- manual / nlp / import / recurring
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS recurring_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
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

    CREATE TABLE IF NOT EXISTS merchant_category_overrides (
        merchant_note TEXT PRIMARY KEY,
        category TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );
    ''')
    if db.execute('SELECT COUNT(*) FROM categories').fetchone()[0] == 0:
        defaults = [
            ('income', 'main', '工资'),
            ('income', 'main', '奖金'),
            ('income', 'side', '自由职业'),
            ('income', 'side', '兼职'),
            ('income', 'side', '投资'),
            ('expense', None, '餐饮'),
            ('expense', None, '交通'),
            ('expense', None, '房租'),
            ('expense', None, '购物'),
            ('expense', None, '娱乐'),
            ('expense', None, '医疗'),
            ('expense', None, '通讯'),
            ('expense', None, '其他'),
        ]
        db.executemany('INSERT INTO categories (type, group_name, name) VALUES (?,?,?)', defaults)
        db.commit()

    if db.execute("SELECT COUNT(*) FROM categories WHERE type='savings'").fetchone()[0] == 0:
        savings_defaults = [
            ('savings', None, '定期存款'),
            ('savings', None, '应急基金'),
            ('savings', None, '投资理财'),
            ('savings', None, '心愿基金'),
        ]
        db.executemany('INSERT INTO categories (type, group_name, name) VALUES (?,?,?)', savings_defaults)
        db.commit()
    db.close()


def get_categories(db, type_, group_name):
    if type_ in ('expense', 'savings'):
        rows = db.execute('SELECT name FROM categories WHERE type=? ORDER BY id', (type_,)).fetchall()
    else:
        rows = db.execute(
            'SELECT name FROM categories WHERE type=? AND group_name=? ORDER BY id',
            (type_, group_name)
        ).fetchall()
    return [r['name'] for r in rows]


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


def generate_due_recurring():
    """按当前真实月份生成到期的固定收支记录（每个规则每月只生成一次）。"""
    db = get_db()
    current_month = date.today().strftime('%Y-%m')
    year, mon = map(int, current_month.split('-'))
    last_day = monthrange(year, mon)[1]

    rules = db.execute('SELECT * FROM recurring_rules WHERE is_active=1').fetchall()
    count = 0
    for r in rules:
        if r['last_generated_month'] == current_month:
            continue
        day = min(r['day_of_month'], last_day)
        tx_date = f'{current_month}-{day:02d}'
        db.execute(
            'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
            'VALUES (?,?,?,?,?,?,?,?)',
            (tx_date, r['type'], r['group_name'], r['category'], r['amount'], r['note'] or '',
             'recurring', datetime.now().isoformat())
        )
        db.execute('UPDATE recurring_rules SET last_generated_month=? WHERE id=?', (current_month, r['id']))
        count += 1
    db.commit()
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
    解析来自 TnG eWallet / Maybank MAE / 银行短信 / 通知栏的文本。
    提取：金额 (RM)、商户名/接收方、时间、自动匹配分类。
    """
    text = raw_text.strip()
    if not text:
        return None

    # 1. 提取金额：支持 "RM 15.00", "RM15.50", "RM 1,250.00", "MYR 20", "15.00"
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

    # 2. 判断是收入还是支出（默认大多数扣款通知是 expense）
    is_income = False
    lower_text = text.lower()
    if any(k in lower_text for k in ['received', 'credited', 'cashback', 'refund', '转入', '收款', '存入']):
        is_income = True

    tx_type = 'income' if is_income else 'expense'

    # 3. 提取商户 / 交易对手
    # 常见格式模式匹配：
    # - "paid RM 15.00 to FamilyMart"
    # - "spent RM 45.00 at PETRONAS"
    # - "Transfer of RM 20.00 to Ali"
    # - "Payment to Starbucks of RM 12"
    merchant = ''
    m_to = re.search(r'(?:to|at|paid to|transfer to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,35})', text, re.IGNORECASE)
    if m_to:
        m_str = m_to.group(1).strip()
        # 清理后续干扰词如 on, via, using, ref, date
        m_cleaned = re.split(r'\s+(?:on|via|ref|using|with|at|for|date|txid)\b', m_str, flags=re.IGNORECASE)[0]
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
    generated = generate_due_recurring()
    if generated:
        flash(f'已自动生成本月固定收支 {generated} 条', 'success')

    month = request.args.get('month') or date.today().strftime('%Y-%m')
    db = get_db()

    year, mon = map(int, month.split('-'))
    last_day = monthrange(year, mon)[1]
    start = f'{month}-01'
    end = f'{month}-{last_day:02d}'

    rows = db.execute(
        'SELECT type, group_name, category, amount FROM transactions WHERE date BETWEEN ? AND ?',
        (start, end)
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    total_savings = sum(r['amount'] for r in rows if r['type'] == 'savings')
    balance = total_income - total_expense

    income_group = {'main': 0.0, 'side': 0.0}
    expense_by_category = {}
    savings_by_category = {}
    for r in rows:
        if r['type'] == 'income':
            gname = r['group_name'] or 'main'
            income_group[gname] = income_group.get(gname, 0.0) + r['amount']
        elif r['type'] == 'expense':
            c = r['category'] or '其他'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']
        elif r['type'] == 'savings':
            c = r['category'] or '储蓄'
            savings_by_category[c] = savings_by_category.get(c, 0.0) + r['amount']

    income_categories = {
        'main': get_categories(db, 'income', 'main'),
        'side': get_categories(db, 'income', 'side'),
    }
    expense_categories = get_categories(db, 'expense', None)
    savings_categories = get_categories(db, 'savings', None)

    return render_template(
        'index.html',
        month=month,
        prev_month=shift_month(month, -1),
        next_month=shift_month(month, 1),
        total_income=total_income,
        total_expense=total_expense,
        total_savings=total_savings,
        balance=balance,
        income_group=income_group,
        expense_by_category=expense_by_category,
        savings_by_category=savings_by_category,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        today=date.today().isoformat(),
    )


@app.route('/api/overview')
def api_overview():
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

    query = 'SELECT date, type, group_name, category, amount FROM transactions WHERE 1=1'
    params = []
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
    total_savings = 0.0
    monthly_stats = {m: {'income': 0.0, 'expense': 0.0, 'savings': 0.0} for m in month_keys}
    expense_cats = {}
    savings_cats = {}
    income_group = {'main': 0.0, 'side': 0.0}

    for r in rows:
        amt = float(r['amount'] or 0)
        m = r['date'][:7]
        t = r['type']
        if m not in monthly_stats:
            monthly_stats[m] = {'income': 0.0, 'expense': 0.0, 'savings': 0.0}
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
        sav = round(monthly_stats[m].get('savings', 0.0), 2)
        monthly_trend.append({
            'month': m,
            'income': inc,
            'expense': exp,
            'savings': sav,
            'balance': round(inc - exp, 2)
        })

    # 月均计算：有月份跨度按跨度算，否则按实际有记录的月份数，至少为 1
    num_months = max(len(month_keys), 1)
    avg_income = total_income / num_months
    avg_expense = total_expense / num_months
    avg_savings = total_savings / num_months
    net_savings = total_income - total_expense

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

def get_category_insights_data(db, time_range='all', start_date=None, end_date=None):
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

    query = "SELECT date, category, amount FROM transactions WHERE type='expense'"
    params = []
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
        "SELECT category, SUM(amount) as total FROM transactions WHERE type='expense' AND date LIKE ? GROUP BY category",
        (f"{current_month}%",)
    ).fetchall()
    cur_month_map = {r['category'] or '其他': float(r['total'] or 0) for r in cur_month_rows}

    prev_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE type='expense' AND date LIKE ? GROUP BY category",
        (f"{prev_month}%",)
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
    db = get_db()
    time_range = request.args.get('range', 'all')
    start_date = (request.args.get('start') or '').strip() or None
    end_date = (request.args.get('end') or '').strip() or None
    data = get_category_insights_data(db, time_range, start_date, end_date)
    return jsonify(data)


@app.route('/categories/insights')
def category_insights_page():
    db = get_db()
    time_range = request.args.get('range', 'all')
    start_date = (request.args.get('start') or '').strip() or None
    end_date = (request.args.get('end') or '').strip() or None
    insights_data = get_category_insights_data(db, time_range, start_date, end_date)
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

    tx_date = f.get('date') or date.today().isoformat()
    cur = db.execute(
        'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?,?,?,?,?,?,?,?)',
        (tx_date, tx_type, group_name, f.get('category'), amount, f.get('note', ''),
         f.get('source', 'manual'), datetime.now().isoformat())
    )
    db.commit()

    if is_ajax_request():
        return jsonify({
            'ok': True,
            'message': '记录已添加',
            'transaction': {
                'id': cur.lastrowid,
                'date': tx_date,
                'type': tx_type,
                'group_name': group_name,
                'category': f.get('category'),
                'amount': amount,
                'note': f.get('note', '')
            }
        })

    flash('记录已添加', 'success')
    return redirect(url_for('index', month=tx_date[:7]))


@app.route('/records')
def records():
    db = get_db()
    start = request.args.get('start', '')
    end = request.args.get('end', '')
    type_ = request.args.get('type', '')
    category = request.args.get('category', '')

    query = 'SELECT * FROM transactions WHERE 1=1'
    params = []
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
        'SELECT DISTINCT category FROM transactions WHERE category IS NOT NULL ORDER BY category'
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    total_savings = sum(r['amount'] for r in rows if r['type'] == 'savings')

    return render_template(
        'records.html',
        rows=rows, start=start, end=end, type=type_, category=category,
        categories=[c['category'] for c in all_categories],
        total_income=total_income, total_expense=total_expense, total_savings=total_savings,
    )


@app.route('/records/<int:tx_id>/edit', methods=['GET', 'POST'])
def edit_record(tx_id):
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
        current_tx = db.execute('SELECT source, note, category FROM transactions WHERE id=?', (tx_id,)).fetchone()
        if current_tx and current_tx['source'] == 'auto_track' and current_tx['note'] and new_category:
            merchant_note = current_tx['note'].strip()
            if merchant_note:
                now_str = datetime.now().isoformat()
                db.execute(
                    'INSERT OR REPLACE INTO merchant_category_overrides (merchant_note, category, updated_at) VALUES (?, ?, ?)',
                    (merchant_note, new_category, now_str)
                )

        db.execute(
            'UPDATE transactions SET date=?, type=?, group_name=?, category=?, amount=?, note=? WHERE id=?',
            (f.get('date'), tx_type, group_name, new_category, amount, f.get('note', ''), tx_id)
        )
        db.commit()

        if is_ajax_request():
            return jsonify({'ok': True, 'message': '记录已更新'})

        flash('记录已更新', 'success')
        return redirect(url_for('records'))

    row = db.execute('SELECT * FROM transactions WHERE id=?', (tx_id,)).fetchone()
    income_categories = {
        'main': get_categories(db, 'income', 'main'),
        'side': get_categories(db, 'income', 'side'),
    }
    expense_categories = get_categories(db, 'expense', None)
    savings_categories = get_categories(db, 'savings', None)
    return render_template(
        'edit_record.html', row=row,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
    )


@app.route('/records/<int:tx_id>/delete', methods=['POST'])
def delete_record(tx_id):
    db = get_db()
    db.execute('DELETE FROM transactions WHERE id=?', (tx_id,))
    db.commit()

    if is_ajax_request():
        return jsonify({'ok': True, 'message': '记录已删除', 'id': tx_id})

    flash('记录已删除', 'success')
    return redirect(url_for('records'))


@app.route('/records/batch-delete', methods=['POST'])
def batch_delete_records():
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
        db.execute(f'DELETE FROM transactions WHERE id IN ({placeholders})', valid_ids)
        db.commit()

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
    params.extend(valid_ids)

    db.execute(f"UPDATE transactions SET {set_clause} WHERE id IN ({placeholders})", params)
    db.commit()

    if is_ajax_request():
        return jsonify({'ok': True, 'message': f'成功批量修改 {len(valid_ids)} 条记录', 'edited_ids': valid_ids})

    flash(f'成功批量修改 {len(valid_ids)} 条记录', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# 自然语言快速记账
# ---------------------------------------------------------------------------

@app.route('/nlp/parse', methods=['POST'])
def nlp_parse():
    text = request.form.get('text', '').strip()
    if not text:
        return {'ok': False, 'message': '请输入内容后再点智能解析'}

    parsed, warnings = parse_nlp_text(text)
    if parsed is None:
        return {'ok': False, 'message': '解析失败：' + '；'.join(warnings) + '。请改用下方快速录入表单手动填写。'}

    return {'ok': True, 'parsed': parsed, 'warnings': warnings, 'original_text': text}


# ---------------------------------------------------------------------------
# Auto Track 自动记账网关 (接收来自手机通知/Webhook)
# ---------------------------------------------------------------------------

def classify_notification_with_llm(text):
    """
    Phase-2 智能营销/广告过滤：
    调用本地 Ollama LLM 二次校验通知是否为真实完成的扣款/入账交易，还是营销促销广告。
    采用 Fail-open 策略：若 Ollama 无法连接、超时或返回异常，打印警告并放行作为真实交易，
    确保不因本地 LLM 服务不可用而影响正常记账。
    返回: True (真实交易) 或 False (营销广告)
    """
    import json as py_json
    import requests

    endpoint = f"{OLLAMA_URL.rstrip('/')}/api/generate"
    prompt = (
        "You are an expert financial transaction validator. "
        "Analyze the following mobile notification text and decide whether it describes an ACTUAL COMPLETED "
        "financial transaction (payment, transfer, debit, credit), OR if it is a PROMOTIONAL, MARKETING, "
        "VOUCHER, DISCOUNT, REWARD, or TOP-UP INVITATION message.\n\n"
        f"Notification text:\n\"\"\"{text}\"\"\"\n\n"
        "Reply with ONLY a valid JSON object in this exact format: {\"is_real_transaction\": true/false, \"reason\": \"short explanation\"}"
    )

    try:
        resp = requests.post(
            endpoint,
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "stream": False,
                "format": "json"
            },
            timeout=3.5
        )
        if resp.status_code == 200:
            res_data = resp.json()
            raw_response = res_data.get('response', '{}')
            parsed_json = py_json.loads(raw_response)
            is_real = parsed_json.get('is_real_transaction')
            if is_real is not None:
                return bool(is_real)
        else:
            print(f"[AUTO_TRACK WARNING] Ollama LLM returned status {resp.status_code}, failing open")
    except Exception as e:
        print(f"[AUTO_TRACK WARNING] Ollama LLM unreachable or error ({e}), failing open")

    return True


@app.route('/api/auto-track', methods=['POST'])
@csrf.exempt
def api_auto_track():
    # 鉴权检查：仅支持 Header X-API-KEY 或 JSON/Form 中的 key (禁止使用 URL ?key= 参数以避免日志与历史记录泄露)
    req_key = request.headers.get('X-API-KEY')
    data = {}
    if request.is_json:
        data = request.get_json(silent=True) or {}
        if not req_key:
            req_key = data.get('key')
    else:
        req_key = req_key or request.form.get('key')

    if not AUTO_TRACK_KEY or req_key != AUTO_TRACK_KEY:
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

    # Phase-2: 本地 LLM 营销广告二次校验（Fail-open 策略）
    is_real = classify_notification_with_llm(text)
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

    # 入库写入交易记录
    db = get_db()
    now = datetime.now().isoformat()
    cur = db.execute(
        'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        (
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

    return jsonify({
        'ok': True,
        'verdict': 'accepted',
        'message': f"成功自动记账：{parsed['note']} {money_filter(parsed['amount'])} ({parsed['category']})",
        'transaction_id': cur.lastrowid,
        'parsed': parsed
    }), 201


@app.route('/api/categories', methods=['GET'])
@csrf.exempt
def api_get_categories():
    """获取所有可用分类列表（支持 Android 端离线缓存与下拉选择）"""
    req_key = request.headers.get('X-API-KEY')
    if not AUTO_TRACK_KEY or req_key != AUTO_TRACK_KEY:
        if not session.get('logged_in'):
            return jsonify({'ok': False, 'message': 'API Key 无效或未登录'}), 401

    db = get_db()
    rows = db.execute('SELECT id, name, type, group_name FROM categories ORDER BY type, id').fetchall()
    categories = [{'id': r['id'], 'name': r['name'], 'type': r['type'], 'group_name': r['group_name']} for r in rows]
    return jsonify({'ok': True, 'categories': categories})


@app.route('/api/transactions/sync', methods=['POST'])
@csrf.exempt
def api_sync_transactions():
    """批量同步移动端离线记账数据"""
    req_key = request.headers.get('X-API-KEY')
    if not AUTO_TRACK_KEY or req_key != AUTO_TRACK_KEY:
        if not session.get('logged_in'):
            return jsonify({'ok': False, 'message': 'API Key 无效或未登录'}), 401

    payload = request.get_json(silent=True) or {}
    txs = payload.get('transactions', [])
    if not txs:
        return jsonify({'ok': True, 'synced_count': 0, 'synced_ids': []})

    db = get_db()
    now = datetime.now().isoformat()
    synced_ids = []
    for item in txs:
        try:
            cur = db.execute(
                'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                (
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
        api_key=AUTO_TRACK_KEY,
        webhook_url=webhook_url
    )


# ---------------------------------------------------------------------------
# 分类管理
# ---------------------------------------------------------------------------

@app.route('/categories')
def categories_page():
    db = get_db()
    income_main = db.execute("SELECT * FROM categories WHERE type='income' AND group_name='main' ORDER BY id").fetchall()
    income_side = db.execute("SELECT * FROM categories WHERE type='income' AND group_name='side' ORDER BY id").fetchall()
    expense = db.execute("SELECT * FROM categories WHERE type='expense' ORDER BY id").fetchall()
    savings = db.execute("SELECT * FROM categories WHERE type='savings' ORDER BY id").fetchall()
    return render_template('categories.html', income_main=income_main, income_side=income_side, expense=expense, savings=savings)


@app.route('/categories/add', methods=['POST'])
def add_category():
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
        db.execute('INSERT INTO categories (type, group_name, name) VALUES (?,?,?)', (type_, group_name, name))
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
    db = get_db()
    db.execute('DELETE FROM categories WHERE id=?', (cat_id,))
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
    db = get_db()
    rules = db.execute('SELECT * FROM recurring_rules ORDER BY id DESC').fetchall()
    income_categories = {
        'main': get_categories(db, 'income', 'main'),
        'side': get_categories(db, 'income', 'side'),
    }
    expense_categories = get_categories(db, 'expense', None)
    savings_categories = get_categories(db, 'savings', None)
    return render_template(
        'recurring.html', rules=rules,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        current_month=date.today().strftime('%Y-%m'),
    )


@app.route('/recurring/add', methods=['POST'])
def add_recurring():
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
        '(type, group_name, category, amount, note, day_of_month, is_active, last_generated_month, created_at) '
        'VALUES (?,?,?,?,?,?,1,NULL,?)',
        (tx_type, group_name, f.get('category'), amount, f.get('note', ''), day, datetime.now().isoformat())
    )
    db.commit()
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '固定收支规则已添加'})
    flash('固定收支规则已添加', 'success')
    return redirect(url_for('recurring_page'))


@app.route('/recurring/<int:rule_id>/delete', methods=['POST'])
def delete_recurring(rule_id):
    db = get_db()
    db.execute('DELETE FROM recurring_rules WHERE id=?', (rule_id,))
    db.commit()
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '规则已删除', 'id': rule_id})
    flash('规则已删除', 'success')
    return redirect(url_for('recurring_page'))


@app.route('/recurring/<int:rule_id>/toggle', methods=['POST'])
def toggle_recurring(rule_id):
    db = get_db()
    row = db.execute('SELECT is_active FROM recurring_rules WHERE id=?', (rule_id,)).fetchone()
    if row:
        new_active = 0 if row['is_active'] else 1
        db.execute('UPDATE recurring_rules SET is_active=? WHERE id=?', (new_active, rule_id))
        db.commit()
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
    count = generate_due_recurring()
    if count:
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
    expense_categories = get_categories(db, 'expense', None)
    income_categories_flat = get_categories(db, 'income', 'main') + get_categories(db, 'income', 'side')

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
                'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?,?,?,?,?,?,?,?)',
                (tx_date, tx_type, group_name, category, amount, note, 'import', now)
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

    db = get_db()
    now = datetime.now().isoformat()
    note = f.get('note', '').strip() or '聚餐 AA 分摊消费'
    tx_date = f.get('date') or date.today().isoformat()
    category = f.get('category') or '餐饮'

    db.execute(
        'INSERT INTO transactions (date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?,?,?,?,?,?,?,?)',
        (tx_date, 'expense', None, category, amount, note, 'split_bill', now)
    )
    db.commit()
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

