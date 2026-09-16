import time
import uuid
import secrets
import sqlite3
import json
import logging
from datetime import datetime
from flask import g, has_request_context, session
from werkzeug.security import generate_password_hash

import turso_db
from core.config import (
    DB_PATH,
    TURSO_URL,
    TURSO_AUTH_TOKEN,
    DEFAULT_AUTO_TRACK_KEY,
    AUTO_TRACK_DEBUG_LOG,
    get_app_password
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 实时同步与局部更新状态版本控制（支持多 Worker DB 持久化与内存热读缓存）
# ---------------------------------------------------------------------------
DATA_VERSION = int(time.time() * 1000)
LATEST_EVENT = None
USER_DATA_VERSIONS = {}
USER_LATEST_EVENTS = {}


def bump_data_version(event_type='update', data=None, user_id=None, db=None):
    global DATA_VERSION, LATEST_EVENT
    DATA_VERSION = int(time.time() * 1000)
    now_iso = datetime.now().isoformat()
    LATEST_EVENT = {
        'version': DATA_VERSION,
        'type': event_type,
        'timestamp': now_iso,
        'data': data or {}
    }
    if not user_id and data and isinstance(data, dict):
        user_id = data.get('user_id')
    if not user_id and has_request_context():
        user_id = session.get('user_id')
    if user_id:
        USER_DATA_VERSIONS[user_id] = DATA_VERSION
        USER_LATEST_EVENTS[user_id] = LATEST_EVENT

    # 持久化到 system_metadata 表，确保多 Worker / 跨重启状态强一致
    try:
        conn = db or (get_db() if has_request_context() else None)
        if conn:
            conn.execute(
                "INSERT OR REPLACE INTO system_metadata (key, val, updated_at) VALUES ('global_data_version', ?, ?)",
                (str(DATA_VERSION), now_iso)
            )
            if user_id:
                conn.execute(
                    "INSERT OR REPLACE INTO system_metadata (key, val, updated_at) VALUES (?, ?, ?)",
                    (f'user_data_version:{user_id}', str(DATA_VERSION), now_iso)
                )
                conn.execute(
                    "INSERT OR REPLACE INTO system_metadata (key, val, updated_at) VALUES (?, ?, ?)",
                    (f'user_latest_event:{user_id}', json.dumps(LATEST_EVENT), now_iso)
                )
            conn.commit()
    except Exception as e:
        logger.warning("Failed to persist data version to system_metadata: %s", e, exc_info=True)


def get_data_version(user_id=None, db=None):
    """获取最新数据版本号（优先从 system_metadata 读取以消除多 Worker 漂移，回退内存缓存）"""
    global DATA_VERSION, USER_DATA_VERSIONS
    try:
        conn = db or (get_db() if has_request_context() else None)
        if conn:
            target_key = f'user_data_version:{user_id}' if user_id else 'global_data_version'
            row = conn.execute("SELECT val FROM system_metadata WHERE key = ?", (target_key,)).fetchone()
            if row and row['val']:
                v = int(row['val'])
                if user_id:
                    USER_DATA_VERSIONS[user_id] = v
                else:
                    DATA_VERSION = v
                return v
            elif user_id:
                row_g = conn.execute("SELECT val FROM system_metadata WHERE key = 'global_data_version'").fetchone()
                if row_g and row_g['val']:
                    return int(row_g['val'])
    except Exception as e:
        logger.debug("Failed to read system_metadata version: %s", e)

    if user_id and user_id in USER_DATA_VERSIONS:
        return USER_DATA_VERSIONS[user_id]
    return DATA_VERSION


def get_latest_event(user_id=None, db=None):
    """获取最新事件 payload（优先从 system_metadata 保证多进程一致性）"""
    global LATEST_EVENT, USER_LATEST_EVENTS
    try:
        conn = db or (get_db() if has_request_context() else None)
        if conn and user_id:
            row = conn.execute("SELECT val FROM system_metadata WHERE key = ?", (f'user_latest_event:{user_id}',)).fetchone()
            if row and row['val']:
                evt = json.loads(row['val'])
                USER_LATEST_EVENTS[user_id] = evt
                return evt
    except Exception as e:
        logger.debug("Failed to read system_metadata event: %s", e)

    if user_id and user_id in USER_LATEST_EVENTS:
        return USER_LATEST_EVENTS[user_id]
    return LATEST_EVENT


def get_db():
    if 'db' not in g:
        if TURSO_URL and TURSO_AUTH_TOKEN:
            g.db = turso_db.TursoConnection(TURSO_URL, TURSO_AUTH_TOKEN)
        else:
            g.db = sqlite3.connect(DB_PATH)
            g.db.row_factory = sqlite3.Row
    return g.db


def close_db(exception=None):
    db = g.pop('db', None)
    if db is not None:
        db.close()


def get_current_user_id():
    if has_request_context():
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


def init_db(app_logger=None):
    if TURSO_URL and TURSO_AUTH_TOKEN:
        db = turso_db.TursoConnection(TURSO_URL, TURSO_AUTH_TOKEN)
    else:
        db = sqlite3.connect(DB_PATH)
        db.row_factory = sqlite3.Row

    # 1. 用户表与系统元数据表
    db.execute('''
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
    );
    ''')
    db.execute('''
    CREATE TABLE IF NOT EXISTS system_metadata (
        key TEXT PRIMARY KEY,
        val TEXT,
        updated_at TEXT NOT NULL
    );
    ''')
    db.commit()

    # 确保默认 admin 用户存在
    admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
    if not admin_row:
        admin_id = str(uuid.uuid4())
        admin_pw = get_app_password(db)
        if not admin_pw:
            admin_pw = secrets.token_urlsafe(16)
            if app_logger:
                app_logger.warning(
                    "未设置 APP_PASSWORD，已为默认 admin 账号生成一次性随机密码（仅显示这一次）：%s",
                    admin_pw
                )
            else:
                logger.warning("[INIT_DB] Generated one-time password for admin: %s", admin_pw)
        db.execute(
            "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (admin_id, 'admin', generate_password_hash(admin_pw), datetime.now().isoformat())
        )
        db.commit()
    else:
        admin_id = admin_row['id']

    # 2. 分类表与多用户迁移
    try:
        col_names = [r[1] for r in db.execute("PRAGMA table_info(categories)").fetchall()]
    except Exception as e:
        logger.debug("PRAGMA table_info(categories) skipped: %s", e)
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
        init_user_default_categories(db, admin_id)
        db.commit()
    elif 'user_id' not in col_names:
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
    except Exception as e:
        logger.debug("PRAGMA table_info(transactions) error: %s", e)
        tx_cols = []
    if 'user_id' not in tx_cols:
        try:
            db.execute("ALTER TABLE transactions ADD COLUMN user_id TEXT")
            db.commit()
        except Exception as e:
            logger.debug("ALTER transactions ADD user_id skipped: %s", e)
    db.execute("UPDATE transactions SET user_id = ? WHERE user_id IS NULL OR user_id = ''", (admin_id,))
    db.commit()

    # 4. 固定收支表
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
    except Exception as e:
        logger.debug("PRAGMA table_info(recurring_rules) error: %s", e)
        rec_cols = []
    if 'user_id' not in rec_cols:
        try:
            db.execute("ALTER TABLE recurring_rules ADD COLUMN user_id TEXT")
            db.commit()
        except Exception as e:
            logger.debug("ALTER recurring_rules ADD user_id skipped: %s", e)
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
        db.commit()
    except Exception as e:
        logger.debug("Init auto_track_key skipped: %s", e)

    try:
        db.execute("ALTER TABLE transactions ADD COLUMN from_savings INTEGER DEFAULT 0")
        db.commit()
    except Exception as e:
        logger.debug("ALTER transactions ADD from_savings skipped: %s", e)

    try:
        db.execute("ALTER TABLE transactions ADD COLUMN from_savings_category TEXT")
        db.commit()
    except Exception as e:
        logger.debug("ALTER transactions ADD from_savings_category skipped: %s", e)

    # 索引优化
    try:
        db.execute("CREATE INDEX IF NOT EXISTS idx_transactions_user ON transactions(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_recurring_user ON recurring_rules(user_id)")
        db.commit()
    except Exception as e:
        logger.debug("Index creation skipped: %s", e)

    # 6. LLM 学习样本表
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

    -- 10. 自动记账防重与幂等记录表 (防止离线排队与网络重试造成重复记账)
    CREATE TABLE IF NOT EXISTS processed_notifications (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT,
        content_hash TEXT NOT NULL,
        raw_text TEXT,
        amount REAL,
        transaction_id INTEGER,
        created_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_processed_notif_hash ON processed_notifications(user_id, content_hash);

    -- 11. 用户个性化偏好与系统设置表
    CREATE TABLE IF NOT EXISTS user_settings (
        user_id TEXT PRIMARY KEY,
        theme_mode TEXT DEFAULT 'system',
        currency_symbol TEXT DEFAULT 'RM',
        default_account_id INTEGER,
        default_group TEXT DEFAULT 'main',
        budget_start_day INTEGER DEFAULT 1,
        default_dashboard_view TEXT DEFAULT 'monthly',
        dedup_window_minutes INTEGER DEFAULT 120,
        table_density TEXT DEFAULT 'comfortable',
        haptic_feedback INTEGER DEFAULT 1,
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
    ''')
    db.commit()

    # 确保 admin 用户具备默认分类
    init_user_default_categories(db, admin_id)
    db.close()


DEFAULT_USER_SETTINGS = {
    'theme_mode': 'system',
    'currency_symbol': 'RM',
    'default_account_id': None,
    'default_group': 'main',
    'budget_start_day': 1,
    'default_dashboard_view': 'monthly',
    'dedup_window_minutes': 120,
    'table_density': 'comfortable',
    'haptic_feedback': 1,
}


def get_user_settings(user_id, db=None):
    """获取指定用户的偏好配置，自动合并默认值"""
    if not user_id:
        return dict(DEFAULT_USER_SETTINGS)
    if db is None:
        db = get_db()
    try:
        row = db.execute(
            "SELECT * FROM user_settings WHERE user_id = ?",
            (str(user_id),)
        ).fetchone()
        res = dict(DEFAULT_USER_SETTINGS)
        if row:
            if hasattr(row, 'keys'):
                for k in row.keys():
                    if k in res and row[k] is not None:
                        res[k] = row[k]
            else:
                for k in res:
                    try:
                        val = row[k]
                        if val is not None:
                            res[k] = val
                    except Exception:
                        pass
        return res
    except Exception as e:
        logger.debug("Failed to read user_settings for %s: %s", user_id, e)
        return dict(DEFAULT_USER_SETTINGS)


def update_user_settings(user_id, new_settings, db=None):
    """更新指定用户的偏好配置"""
    if not user_id:
        return False
    if db is None:
        db = get_db()
    current = get_user_settings(user_id, db=db)
    current.update({k: v for k, v in new_settings.items() if k in DEFAULT_USER_SETTINGS})

    now_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    try:
        db.execute('''
            INSERT INTO user_settings (
                user_id, theme_mode, currency_symbol, default_account_id,
                default_group, budget_start_day, default_dashboard_view,
                dedup_window_minutes, table_density, haptic_feedback, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                theme_mode=excluded.theme_mode,
                currency_symbol=excluded.currency_symbol,
                default_account_id=excluded.default_account_id,
                default_group=excluded.default_group,
                budget_start_day=excluded.budget_start_day,
                default_dashboard_view=excluded.default_dashboard_view,
                dedup_window_minutes=excluded.dedup_window_minutes,
                table_density=excluded.table_density,
                haptic_feedback=excluded.haptic_feedback,
                updated_at=excluded.updated_at
        ''', (
            str(user_id),
            current['theme_mode'],
            current['currency_symbol'],
            current['default_account_id'],
            current['default_group'],
            int(current['budget_start_day']),
            current['default_dashboard_view'],
            int(current['dedup_window_minutes']),
            current['table_density'],
            int(current['haptic_feedback']),
            now_str
        ))
        db.commit()
        bump_data_version("settings_update", user_id=user_id, db=db)
        return True
    except Exception as e:
        logger.error("Failed to update user_settings for %s: %s", user_id, e, exc_info=True)
        return False

