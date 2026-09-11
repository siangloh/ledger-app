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

# ç¡®ä¿åœ¨ Windows æŽ§åˆ¶å°çŽ¯å¢ƒä¸‹è¾“å‡ºä¸­æ–‡ä¸å‘ç”Ÿ charmap ç¼–ç å´©æºƒ
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
# æ•°æ®å­˜å‚¨ç›®å½•
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

# Turso äº‘æ•°æ®åº“å‡­è¯ (ä»ŽçŽ¯å¢ƒå˜é‡è¯»å–ï¼Œfail-fast)
TURSO_URL = turso_db.TURSO_URL
TURSO_AUTH_TOKEN = turso_db.TURSO_AUTH_TOKEN

from flask_wtf.csrf import CSRFProtect, CSRFError
from datetime import timedelta

app = Flask(__name__)

# ç¨³å®š Session å¯†é’¥æœºåˆ¶ï¼ˆä¿è¯è·¨ Gunicorn Workerã€è·¨é‡å¯ã€è·¨å”¤é†’å¯†é’¥ 100% æ’å®šä¸€è‡´ï¼Œæœç»ä¼šè¯æ¼‚ç§»ï¼‰
app.secret_key = (
    os.environ.get('FLASK_SECRET_KEY')
    or os.environ.get('SECRET_KEY')
    or 'ledger-app-prod-secret-stable-key-8f4b2c1e9a7d-stable-2026'
)
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)
app.config['TEMPLATES_AUTO_RELOAD'] = True

# æ”¯æŒ Render ç­‰åå‘ä»£ç†æ­£ç¡®è¯†åˆ« https åè®®ä¸Žå®¢æˆ·ç«¯ IP
from werkzeug.middleware.proxy_fix import ProxyFix
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1, x_prefix=1)

# CSRF ä¿æŠ¤ (å…¨å±€å¯ç”¨ï¼Œè‡ªåŠ¨åŒ– Webhook ä½¿ç”¨ @csrf.exempt æŽ’é™¤)
csrf = CSRFProtect(app)

# å®‰å…¨ Session Cookie æ ‡å¿— (ç”Ÿäº§çŽ¯å¢ƒ/HTTPS å¼€å¯ Secure)
is_production = os.environ.get('RENDER') or os.environ.get('FLASK_ENV') == 'production' or os.environ.get('SESSION_COOKIE_SECURE', '0') == '1'
app.config['SESSION_COOKIE_SECURE'] = bool(is_production)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'

# é™åˆ¶ä¸Šä¼ æ–‡ä»¶å¤§å°æœ€å¤§ 20MB (é¿å…é«˜åƒç´ æ‰‹æœºç…§ç‰‡è¶…å‡ºé™åˆ¶)
app.config['MAX_CONTENT_LENGTH'] = 20 * 1024 * 1024

# è‡ªåŠ¨è®°è´¦ API é‰´æƒå¯†é’¥ (æ”¯æŒç”¨æˆ·æŒ‡å®š keyã€çŽ¯å¢ƒå˜é‡åŠæ•°æ®åº“é…ç½®ï¼›ä¸å†æœ‰ä»»ä½•ç¡¬ç¼–ç ä¿åº•å€¼)
AUTO_TRACK_KEY = os.environ.get('AUTO_TRACK_KEY')
AUTO_TRACK_DEBUG_LOG = os.environ.get('AUTO_TRACK_DEBUG_LOG', '0') == '1'

# èŽ·å–æœ‰æ•ˆçš„ AUTO_TRACK_KEYï¼ˆä¼˜å…ˆçŽ¯å¢ƒå˜é‡ï¼Œæ¬¡é€‰æ•°æ®åº“ system_settingsï¼›ä¸¤è€…éƒ½æ²¡è®¾ç½®å°±å›žä¼  Noneï¼Œ
# ä»£è¡¨ç›®å‰æ²¡æœ‰é…ç½®ä»»ä½• key â€”â€” è¿™ç§æƒ…å†µä¸‹ is_valid_api_key() ä¸€å¾‹æ‹’ç»ï¼Œä¸ä¼šæœ‰ä»»ä½•åŽå¤‡å€¼å¯ç”¨ï¼‰
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
    """æ£€éªŒ API Key æ˜¯å¦åˆæ³•ã€‚åªè®¤ç›®å‰å®žé™…é…ç½®çš„é‚£ä¸€æŠŠ keyï¼Œ
    ä¸æŽ¥å—ä»»ä½•å†™æ­»åœ¨ä»£ç é‡Œçš„é»˜è®¤å€¼æˆ–æ—§ç‰ˆæ›¾ç»æ³„æ¼è¿‡çš„ keyï¼ˆé‚£äº›å·²ç»è¢«è§†ä¸ºæ°¸ä¹…ä½œåºŸï¼‰ã€‚"""
    if not req_key:
        return False
    effective = get_auto_track_key()
    if not effective:
        # å®Œå…¨æ²¡æœ‰é…ç½®ä»»ä½• key æ—¶ï¼Œæ‹’ç»æ‰€æœ‰è¯·æ±‚ï¼Œä¸å›žé€€åˆ°ä»»ä½•é»˜è®¤å€¼
        return False
    return str(req_key).strip() == effective

# LLM æ™ºèƒ½æœåŠ¡é…ç½® (ä¼˜å…ˆ Google Geminiï¼Œå…¶æ¬¡ OpenAI/DeepSeekï¼Œå†å›žé€€æœ¬åœ° Ollama ä¸Žå¿«é€Ÿè§„åˆ™å¼•æ“Ž)
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
    """è¿”å›žå½“å‰ä¼˜å…ˆå¯ç”¨çš„ LLM ä¾›åº”å•†åç§°ä¸Žæ¨¡åž‹"""
    if GEMINI_API_KEY:
        return {'provider': 'gemini', 'name': f'Google Gemini ({GEMINI_MODEL})', 'available': True}
    if DEEPSEEK_API_KEY:
        return {'provider': 'deepseek', 'name': 'DeepSeek (deepseek-chat)', 'available': True}
    if OPENAI_API_KEY:
        return {'provider': 'openai', 'name': f'OpenAI ({OPENAI_MODEL})', 'available': True}
    return {'provider': 'ollama', 'name': f'Local Ollama ({OLLAMA_MODEL})', 'available': False}

# å•ç”¨æˆ·è®¿é—®å¯†ç  (ä¼˜å…ˆçŽ¯å¢ƒå˜é‡ï¼Œæ¬¡é€‰æ•°æ®åº“ system_settingsï¼›éƒ½æ²¡è®¾ç½®å°±å›žä¼  Noneï¼Œ
# ä¸å†æœ‰ä»»ä½•å†™æ­»åœ¨ä»£ç é‡Œçš„ä¿åº•å¯†ç )
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
# å®žæ—¶åŒæ­¥ä¸Žå±€éƒ¨æ›´æ–°çŠ¶æ€ç‰ˆæœ¬æŽ§åˆ¶
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

# æœ¬åœ°å•äººä½¿ç”¨çš„å¼€å‘æœåŠ¡å™¨ï¼šå…³é—­é™æ€æ–‡ä»¶ç¼“å­˜ï¼Œé¿å…æµè§ˆå™¨ç¼“å­˜æ—§çš„ CSS/JS å¯¼è‡´æ”¹åŠ¨çœ‹ä¸åˆ°
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0


@app.errorhandler(413)
def request_entity_too_large(error):
    if request.is_json:
        return jsonify({'ok': False, 'message': 'ä¸Šä¼ æ–‡ä»¶å¤§å°è¶…å‡ºé™åˆ¶ï¼ˆæœ€å¤§å…è®¸ 20MBï¼‰'}), 413
    flash('ä¸Šä¼ æ–‡ä»¶å¤§å°è¶…å‡ºé™åˆ¶ï¼ˆæœ€å¤§å…è®¸ 20MBï¼‰', 'error')
    return redirect(request.referrer or url_for('index'))


@app.errorhandler(500)
def internal_server_error(error):
    """ç¡®ä¿ /split-bill/ è·¯ç”±çš„ 500 é”™è¯¯ä»¥ JSON å½¢å¼è¿”å›žï¼Œè€Œä¸æ˜¯ HTML é”™è¯¯é¡µ"""
    import traceback
    traceback.print_exc()
    if request.path.startswith('/split-bill/'):
        return jsonify({'ok': False, 'message': f'æœåŠ¡å™¨å†…éƒ¨é”™è¯¯ï¼Œè¯·ç¨åŽé‡è¯•ã€‚({str(error)})'}), 200
    return error


@app.errorhandler(CSRFError)
def handle_csrf_error(error):
    """æ‹¦æˆª CSRF ä»¤ç‰Œè¿‡æœŸæˆ–ä¸¢å¤±é”™è¯¯ï¼Œä»¥å‹å¥½æ–¹å¼æç¤º/é‡å®šå‘ï¼Œä¸å†å±•ç¤ºåŽŸç”Ÿç”Ÿç¡¬çš„ 400 Bad Request é¡µé¢"""
    if request.is_json or request.headers.get('X-Requested-With') == 'XMLHttpRequest' or request.path.startswith('/api/') or request.path.startswith('/split-bill/'):
        return jsonify({
            'ok': False,
            'message': 'é¡µé¢ä¼šè¯å·²è¶…æ—¶å¤±æ•ˆï¼Œè¯·ä¸‹æ‹‰åˆ·æ–°å½“å‰ç½‘é¡µåŽé‡è¯•ã€‚'
        }), 400
    flash('é¡µé¢åœé¡¿æ—¶é—´è¾ƒé•¿æˆ–æœåŠ¡åˆšæ›´æ–°ï¼Œä¼šè¯å·²è‡ªåŠ¨é‡ç½®ï¼Œè¯·é‡è¯•æäº¤ã€‚', 'warning')
    return redirect(request.referrer or url_for('index'))


@app.after_request
def add_cache_control_headers(response):
    """å¯¹ HTML é¡µé¢ä¸Žæ•æ„Ÿè·¯ç”±å¼ºåˆ¶ä¸ç¼“å­˜ï¼Œç¡®ä¿æ¯æ¬¡åŠ è½½éƒ½èƒ½èŽ·å–æœ€æ–°ä¼šè¯å’Œæœ‰æ•ˆçŠ¶æ€"""
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
    # å…è®¸é™æ€èµ„æºã€ç™»å½•/æ³¨å†Œ/ç™»å‡ºè·¯ç”±ã€å¥åº·æ£€æŸ¥ã€PWA æ ¸å¿ƒèµ„æºä»¥åŠå¤–éƒ¨è‡ªåŠ¨è®°è´¦ Webhook è±å… Session æ£€æŸ¥
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
        return jsonify({'ok': False, 'available': False, 'message': 'è¯·è¾“å…¥ç”¨æˆ·å'})
    if len(username) < 3:
        return jsonify({'ok': False, 'available': False, 'message': f'ç”¨æˆ·åå¤ªçŸ­ï¼Œè‡³å°‘éœ€ 3 ä¸ªå­—ç¬¦ï¼ˆå½“å‰ {len(username)} ä¸ªï¼‰'})
    if len(username) > 30:
        return jsonify({'ok': False, 'available': False, 'message': 'ç”¨æˆ·åä¸èƒ½è¶…è¿‡ 30 ä¸ªå­—ç¬¦'})
    if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
        return jsonify({'ok': False, 'available': False, 'message': 'ä»…æ”¯æŒä¸­æ–‡ã€è‹±æ–‡å­—æ¯ã€æ•°å­—ã€ä¸‹åˆ’çº¿åŠè¿žå­—ç¬¦'})
    db = get_db()
    existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
    if existing:
        return jsonify({'ok': True, 'available': False, 'message': 'è¯¥ç”¨æˆ·åå·²è¢«å ç”¨ï¼Œè¯·ç›´æŽ¥ç™»å½•æˆ–æ›´æ¢'})
    return jsonify({'ok': True, 'available': True, 'message': 'è¯¥ç”¨æˆ·åå¯ç”¨ âœ“'})


@app.route('/register', methods=['GET', 'POST'])
def register():
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    if request.method == 'POST':
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password', '')
        confirm_password = request.form.get('confirm_password', '')

        if not username:
            flash('ç”¨æˆ·åä¸èƒ½ä¸ºç©º', 'error')
            return render_template('register.html')
        if len(username) < 3 or len(username) > 30:
            flash('ç”¨æˆ·åé•¿åº¦éœ€åœ¨ 3 åˆ° 30 ä¸ªå­—ç¬¦ä¹‹é—´', 'error')
            return render_template('register.html')
        if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
            flash('ç”¨æˆ·åä»…æ”¯æŒä¸­æ–‡ã€å­—æ¯ã€æ•°å­—åŠä¸‹åˆ’çº¿', 'error')
            return render_template('register.html')
        if not password or len(password) < 6:
            flash('å¯†ç é•¿åº¦è‡³å°‘éœ€è¦ 6 ä¸ªå­—ç¬¦', 'error')
            return render_template('register.html')
        if not re.search(r'[A-Z]', password):
            flash('å¯†ç éœ€åŒ…å«è‡³å°‘ä¸€ä¸ªå¤§å†™å­—æ¯ (A-Z)', 'error')
            return render_template('register.html')
        if not re.search(r'[a-z]', password):
            flash('å¯†ç éœ€åŒ…å«è‡³å°‘ä¸€ä¸ªå°å†™å­—æ¯ (a-z)', 'error')
            return render_template('register.html')
        if not re.search(r'[0-9]', password):
            flash('å¯†ç éœ€åŒ…å«è‡³å°‘ä¸€ä¸ªæ•°å­— (0-9)', 'error')
            return render_template('register.html')
        if not re.search(r'[^a-zA-Z0-9]', password):
            flash('å¯†ç éœ€åŒ…å«è‡³å°‘ä¸€ä¸ªç‰¹æ®Šç¬¦å·ï¼ˆå¦‚ !@#$%^&* ç­‰ï¼‰', 'error')
            return render_template('register.html')
        if password != confirm_password:
            flash('ä¸¤æ¬¡è¾“å…¥çš„å¯†ç ä¸ä¸€è‡´', 'error')
            return render_template('register.html')

        db = get_db()
        existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
        if existing:
            flash('è¯¥ç”¨æˆ·åå·²è¢«æ³¨å†Œï¼Œè¯·ç›´æŽ¥ç™»å½•æˆ–æ¢ä¸€ä¸ªç”¨æˆ·å', 'error')
            return render_template('register.html')

        user_id = str(uuid.uuid4())
        pw_hash = generate_password_hash(password)
        now_str = datetime.now().isoformat()
        db.execute(
            'INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)',
            (user_id, username, pw_hash, now_str)
        )
        db.commit()

        # ä¸ºæ–°æ³¨å†Œè´¦å·åˆå§‹åŒ–ä¸“å±žç‹¬ç«‹çš„é»˜è®¤åˆ†ç±»é›†
        init_user_default_categories(db, user_id)

        session.permanent = True
        session['logged_in'] = True
        session['user_id'] = user_id
        session['username'] = username
        flash(f'æ³¨å†ŒæˆåŠŸï¼Œæ¬¢è¿Žä½¿ç”¨å¤šè´¦æœ¬ä¸ªäººè´¢åŠ¡ç³»ç»Ÿï¼Œ{username}ï¼', 'success')
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
            flash('è¯·è¾“å…¥ç”¨æˆ·åå’Œå¯†ç ', 'error')
            return render_template('login.html', next=next_url, username=username), 400

        db = get_db()
        user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()

        if user and check_password_hash(user['password_hash'], password):
            session.permanent = True
            session['logged_in'] = True
            session['user_id'] = user['id']
            session['username'] = user['username']
            flash(f'æ¬¢è¿Žå›žæ¥ï¼Œ{user["username"]}ï¼', 'success')
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
            flash('ç™»å½•æˆåŠŸï¼', 'success')
            return redirect(next_url)
        else:
            flash('ç”¨æˆ·åæˆ–å¯†ç é”™è¯¯ï¼Œè¯·é‡è¯•', 'error')
            return render_template('login.html', next=next_url, username=username), 401

    return render_template('login.html', next=next_url)


@app.route('/logout', methods=['GET', 'POST'])
def logout():
    session.clear()
    flash('æ‚¨å·²æˆåŠŸé€€å‡ºç™»å½•ã€‚', 'success')
    return redirect(url_for('login'))



@app.template_filter('money')
def money_filter(value):
    """æ ¼å¼åŒ–ä¸ºæž—å‰ç‰¹é‡‘é¢ï¼Œå¦‚ RM 3,900.00"""
    try:
        value = float(value)
    except (TypeError, ValueError):
        value = 0.0
    return f"RM {value:,.2f}"


# ---------------------------------------------------------------------------
# æ•°æ®åº“
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
            (user_id, 'income', 'main', 'å·¥èµ„'),
            (user_id, 'income', 'main', 'å¥–é‡‘'),
            (user_id, 'income', 'side', 'è‡ªç”±èŒä¸š'),
            (user_id, 'income', 'side', 'å…¼èŒ'),
            (user_id, 'income', 'side', 'æŠ•èµ„'),
            (user_id, 'expense', None, 'é¤é¥®'),
            (user_id, 'expense', None, 'äº¤é€š'),
            (user_id, 'expense', None, 'æˆ¿ç§Ÿ'),
            (user_id, 'expense', None, 'è´­ç‰©'),
            (user_id, 'expense', None, 'å¨±ä¹'),
            (user_id, 'expense', None, 'åŒ»ç–—'),
            (user_id, 'expense', None, 'é€šè®¯'),
            (user_id, 'expense', None, 'å…¶ä»–'),
            (user_id, 'savings', None, 'å®šæœŸå­˜æ¬¾'),
            (user_id, 'savings', None, 'åº”æ€¥åŸºé‡‘'),
            (user_id, 'savings', None, 'æŠ•èµ„ç†è´¢'),
            (user_id, 'savings', None, 'å¿ƒæ„¿åŸºé‡‘'),
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
        'notes': 'é“¶è¡Œä¿¡ç”¨å¡å¼€å¡æ´»åŠ¨è¥é”€å¹¿å‘Šï¼ŒéžåŠ¨è´¦é€šçŸ¥'
    },
    {
        'text': 'Exclusive for you! Need extra cash? Apply for Maybank Personal Loan from 5.88% p.a. and get instant approval today. T&Cs apply.',
        'label_type': 'promo',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': 'Maybank',
        'sample_category': None,
        'notes': 'é“¶è¡Œä¸ªäººè´·æ¬¾æŽ¨é”€å¹¿å‘Š'
    },
    {
        'text': "Touch 'n Go eWallet: Stand a chance to win a Proton eMas 7 and RM50,000 cash prizes! Spend RM10 with DuitNow QR to earn entries. Promo ends 30 Sept.",
        'label_type': 'promo',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': "Touch 'n Go",
        'sample_category': None,
        'notes': 'æŠ½å¥–æ´»åŠ¨ä¸Žæ¶ˆè´¹è¾¾æ ‡ç«žèµ›å®£ä¼ ï¼Œéžå®žé™…æ¶ˆè´¹'
    },
    {
        'text': 'PB Alert: Your OTP is 582910 for First-Time Login. Do not reveal this OTP to anyone, including bank staff.',
        'label_type': 'otp_notice',
        'is_real_transaction': 0,
        'sample_amount': None,
        'sample_merchant': 'Public Bank',
        'sample_category': None,
        'notes': 'ä¸€æ¬¡æ€§ç™»å½•éªŒè¯ç  / å®‰å…¨æé†’'
    },
    {
        'text': "Touch 'n Go eWallet: You have successfully paid RM 15.50 to FamilyMart SS15 on 10/09/2026. Ref: TNG8892182. Claim your cashback voucher now!",
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 15.50,
        'sample_merchant': 'FamilyMart SS15',
        'sample_category': 'é¤é¥®',
        'notes': 'ä¾¿åˆ©åº—æ‰«ç æ¶ˆè´¹ï¼Œæœ«å°¾å¸¦è¥é”€å¡åˆ¸å¥–åŠ±ï¼Œåº”åˆ¤å®šä¸ºçœŸå®žæ¶ˆè´¹'
    },
    {
        'text': 'PB Payment Alert: You have paid RM 45.00 to PETRONAS SOLARIS on 10/09/2026 via debit card. Ref: PB491823.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 45.00,
        'sample_merchant': 'PETRONAS SOLARIS',
        'sample_category': 'äº¤é€š',
        'notes': 'æ²¹ç«™åŠ æ²¹æ¶ˆè´¹æ”¯å‡º'
    },
    {
        'text': 'Payment of RM 28.00 to GrabCar completed via GrabPay on 10 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 28.00,
        'sample_merchant': 'GrabCar',
        'sample_category': 'äº¤é€š',
        'notes': 'ç½‘çº¦è½¦æ‰“è½¦å‡ºè¡Œæ”¯å‡º'
    },
    {
        'text': 'MAE: RM 36.40 debited for payment at 99 SPEEDMART - 1482 on 10 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 36.40,
        'sample_merchant': '99 SPEEDMART',
        'sample_category': 'è´­ç‰©',
        'notes': 'è¿žé”è¶…å¸‚æ—¥å¸¸ç”¨å“æ¶ˆè´¹æ”¯å‡º'
    },
    {
        'text': 'Transfer Successful. RM 120.00 has been successfully transferred to Tan Ah Kow via DuitNow Transfer. Ref: 20260910001.',
        'label_type': 'expense_transfer',
        'is_real_transaction': 1,
        'sample_amount': 120.00,
        'sample_merchant': 'Tan Ah Kow',
        'sample_category': 'å…¶ä»–',
        'notes': 'å‘ä»–äººè½¬è´¦ä»˜æ¬¾ / æ”¯å‡º'
    },
    {
        'text': 'DuitNow Transfer: You have received RM 250.00 from Wong Mei Ling on 10 Sep 2026. Ref: DN982187.',
        'label_type': 'income_transfer',
        'is_real_transaction': 1,
        'sample_amount': 250.00,
        'sample_merchant': 'Wong Mei Ling',
        'sample_category': 'å…¶ä»–',
        'notes': 'æ”¶åˆ°ä»–äºº DuitNow è½¬è´¦è¿›è´¦ï¼Œè®°ä¸ºæ”¶å…¥'
    },
    {
        'text': 'Salary Credit: RM 8,500.00 credited into your account from ABC TECH SDN BHD on 28/08/2026. Salary payment.',
        'label_type': 'income_transfer',
        'is_real_transaction': 1,
        'sample_amount': 8500.00,
        'sample_merchant': 'ABC TECH SDN BHD',
        'sample_category': 'å·¥èµ„',
        'notes': 'å…¬å¸è–ªèµ„ä»£å‘ï¼Œä¸»ä¸šæ”¶å…¥å…¥è´¦'
    },
    {
        'text': 'JomPAY: RM 142.50 paid to Tenaga Nasional Berhad (TNB) via Maybank MAE on 05 Sep 2026.',
        'label_type': 'expense',
        'is_real_transaction': 1,
        'sample_amount': 142.50,
        'sample_merchant': 'Tenaga Nasional Berhad (TNB)',
        'sample_category': 'é€šè®¯',
        'notes': 'æ°´ç”µç¼´è´¹æ”¯å‡º'
    }
]


def seed_learning_samples(db):
    """å°†é»˜è®¤é¢„è®¾è¯­æ–™æ ·æœ¬çŒå…¥æ•°æ®åº“"""
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

    # 1. ç”¨æˆ·è¡¨ï¼ˆUUID ä¸»é”®ï¼Œç¡®ä¿å®‰å…¨æ€§å’Œå”¯ä¸€æ€§ï¼‰
    db.execute('''
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
    );
    ''')
    db.commit()

    # ç¡®ä¿é»˜è®¤ admin ç”¨æˆ·å­˜åœ¨ï¼Œåˆ†é…ç‹¬ç«‹ UUID
    admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
    if not admin_row:
        admin_id = str(uuid.uuid4())
        admin_pw = get_app_password()
        if not admin_pw:
            # æ²¡æœ‰è®¾ç½® APP_PASSWORD/system_settingsï¼Œå°±ç”Ÿæˆä¸€ä¸ªéšæœºçš„ä¸€æ¬¡æ€§å¯†ç ï¼Œ
            # è€Œä¸æ˜¯ç”¨ä»»ä½•å†™æ­»çš„é»˜è®¤å¯†ç  â€”â€” å¯†ç åªä¼šå°ä¸€æ¬¡åœ¨ server log é‡Œï¼Œ
            # ä½ è¦ç”¨è¿™ä¸ªé»˜è®¤ admin è´¦å·ç™»å½•çš„è¯ï¼ŒåŽ» log é‡Œæ‰¾è¿™ä¸€è¡Œå¤åˆ¶å¯†ç ï¼Œ
            # ç™»å½•åŽå»ºè®®å°½å¿«æ”¹æŽ‰æˆ–æ”¹ç”¨ /register å»ºä¸€ä¸ªè‡ªå·±çš„è´¦å·ã€‚
            admin_pw = secrets.token_urlsafe(16)
            app.logger.warning(
                "æœªè®¾ç½® APP_PASSWORDï¼Œå·²ä¸ºé»˜è®¤ admin è´¦å·ç”Ÿæˆä¸€æ¬¡æ€§éšæœºå¯†ç ï¼ˆä»…æ˜¾ç¤ºè¿™ä¸€æ¬¡ï¼‰ï¼š%s",
                admin_pw
            )
        db.execute(
            "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (admin_id, 'admin', generate_password_hash(admin_pw), datetime.now().isoformat())
        )
        db.commit()
    else:
        admin_id = admin_row['id'] if (isinstance(admin_row, sqlite3.Row) or isinstance(admin_row, dict)) else admin_row[0]

    # 2. æ£€æŸ¥ categories è¡¨æ˜¯å¦å·²æœ‰ user_id å­—æ®µåŠç‹¬ç«‹å¤åˆå”¯ä¸€çº¦æŸ UNIQUE(user_id, type, group_name, name)
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
        # è¿›è¡Œå®‰å…¨è¿ç§»ï¼Œé‡æž„ä¸ºæ”¯æŒå¤šç”¨æˆ·ç‹¬ç«‹åˆ†ç±»ä¸”ä¿ç•™åŽ†å²æ•°æ®
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

    # 3. äº¤æ˜“è¡¨ä¸Žå¤šç”¨æˆ·æ”¯æŒ
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

    # 4. å›ºå®šæ”¶æ”¯è¡¨ä¸Žå¤šç”¨æˆ·æ”¯æŒ
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

    # 5. å•†æˆ·-åˆ†ç±»è®°å¿†è¡¨ä¸Žç³»ç»Ÿé…ç½®è¡¨
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

    # ç´¢å¼•ä¼˜åŒ–
    try:
        db.execute("CREATE INDEX IF NOT EXISTS idx_transactions_user ON transactions(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_recurring_user ON recurring_rules(user_id)")
        db.commit()
    except Exception:
        pass

    # 6. LLM å­¦ä¹ æ ·æœ¬è¡¨ (Few-Shot Datasheet)
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

    # 7. åˆ†ç±»é¢„ç®—ä¸Šé™ä¸Žè¶…æ”¯æé†’åŽ»é‡è¡¨
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

    -- 8. è´Ÿå€ºã€ä¿¡ç”¨å¡ä¸Žåˆ†æœŸä»˜æ¬¾è¿½è¸ªè¡¨
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

    -- 9. è®¢é˜…æœåŠ¡ä¸Žç»­è´¹æé†’è¡¨
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

    # ç¡®ä¿ admin ç”¨æˆ·å…·å¤‡é»˜è®¤åˆ†ç±»
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
    è®¡ç®—å½“å‰ç”¨æˆ·æ‰€æœ‰åŽ†å²å‚¨è“„åˆ†ç±»çš„ç´¯è®¡ç»“ä½™ï¼ˆå‚¨è“„èµ„é‡‘æ± ï¼‰ï¼š
    å„åˆ†ç±»ç´¯è®¡å­˜å…¥ - ä»Žè¯¥åˆ†ç±»æ‰£é™¤çš„åŽ†å²æ”¯å‡º
    è¿”å›ž: (savings_pool_by_category: dict, total_savings_pool: float)
    """
    if not user_id:
        user_id = get_current_user_id()
    in_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id=? AND type='savings' GROUP BY category",
        (user_id,)
    ).fetchall()
    savings_in = {}
    for r in in_rows:
        cat = r['category'] or 'å‚¨è“„'
        savings_in[cat] = savings_in.get(cat, 0.0) + float(r['total'] or 0.0)

    out_rows = db.execute(
        "SELECT COALESCE(NULLIF(from_savings_category, ''), category, 'å…¶ä»–') as scat, SUM(amount) as total "
        "FROM transactions WHERE user_id=? AND type='expense' AND COALESCE(from_savings, 0)=1 GROUP BY scat",
        (user_id,)
    ).fetchall()
    savings_out = {}
    for r in out_rows:
        scat = r['scat'] or 'å…¶ä»–'
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
    è¿”å›žå½“å‰ç”¨æˆ·æ¯ä¸ªå·²è®¾ç½®æœˆåº¦é¢„ç®—çš„æ”¯å‡ºåˆ†ç±»çš„èŠ±è´¹è¿›åº¦ï¼š
    [{category, limit, spent, remaining, pct, level}], æŒ‰ pct ä»Žé«˜åˆ°ä½ŽæŽ’åºã€‚
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
    spent_by_category = {(r['category'] or 'å…¶ä»–'): float(r['total'] or 0.0) for r in spent_rows}

    result = []
    for b in budgets:
        cat = b['category']
        limit = float(b['monthly_limit'])
        spent = spent_by_category.get(cat, 0.0)
        pct = (spent / limit * 100.0) if limit > 0 else 0.0
        # ä¸¥æ ¼åŒºåˆ†è¶…æ”¯ä¸Žæ»¡é¢ï¼šåªæœ‰çœŸæ­£è¶…è¿‡é™é¢ (pct > 100) æ‰æ˜¯ over (è¶…æ”¯)
        # åˆšå¥½ç”¨æ»¡ 100% æ˜¯ reached (å·²è¾¾ä¸Šé™/æ»¡é¢)ï¼Œç»ä¸æ˜¯è¶…æ”¯
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
    æ£€æŸ¥æŸä¸ªåˆ†ç±»æœ¬æœˆèŠ±è´¹æ˜¯å¦æ–°è·¨è¶Šäº†ä¸€ä¸ªæé†’é˜ˆå€¼ï¼ˆ70% / 100% / 150%ï¼‰ã€‚
    æ¯ä¸ª (ç”¨æˆ·, åˆ†ç±», æœˆä»½, é˜ˆå€¼) ç»„åˆåªæé†’ä¸€æ¬¡ï¼Œé¿å…åŒä¸€æ¡£ä½åå¤å¼¹å‡ºæé†’ã€‚
    è‹¥ç¡®å®žè·¨è¶Šäº†æ–°çš„é˜ˆå€¼ï¼Œè¿”å›žè¯¥æé†’çš„è¯¦æƒ… dict å¹¶è½åº“ï¼›å¦åˆ™è¿”å›ž Noneã€‚
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
        'message': f'é¢„ç®—æé†’ï¼šã€Œ{category}ã€æœ¬æœˆå·²èŠ± RM{spent:.2f} / RM{limit:.2f}ï¼ˆ{round(pct)}%ï¼‰',
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
    """æŒ‰å½“å‰çœŸå®žæœˆä»½ç”Ÿæˆåˆ°æœŸçš„å›ºå®šæ”¶æ”¯è®°å½•ï¼ˆæ¯ä¸ªè§„åˆ™æ¯æœˆåªç”Ÿæˆä¸€æ¬¡ï¼‰ã€‚"""
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
# è‡ªç„¶è¯­è¨€å¿«é€Ÿè®°è´¦è§£æž
# ---------------------------------------------------------------------------

INCOME_MAIN_KEYWORDS = ['å·¥èµ„', 'è–ªèµ„', 'å‘è–ª', 'å¥–é‡‘', 'å¹´ç»ˆå¥–', 'ç»©æ•ˆ']
INCOME_SIDE_KEYWORDS = ['å‰¯ä¸š', 'è‡ªç”±èŒä¸š', 'å…¼èŒ', 'ç¨¿è´¹', 'ç§æ´»', 'æŠ•èµ„', 'ç†è´¢', 'åˆ†çº¢', 'åˆ©æ¯', 'å¤–å¿«']

INCOME_CATEGORY_KEYWORDS = {
    'å·¥èµ„': ['å·¥èµ„', 'è–ªèµ„', 'å‘è–ª'],
    'å¥–é‡‘': ['å¥–é‡‘', 'å¹´ç»ˆå¥–', 'ç»©æ•ˆ'],
    'è‡ªç”±èŒä¸š': ['è‡ªç”±èŒä¸š', 'ç¨¿è´¹', 'ç§æ´»', 'å†™ä½œ', 'è®¾è®¡è´¹'],
    'å…¼èŒ': ['å…¼èŒ', 'å¤–å¿«'],
    'æŠ•èµ„': ['æŠ•èµ„', 'ç†è´¢', 'åˆ†çº¢', 'åˆ©æ¯'],
}

EXPENSE_CATEGORY_KEYWORDS = {
    'é¤é¥®': [
        'åƒ', 'é¥­', 'é¤', 'å¤–å–', 'å¥¶èŒ¶', 'å’–å•¡', 'æ—©é¥­', 'åˆé¥­', 'æ™šé¥­', 'å¤œå®µ', 'é›¶é£Ÿ',
        'kfc', 'mcd', 'mcdonald', 'starbucks', 'zus', 'chagee', 'tealive', 'subway',
        'familymart', 'family mart', 'rotiboy', 'baker', 'kopitiam', 'restaurant',
        'nasi', 'cafe', 'food', 'din', 'bbq', 'sushi', 'pizza'
    ],
    'äº¤é€š': [
        'æ‰“è½¦', 'åœ°é“', 'å…¬äº¤', 'é«˜é“', 'ç«è½¦', 'æœºç¥¨', 'æ²¹è´¹', 'åœè½¦', 'äº¤é€š', 'å‡ºè¡Œ',
        'petronas', 'shell', 'caltex', 'bhp', 'petron', 'grab', 'touch n go', 'tng rfid',
        'parking', 'tng reload', 'toll', 'rapidkl', 'mrt', 'lrt', 'airasia'
    ],
    'æˆ¿ç§Ÿ': ['æˆ¿ç§Ÿ', 'ç§Ÿé‡‘', 'ç‰©ä¸šè´¹', 'rental', 'maintenance fee'],
    'è´­ç‰©': [
        'è´­ç‰©', 'æ·˜å®', 'äº¬ä¸œ', 'è¡£æœ', 'è¶…å¸‚', 'shopee', 'lazada', 'watsons', 'guardian',
        'uniqlo', 'lotus', 'aeon', 'jaya grocer', 'village grocer', 'mr diy', 'econsave',
        '99 speedmart', 'speedmart', 'donki', 'supermarket', 'mall'
    ],
    'å¨±ä¹': ['ç”µå½±', 'æ¸¸æˆ', 'å¨±ä¹', 'å”±æ­Œ', 'æ—…æ¸¸', 'æ™¯ç‚¹', 'steam', 'netflix', 'spotify', 'cinema', 'gsc', 'tgv'],
    'åŒ»ç–—': ['åŒ»é™¢', 'çœ‹ç—…', 'åŒ»ç–—', 'ä½“æ£€', 'è¯', 'clinic', 'hospital', 'pharmacy', 'dental'],
    'é€šè®¯': ['è¯è´¹', 'æµé‡', 'ç½‘è´¹', 'é€šè®¯', 'maxis', 'digi', 'celcom', 'umobile', 'unifi', 'tnb', 'air selangor'],
}

MERCHANT_CATEGORY_MAPPING = {
    # äº¤é€šåŠ æ²¹
    'petronas': 'äº¤é€š', 'shell': 'äº¤é€š', 'caltex': 'äº¤é€š', 'bhp': 'äº¤é€š', 'petron': 'äº¤é€š',
    'grab': 'äº¤é€š', 'touch n go': 'äº¤é€š', 'parking': 'äº¤é€š', 'toll': 'äº¤é€š', 'rapidkl': 'äº¤é€š',
    # é¤é¥®
    'familymart': 'é¤é¥®', 'family mart': 'é¤é¥®', 'kfc': 'é¤é¥®', 'mcdonald': 'é¤é¥®', 'mcd': 'é¤é¥®',
    'starbucks': 'é¤é¥®', 'zus': 'é¤é¥®', 'chagee': 'é¤é¥®', 'tealive': 'é¤é¥®', 'subway': 'é¤é¥®',
    'foodpanda': 'é¤é¥®', 'grabfood': 'é¤é¥®', 'kopitiam': 'é¤é¥®', 'restaurant': 'é¤é¥®', 'cafe': 'é¤é¥®',
    # è´­ç‰©è¶…å¸‚
    '99 speedmart': 'è´­ç‰©', 'speedmart': 'è´­ç‰©', 'lotus': 'è´­ç‰©', 'aeon': 'è´­ç‰©', 'watsons': 'è´­ç‰©',
    'guardian': 'è´­ç‰©', 'mr diy': 'è´­ç‰©', 'shopee': 'è´­ç‰©', 'lazada': 'è´­ç‰©', 'jaya grocer': 'è´­ç‰©',
    'village grocer': 'è´­ç‰©', 'econsave': 'è´­ç‰©', 'donki': 'è´­ç‰©',
    # æ°´ç”µé€šè®¯
    'tnb': 'é€šè®¯', 'unifi': 'é€šè®¯', 'maxis': 'é€šè®¯', 'celcom': 'é€šè®¯', 'digi': 'é€šè®¯', 'umobile': 'é€šè®¯'
}


def parse_auto_track_notification(raw_text):
    """
    è§£æžæ¥è‡ª TnG eWallet / Maybank MAE / Public Bank (MyPB) / é“¶è¡ŒçŸ­ä¿¡ / é€šçŸ¥æ çš„æ–‡æœ¬ã€‚
    æå–ï¼šé‡‘é¢ (RM)ã€å•†æˆ·å/æŽ¥æ”¶æ–¹ã€æ—¶é—´ã€è‡ªåŠ¨åŒ¹é…åˆ†ç±»ã€‚
    è‡ªåŠ¨è¿‡æ»¤ï¼šè¥é”€å¹¿å‘Šã€ä¿¡ç”¨å¡/è´·æ¬¾æŽ¨å¹¿ã€è¿”çŽ°æ´»åŠ¨å®£ä¼ ã€å®‰å…¨æé†’ã€OTP/TACéªŒè¯ç ç­‰éžåŠ¨è´¦é€šçŸ¥ã€‚
    """
    text = raw_text.strip()
    if not text:
        return None

    lower_text = text.lower()

    # 0. å¼ºåŠ›è¿‡æ»¤éžåŠ¨è´¦ç±»é€šçŸ¥ï¼ˆè¥é”€æŽ¨å¹¿ã€ä¿¡ç”¨å¡/è´·æ¬¾æŽ¨é”€ã€è¿”çŽ°æ´»åŠ¨å®£ä¼ ã€æŠ½å¥–ã€æ¡æ¬¾ã€OTP/TACéªŒè¯ç ã€å®‰å…¨æé†’ç­‰ï¼‰
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
        return {'is_promo': True, 'reason': 'å‘½ä¸­è¥é”€æŽ¨å¹¿æ´»åŠ¨æˆ–éžåŠ¨è´¦å®‰å…¨è¯åº“'}

    # 1. åŠ¨è´¦è¡Œä¸ºåŠ¨è¯ç¡¬æ€§æ£€æŸ¥ï¼ˆå¿…é¡»å…·å¤‡æ˜Žç¡®çœŸå®žçš„è´¢åŠ¡æ”¶æ”¯åŠ¨ä½œï¼Œæœç»æ™®é€šèµ„è®¯/å¹¿å‘Šè¢«è¯¯è®°è´¦ï¼‰
    is_expense = any(k in lower_text for k in [
        'paid', 'spent', 'payment to', 'payment of', 'payment successful', 'payment has been made',
        'deducted', 'debited', 'charged', 'transfer to', 'transferred to', 'transfer of',
        'purchase at', 'purchase of', 'withdrawal', 'withdrawn', 'duitnow qr', 'duitnow transfer to',
        'ä»˜æ¬¾', 'æ”¯å‡º', 'æ‰£æ¬¾', 'è½¬è´¦ç»™', 'å·²æ”¯ä»˜', 'ä¹°å•', 'æ¶ˆè´¹', 'æˆåŠŸæ”¯ä»˜', 'æˆåŠŸè½¬è´¦', 'æˆåŠŸæ‰£æ¬¾'
    ])

    is_income = any(k in lower_text for k in [
        'received from', 'received', 'credited', 'refund', 'cash in', 'deposit', 'salary', 'dividend',
        'duitnow transfer from', 'transfer from',
        'è½¬å…¥', 'æ”¶æ¬¾', 'å­˜å…¥', 'é€€æ¬¾', 'åˆ°è´¦', 'æ”¶åˆ°è½¬è´¦', 'å…¥è´¦'
    ])

    # è‹¥æ—¢ä¸æ˜¯æ˜Žç¡®çš„æ”¯å‡ºåŠ¨è¯ï¼Œä¹Ÿä¸æ˜¯æ˜Žç¡®çš„æ”¶å…¥åŠ¨è¯ï¼Œç›´æŽ¥åˆ¤å®šä¸ºéžäº¤æ˜“åŠ¨è´¦é€šçŸ¥å¹¶å¿½ç•¥
    if not is_expense and not is_income:
        return None

    tx_type = 'income' if is_income and not is_expense else 'expense'

    # 2. æå–é‡‘é¢ï¼šæ”¯æŒ "RM 15.00", "RM15.50", "RM 1,250.00", "MYR 20", "15.00"
    amount = None
    # ä¼˜å…ˆåŒ¹é…å¸¦ RM / MYR çš„æ ¼å¼ (å…è®¸åƒåˆ†ä½é€—å·)
    m_rm = re.search(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
    if m_rm:
        try:
            val_str = m_rm.group(1).replace(',', '')
            amount = float(val_str)
        except ValueError:
            amount = None

    if amount is None:
        # å›žé€€æå–æ™®é€šæ•°å­—ï¼ˆå…è®¸åƒåˆ†ä½ï¼‰
        nums = list(re.finditer(r'\b([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\b', text))
        if nums:
            try:
                val_str = nums[-1].group(1).replace(',', '')
                amount = float(val_str)
            except ValueError:
                pass

    if not amount or amount <= 0:
        return None

    # 3. æå–å•†æˆ· / äº¤æ˜“å¯¹æ‰‹
    # å¸¸è§æ ¼å¼æ¨¡å¼åŒ¹é…ï¼š
    # - "paid RM 15.00 to FamilyMart"
    # - "spent RM 45.00 at PETRONAS"
    # - "Transfer of RM 20.00 to Ali"
    # - "Payment to Starbucks of RM 12"
    merchant = ''
    m_to = re.search(r'(?:to|at|from|paid to|transfer to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,35})', text, re.IGNORECASE)
    if m_to:
        m_str = m_to.group(1).strip()
        # æ¸…ç†åŽç»­å¹²æ‰°è¯å¦‚ on, via, using, ref, date, claim, cashback, voucher ç­‰ä»¥åŠå¥å·/æ¢è¡Œ
        m_cleaned = re.split(r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|for|date|txid|claim|get|earn|earned|cashback|voucher|was|is|successful)\b', m_str, flags=re.IGNORECASE)[0]
        merchant = m_cleaned.strip(' .,-')

    if not merchant:
        # å°è¯•ä¸­æ–‡æ ¼å¼ï¼šâ€œåœ¨ã€å…¨å®¶ã€‘æ¶ˆè´¹â€ã€â€œå‘ã€å¼ ä¸‰ã€‘è½¬è´¦â€
        m_cn = re.search(r'(?:åœ¨|å‘)\s*([A-Za-z0-9\u4e00-\u9fa5\s&]{2,20})\s*(?:æ¶ˆè´¹|è½¬è´¦|ä»˜æ¬¾)', text)
        if m_cn:
            merchant = m_cn.group(1).strip()

    if not merchant:
        merchant = 'è‡ªåŠ¨è¿½è¸ªæ¶ˆè´¹' if tx_type == 'expense' else 'è‡ªåŠ¨è¿½è¸ªå…¥è´¦'

    # 4. è‡ªåŠ¨å½’ç±»åˆ†ç±» (Category)
    category = 'å…¶ä»–'
    if tx_type == 'income':
        category = 'å…¶ä»–'
        group_name = 'side'
    else:
        group_name = None
        # ä¼˜å…ˆé€šè¿‡å•†æˆ·ååŒ¹é…æ˜ å°„è¡¨
        matched_cat = None
        m_lower = merchant.lower()
        for kw, cat in MERCHANT_CATEGORY_MAPPING.items():
            if kw in m_lower or kw in lower_text:
                matched_cat = cat
                break

        if not matched_cat:
            # æ¬¡ä¼˜æŒ‰é€šç”¨æ”¯å‡ºåˆ†ç±»å…³é”®è¯è¯åº“åŒ¹é…
            for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
                if any(k in m_lower or k in lower_text for k in kws):
                    matched_cat = cat
                    break

        category = matched_cat if matched_cat else 'å…¶ä»–'

    # 5. æå–æ—¥æœŸï¼ˆè‹¥æ— æ³•ä»Žæ–‡æœ¬ä¸­è§£æžå‡º YYYY-MM-DDï¼Œåˆ™é»˜è®¤å½“å‰æ—¥æœŸï¼‰
    tx_date = date.today().isoformat()
    m_date = re.search(r'(\d{4}[-/.]\d{1,2}[-/.]\d{1,2})', text)
    if m_date:
        try:
            d_str = m_date.group(1).replace('/', '-').replace('.', '-')
            # æ ¼å¼åŒ–ç»Ÿä¸€ä¸º YYYY-MM-DD
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
    """ä»Žä¸€å¥è‡ªç„¶è¯­è¨€æ–‡æœ¬ä¸­è§£æžå‡ºé‡‘é¢/ç±»åž‹/åˆ†ç»„/åˆ†ç±»ï¼Œä»…è¿”å›žè‰ç¨¿ï¼Œä¸ç›´æŽ¥å…¥åº“ã€‚"""
    text = text.strip()
    warnings = []

    amount_matches = list(re.finditer(r'\d+(\.\d+)?', text))
    if not amount_matches:
        return None, ['æœªèƒ½è¯†åˆ«å‡ºé‡‘é¢ï¼Œè¯·æ‰‹åŠ¨å¡«å†™']
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
            category = 'å·¥èµ„' if group_name == 'main' else 'è‡ªç”±èŒä¸š'
            warnings.append('æœªèƒ½ç²¾ç¡®åŒ¹é…æ”¶å…¥å­åˆ†ç±»ï¼Œå·²ä½¿ç”¨é»˜è®¤åˆ†ç±»ï¼Œè¯·æ£€æŸ¥')
    else:
        for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
            if any(k in text for k in kws):
                category = cat
                break
        if category is None:
            category = 'å…¶ä»–'
            warnings.append('æœªèƒ½åŒ¹é…æ”¯å‡ºåˆ†ç±»ï¼Œå·²å½’ä¸º"å…¶ä»–"ï¼Œè¯·æ£€æŸ¥')

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
# ä»ªè¡¨ç›˜
# ---------------------------------------------------------------------------

@app.route('/')
def index():
    user_id = get_current_user_id()
    generated = generate_due_recurring(user_id)
    if generated:
        flash(f'å·²è‡ªåŠ¨ç”Ÿæˆæœ¬æœˆå›ºå®šæ”¶æ”¯ {generated} æ¡', 'success')

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

    # æœ¬æœˆå‡€ç»“ä½™ï¼šæ”¶å…¥ - æ—¥å¸¸æ”¯å‡º - å­˜å…¥å‚¨è“„ï¼ˆä»Žå‚¨è“„æ‰£é™¤çš„æ”¯å‡ºä¸æ‰£å½“æœˆç»“ä½™ï¼‰
    balance = total_income - regular_expense - month_savings_in

    # å‚¨è“„èµ„é‡‘æ± æ˜Žç»†ä¸Žç´¯è®¡æ€»å‚¨è“„
    savings_pool_by_category, total_savings_pool = get_savings_breakdown(db, user_id)

    income_group = {'main': 0.0, 'side': 0.0}
    expense_by_category = {}
    for r in rows:
        if r['type'] == 'income':
            gname = r['group_name'] or 'main'
            income_group[gname] = income_group.get(gname, 0.0) + r['amount']
        elif r['type'] == 'expense':
            c = r['category'] or 'å…¶ä»–'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']

    # æ”¯å‡ºåˆ†ç±»æŒ‰é‡‘é¢ä»Žé«˜åˆ°ä½ŽæŽ’åºï¼Œç¡®ä¿å›¾è¡¨ä¸Žå›¾ä¾‹è§†è§‰ç»Ÿä¸€ä¸”çªå‡ºé‡ç‚¹
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
            c = r['category'] or 'å…¶ä»–'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']

    sorted_expenses = sorted(expense_by_category.items(), key=lambda x: x[1], reverse=True)

    return jsonify({
        'ok': True,
        'month': month,
        'income': {
            'labels': ['ä¸»ä¸šæ”¶å…¥', 'å‰¯ä¸šæ”¶å…¥'],
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

    # å¦‚æžœæ˜¯ all æˆ– customï¼ŒåŠ¨æ€æ ¹æ®è®°å½•æˆ–å‚æ•°ç”Ÿæˆè¿žç»­æœˆä»½
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
            cat = r['category'] or 'å…¶ä»–'
            expense_cats[cat] = expense_cats.get(cat, 0.0) + amt
        elif t == 'savings':
            total_savings += amt
            monthly_stats[m]['savings'] = monthly_stats[m].get('savings', 0.0) + amt
            cat = r['category'] or 'å‚¨è“„'
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

    # æœˆå‡è®¡ç®—ï¼šæœ‰æœˆä»½è·¨åº¦æŒ‰è·¨åº¦ç®—ï¼Œå¦åˆ™æŒ‰å®žé™…æœ‰è®°å½•çš„æœˆä»½æ•°ï¼Œè‡³å°‘ä¸º 1
    num_months = max(len(month_keys), 1)
    avg_income = total_income / num_months
    avg_expense = total_expense / num_months
    avg_savings = total_savings / num_months
    net_savings = total_income - regular_expense - total_savings

    all_savings_in = db.execute("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE user_id = ? AND type='savings'", (user_id,)).fetchone()[0] or 0.0
    all_savings_out = db.execute("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE user_id = ? AND type='expense' AND COALESCE(from_savings, 0)=1", (user_id,)).fetchone()[0] or 0.0
    total_savings_pool = max(float(all_savings_in) - float(all_savings_out), 0.0)

    # æ”¯å‡ºåˆ†ç±»æŒ‰é‡‘é¢é™åºæŽ’åº
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
# æ”¯å‡ºåˆ†ç±»æ·±åº¦æ´žå¯ŸæŠ¥å‘Š (Category Breakdown & Insights)
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

    # ç»Ÿè®¡å½“æœˆä¸Žä¸Šæœˆçš„ç»å¯¹æ”¯å‡º
    cur_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{current_month}%")
    ).fetchall()
    cur_month_map = {r['category'] or 'å…¶ä»–': float(r['total'] or 0) for r in cur_month_rows}

    prev_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{prev_month}%")
    ).fetchall()
    prev_month_map = {r['category'] or 'å…¶ä»–': float(r['total'] or 0) for r in prev_month_rows}

    # ç»Ÿè®¡åˆ†ç±»æ±‡æ€»åŠæœˆåº¦åˆ†å¸ƒ
    cat_stats = {}
    for r in rows:
        cat = r['category'] or 'å…¶ä»–'
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
                trend_text = "æŒå¹³ 0%"
        else:
            if cur_amt > 0:
                trend_dir = 'up'
                trend_text = "æœ¬æœˆæ–°å¢ž"
            else:
                trend_dir = 'flat'
                trend_text = "â€”"

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
# äº¤æ˜“è®°å½•ï¼šæ–°å¢ž / ç¼–è¾‘ / åˆ é™¤ / åˆ—è¡¨
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
            return jsonify({'ok': False, 'message': 'é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—'}), 400
        flash('é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—', 'error')
        return redirect(url_for('index'))

    tx_type = f.get('type')
    group_name = f.get('group_name') or None
    if tx_type != 'income':
        group_name = None

    from_savings = 1 if (tx_type == 'expense' and f.get('from_savings') in ('1', 'true', 'on')) else 0
    from_savings_category = (f.get('from_savings_category') or '').strip() if from_savings else None
    if from_savings and not from_savings_category:
        from_savings_category = f.get('category') or 'å‚¨è“„'

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
        # å®žæ—¶è®¡ç®—å½“æœˆçš„æœ€æ–°æ”¶å…¥æž„æˆä¸Žæ”¯å‡ºåˆ†ç±»å æ¯”ï¼Œä¾›å‰ç«¯å³æ—¶å±€éƒ¨æ›´æ–°å›¾è¡¨ä¸Žå›¾ä¾‹
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
                c = r['category'] or 'å…¶ä»–'
                m_exp[c] = m_exp.get(c, 0.0) + r['amount']

        return jsonify({
            'ok': True,
            'message': 'è®°å½•å·²æ·»åŠ ',
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
                    'labels': ['ä¸»ä¸šæ”¶å…¥', 'å‰¯ä¸šæ”¶å…¥'],
                    'values': [round(m_inc.get('main', 0.0), 2), round(m_inc.get('side', 0.0), 2)]
                },
                'expense': {
                    'labels': [k for k, _ in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)],
                    'values': [round(v, 2) for _, v in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)]
                }
            },
            'budget_alert': budget_alert
        })

    flash('è®°å½•å·²æ·»åŠ ', 'success')
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

        # æ£€æŸ¥æ˜¯å¦ä¸º auto_track æ¥æºä¸”ä¿®æ”¹äº†åˆ†ç±»ï¼Œè‹¥æ˜¯åˆ™è®°å¿†å•†æˆ·-åˆ†ç±»æ˜ å°„è¦†ç›–
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
            from_savings_category = f.get('category') or 'å‚¨è“„'

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
            return jsonify({'ok': True, 'message': 'è®°å½•å·²æ›´æ–°', 'budget_alert': budget_alert})

        flash('è®°å½•å·²æ›´æ–°', 'success')
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
        return jsonify({'ok': True, 'message': 'è®°å½•å·²åˆ é™¤', 'id': tx_id})

    flash('è®°å½•å·²åˆ é™¤', 'success')
    return redirect(url_for('records'))


@app.route('/records/batch-delete', methods=['POST'])
def batch_delete_records():
    user_id = get_current_user_id()
    db = get_db()
    ids = request.form.getlist('ids')
    if not ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'æœªé€‰ä¸­ä»»ä½•è®°å½•'}), 400
        flash('æœªé€‰ä¸­ä»»ä½•è®°å½•', 'error')
        return redirect(url_for('records'))

    # å®‰å…¨åœ°è¿‡æ»¤æ•°å­— ID
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
            return jsonify({'ok': True, 'message': f'æˆåŠŸæ‰¹é‡åˆ é™¤ {len(valid_ids)} æ¡è®°å½•', 'deleted_ids': valid_ids})

        flash(f'æˆåŠŸæ‰¹é‡åˆ é™¤ {len(valid_ids)} æ¡è®°å½•', 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'æœªé€‰ä¸­æœ‰æ•ˆçš„è®°å½•'}), 400
        flash('æœªé€‰ä¸­æœ‰æ•ˆçš„è®°å½•', 'error')

    return redirect(url_for('records'))


@app.route('/records/batch-edit', methods=['POST'])
def batch_edit_records():
    """æ‰¹é‡ä¿®æ”¹è®°å½•çš„åˆ†ç±»ä¸Žç±»åž‹"""
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
            return jsonify({'ok': False, 'message': 'æœªé€‰ä¸­ä»»ä½•è®°å½•'}), 400
        flash('æœªé€‰ä¸­ä»»ä½•è®°å½•', 'error')
        return redirect(url_for('records'))

    valid_ids = []
    for i in ids:
        try:
            valid_ids.append(int(i))
        except ValueError:
            pass

    if not valid_ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'æœªé€‰ä¸­æœ‰æ•ˆçš„è®°å½•'}), 400
        flash('æœªé€‰ä¸­æœ‰æ•ˆçš„è®°å½•', 'error')
        return redirect(url_for('records'))

    if not new_type and not new_category:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'æœªæŒ‡å®šéœ€è¦ä¿®æ”¹çš„åˆ†ç±»æˆ–ç±»åž‹'}), 400
        flash('æœªæŒ‡å®šéœ€è¦ä¿®æ”¹çš„åˆ†ç±»æˆ–ç±»åž‹', 'warning')
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

    # æ‰¹é‡ä¿®æ”¹åŽï¼Œå¯¹æ¶‰åŠåˆ°çš„æ¯ä¸ªæ”¯å‡ºåˆ†ç±»å„æ£€æŸ¥ä¸€æ¬¡æ˜¯å¦éœ€è¦å‘å‡ºè¶…æ”¯æé†’ï¼ˆåŽ»é‡è¡¨ä¿è¯ä¸ä¼šé‡å¤å¼¹å‡ºï¼‰
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
        return jsonify({'ok': True, 'message': f'æˆåŠŸæ‰¹é‡ä¿®æ”¹ {len(valid_ids)} æ¡è®°å½•', 'edited_ids': valid_ids, 'budget_alerts': budget_alerts})

    flash(f'æˆåŠŸæ‰¹é‡ä¿®æ”¹ {len(valid_ids)} æ¡è®°å½•', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# è‡ªç„¶è¯­è¨€å¿«é€Ÿè®°è´¦
# ---------------------------------------------------------------------------

def clean_and_parse_json(raw_str):
    """ä»Ž LLM è¿”å›žçš„æ–‡æœ¬ä¸­ç¨³å¥æå–å¹¶è§£æž JSON å¯¹è±¡"""
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
    é€šç”¨å¤šæº LLM JSON æŽ¥å£ï¼š
    1. ä¼˜å…ˆ Google Gemini 2.5 Flash
    2. æ¬¡é€‰ DeepSeek / OpenAI
    3. æ¬¡é€‰ æœ¬åœ° Ollama
    4. å¤±è´¥è¿”å›ž Noneï¼Œè°ƒç”¨æ–¹è‡ªåŠ¨é™çº§åˆ°è§„åˆ™å¼•æ“Ž
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
    """ä»Ž llm_learning_samples æ•°æ®åº“è¯»å–æ ·æœ¬ï¼Œæž„é€ æä¾›ç»™ LLM æç¤ºè¯çš„åŠ¨æ€å‚è€ƒæ¡ˆä¾‹åº“"""
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
            "[LEARNING SAMPLES & REFERENCE DATASHEET / è¯­è¨€å­¦ä¹ æ ·æœ¬åº“ä¸Žåˆ¤å®šç¤ºèŒƒ]:",
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
    æ™ºèƒ½è¥é”€/å¹¿å‘Šè¿‡æ»¤ä¸Žå…³é”®è¦ç´ æå–ï¼š
    é€šè¿‡ LLM æ·±åº¦æ ¡éªŒé€šçŸ¥æ˜¯å¦ä¸ºçœŸå®žå‘ç”Ÿçš„äº¤æ˜“ï¼ˆæ‰£æ¬¾/å…¥è´¦ï¼‰ï¼Œè¿˜æ˜¯è¥é”€æŽ¨å¹¿ã€æŠ½å¥–ã€ä¿¡ç”¨å¡åŠžå¡æŽ¨å¹¿ã€è¿”çŽ°æ´»åŠ¨æˆ–OTPéªŒè¯ç ã€‚
    è¿”å›ž: (is_real: bool, llm_data: dict or None)
    é‡‡ç”¨ Fail-open ç­–ç•¥ï¼šè‹¥ LLM ç¦»çº¿æˆ–è¶…æ—¶ï¼Œæ”¾è¡Œå¹¶è¿”å›ž (True, None)ï¼Œç¡®ä¿ä¸æ¼è®°çœŸå®žäº¤æ˜“ã€‚
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
        "4. Standard expense categories: é¤é¥®, äº¤é€š, è´­ç‰©, å¨±ä¹, å±…ä½, åŒ»ç–—, æ•™è‚², é€šè®¯, æ—…è¡Œ, äººæƒ…, å…¶ä»–.\n"
        "5. Standard income categories: å·¥èµ„, å¥–é‡‘, æŠ•èµ„, è‡ªç”±èŒä¸š, å…¶ä»–.\n"
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

    # æ— æ³•é€šè¿‡ LLM åˆ¤å®šæ—¶ï¼Œæ‰§è¡Œ Fail-openï¼Œæ”¾è¡ŒçœŸå®žäº¤æ˜“
    return True, None


def parse_nlp_with_llm(text):
    """
    é€šè¿‡ LLM å°†å£è¯­åŒ–è‡ªç„¶è¯­è¨€æ–‡æœ¬è§£æžä¸ºæ ‡å‡†è®°è´¦å¯¹è±¡ã€‚
    è¿”å›ž dict æˆ– None
    """
    system_instruction = (
        "You are an intelligent accounting parser for a personal ledger app in Malaysia.\n"
        "Parse colloquial natural language entries (in Chinese or English or Malay) into a structured ledger transaction.\n"
        "Categories allowed:\n"
        "- expense: é¤é¥®, äº¤é€š, è´­ç‰©, å¨±ä¹, å±…ä½, åŒ»ç–—, æ•™è‚², é€šè®¯, æ—…è¡Œ, äººæƒ…, å…¶ä»–\n"
        "- income: å·¥èµ„, å¥–é‡‘, æŠ•èµ„, è‡ªç”±èŒä¸š, å…¶ä»– (group_name is 'main' for salary/main job, 'side' for side gig/investment)\n"
        "- savings: åº”æ€¥é‡‘, å…»è€, æ—…æ¸¸, å¿ƒæ„¿, å…¶ä»–\n"
        f"- Assume today is {date.today().isoformat()}. Parse relative dates like 'æ˜¨å¤©', 'å‰å¤©', 'yesterday' correctly.\n"
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
                    'category': str(res.get('category') or 'å…¶ä»–'),
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
        return {'ok': False, 'message': 'è¯·è¾“å…¥å†…å®¹åŽå†ç‚¹æ™ºèƒ½è§£æž'}

    # 1. ä¼˜å…ˆè°ƒç”¨ LLM æ·±åº¦æ™ºèƒ½è§£æž
    llm_parsed = parse_nlp_with_llm(text)
    if llm_parsed and llm_parsed.get('amount'):
        return {
            'ok': True,
            'parsed': llm_parsed,
            'source': 'llm',
            'warnings': [],
            'original_text': text
        }

    # 2. å›žé€€åˆ°æœ¬åœ°è§„åˆ™è§£æžå™¨
    parsed, warnings = parse_nlp_text(text)
    if parsed is None:
        return {'ok': False, 'message': 'è§£æžå¤±è´¥ï¼š' + 'ï¼›'.join(warnings) + 'ã€‚è¯·æ”¹ç”¨ä¸‹æ–¹å¿«é€Ÿå½•å…¥è¡¨å•æ‰‹åŠ¨å¡«å†™ã€‚'}

    return {'ok': True, 'parsed': parsed, 'source': 'rule', 'warnings': warnings, 'original_text': text}


@app.route('/api/auto-track', methods=['GET', 'POST'])
@csrf.exempt
def api_auto_track():
    # é‰´æƒæ£€æŸ¥ï¼šåªæŽ¥å— Header X-API-KEY æˆ– JSON/è¡¨å• body é‡Œçš„ keyï¼Œä¸å†æŽ¥å— URL å‚æ•° ?key=xxxã€‚
    # URL å‚æ•°é‡Œçš„å¯†é’¥å¾ˆå®¹æ˜“è¢« server access logã€æµè§ˆå™¨åŽ†å²è®°å½•ã€åå‘ä»£ç†æ—¥å¿—ç•™ä¸‹ç—•è¿¹ï¼Œ
    # ä¹‹å‰æ³„æ¼çš„é‚£æŠŠ key å°±æ˜¯ä»Žç±»ä¼¼çš„åœ°æ–¹å¤–æµçš„ï¼Œæ‰€ä»¥è¿™é‡Œåˆ»æ„ä¸ç•™è¿™ä¸ªå…¥å£ã€‚
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
        return jsonify({'ok': False, 'message': 'API Key æ— æ•ˆæˆ–æœªåœ¨æœåŠ¡å™¨é…ç½®ï¼Œæ‹’ç»è®¿é—®'}), 401

    # èŽ·å–é€šçŸ¥æ–‡æœ¬ï¼šä¼˜å…ˆä»Ž Query å‚æ•°èŽ·å–ï¼Œå†ä»Ž JSON / è¡¨å• / Raw Payload èŽ·å–
    raw_payload = request.get_data(as_text=True)
    text = request.args.get('text') or ""

    # 1. å°è¯•ä»Ž JSON æå–ï¼ˆå¦‚æžœ Query å‚æ•°æœªæä¾›ï¼‰
    if not text and request.is_json:
        try:
            data = request.get_json(silent=True) or {}
            if isinstance(data, dict):
                text = data.get('text') or data.get('body') or data.get('message') or ""
        except Exception:
            pass

    # 2. å°è¯•ä»Ž Form è¡¨å•æå–
    if not text:
        text = request.form.get('text') or request.form.get('body') or request.form.get('message') or ""

    # 3. å°è¯•ä»Ž Raw Payload æå– (è¿‡æ»¤æ— æ„ä¹‰çš„ç©ºæˆ–æžçŸ­å­—ç¬¦)
    if not text and raw_payload and len(raw_payload.strip()) > 3:
        # å¦‚æžœæ˜¯ JSON å­—ç¬¦ä¸²ä½†å«æ¢è¡Œå¯¼è‡´ get_json å¤±è´¥ï¼Œåšå®½å®¹æ­£åˆ™æå–
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
    # å¦‚æžœ payload æ˜¯ç±»ä¼¼ text=... çš„ urlencoded å½¢å¼ï¼Œè‡ªåŠ¨è§£å‡º
    if text.startswith('text='):
        from urllib.parse import unquote
        text = unquote(text[5:]).strip()

    print(f"[AUTO_TRACK] Extracted text: {repr(text[:120])}")

    if not text or text == "None" or text == "null":
        return jsonify({
            'ok': False,
            'message': 'æœªæ”¶åˆ°æœ‰æ•ˆçš„é€šçŸ¥æ–‡æœ¬å†…å®¹ï¼ˆè‹¥ä¸ºæ‰‹åŠ¨æµ‹è¯•ï¼Œè¯·ç¡®ä¿å½“å‰é€šçŸ¥æ å­˜åœ¨çœŸå®žçš„æ‰£æ¬¾é€šçŸ¥ï¼‰'
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
            'message': 'é€šçŸ¥è¢«è¯†åˆ«ä¸ºè¥é”€æŽ¨å¹¿æ´»åŠ¨æˆ–éžåŠ¨è´¦é€šçŸ¥ï¼Œå·²è‡ªåŠ¨å¿½ç•¥å…¥è´¦',
            'reason': parsed.get('reason'),
            'raw_text': text
        }), 200

    if not parsed or not parsed.get('amount'):
        if AUTO_TRACK_DEBUG_LOG:
            print("[AUTO_TRACK DEBUG] Failed to parse amount! Returning 422")
        return jsonify({
            'ok': False,
            'message': 'æœªèƒ½ä»Žé€šçŸ¥ä¸­æå–å‡ºæœ‰æ•ˆé‡‘é¢æˆ–å•†æˆ·ä¿¡æ¯',
            'raw_text': text
        }), 422

    # ä¼˜å…ˆæ£€æŸ¥æ˜¯å¦å­˜åœ¨å•†æˆ·åŽ†å²æ‰‹åŠ¨çº åè®°å½•ï¼ˆç²¾ç¡®åŒ¹é…æå–åˆ°çš„å•†æˆ·/å¤‡æ³¨åï¼Œä¼˜å…ˆçº§é«˜äºŽé»˜è®¤æŽ¨æ–­ä¸Ž LLM åˆ†ç±»ï¼‰
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

    # Phase-2: LLM è¥é”€å¹¿å‘ŠäºŒæ¬¡æ ¡éªŒä¸Žè¦ç´ æ™ºèƒ½å¢žå¼ºï¼ˆFail-open ç­–ç•¥ï¼‰
    is_real, llm_data = classify_notification_with_llm(text)

    if not is_real:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Notification rejected by Phase-2 LLM as promotional: {repr(text)}")
        return jsonify({
            'ok': False,
            'verdict': 'rejected_promo',
            'message': 'é€šçŸ¥è¢«è¯†åˆ«ä¸ºè¥é”€æŽ¨å¹¿æˆ–éžçœŸå®žäº¤æ˜“ï¼Œå·²å¿½ç•¥å…¥è´¦',
            'parsed': parsed,
            'raw_text': text
        }), 200

    # æ™ºèƒ½å¢žå¼ºï¼šå¦‚æžœ LLM æå–åˆ°äº†æ›´ç²¾å‡†çš„åˆ†ç±»ã€å•†æˆ·åç§°æˆ–è½¬è´¦è¿›è´¦æ–¹å‘
    if llm_data and isinstance(llm_data, dict):
        label_type = llm_data.get('label_type')
        if label_type == 'income_transfer':
            parsed['type'] = 'income'
            if not parsed.get('group_name'):
                parsed['group_name'] = 'main' if any(k in text.lower() for k in ['salary', 'payroll', 'å·¥èµ„', 'è–ªèµ„', 'è–ªæ°´']) else 'side'
        elif label_type in ('expense', 'expense_transfer'):
            parsed['type'] = 'expense'
            parsed['group_name'] = None

        if parsed.get('category') == 'å…¶ä»–' and llm_data.get('category') and llm_data['category'] != 'å…¶ä»–':
            parsed['category'] = str(llm_data['category']).strip()
        if parsed.get('note') in ('è‡ªåŠ¨è¿½è¸ªæ¶ˆè´¹', 'è‡ªåŠ¨è¿½è¸ªå…¥è´¦') and llm_data.get('merchant'):
            parsed['note'] = str(llm_data['merchant']).strip()

    # å…¥åº“å†™å…¥äº¤æ˜“è®°å½•
    db = get_db()
    now = datetime.now().isoformat()
    # ç¡®å®šå…¥è´¦å½’å±žç”¨æˆ·ï¼ˆæ”¯æŒå‚æ•°æŒ‡å®š user_id æˆ– usernameï¼Œä¿åº•ä½¿ç”¨ admin æˆ–é¦–ä½ç”¨æˆ·ï¼‰
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

    type_text = 'æ”¯å‡º' if parsed['type'] == 'expense' else 'æ”¶å…¥' if parsed['type'] == 'income' else 'å‚¨è“„'
    note_str = f" ({parsed['note']})" if parsed['note'] else ""
    notification_title = "è‡ªåŠ¨è®°è´¦æˆåŠŸ ðŸ’¸"
    notification_body = f"å·²è‡ªåŠ¨è®°å…¥ã€{type_text} Â· {parsed['category']}ã€‘{money_filter(parsed['amount'])}{note_str}"

    budget_alert = None
    if parsed['type'] == 'expense':
        budget_alert = check_and_record_budget_alerts(db, target_user_id, parsed['category'], parsed['date'][:7])

    return jsonify({
        'ok': True,
        'verdict': 'accepted',
        'message': f"æˆåŠŸè‡ªåŠ¨è®°è´¦ï¼š{parsed['note']} {money_filter(parsed['amount'])} ({parsed['category']})",
        'transaction_id': cur.lastrowid,
        'parsed': parsed,
        'notification_title': notification_title,
        'notification_body': notification_body,
        'budget_alert': budget_alert
    }), 201


@app.route('/api/categories', methods=['GET'])
@csrf.exempt
def api_get_categories():
    """èŽ·å–æ‰€æœ‰å¯ç”¨åˆ†ç±»åˆ—è¡¨ï¼ˆä¸“ä¾› Android ç«¯ç¦»çº¿ç¼“å­˜ä¸Žä¸‹æ‹‰é€‰æ‹©ä½¿ç”¨ï¼‰ã€‚
    åªè®¤ X-API-KEYï¼Œä¸æŽ¥å— session cookie ç™»å½•çŠ¶æ€ â€”â€” è¿™ä¸ªç«¯ç‚¹ä»Žæœªè¢«ç½‘é¡µç«¯è°ƒç”¨è¿‡ï¼Œ
    ä¿ç•™ cookie å½“å¤‡ç”¨è®¤è¯æ–¹å¼åªä¼šå¹³ç™½è®©å®ƒæš´éœ²åœ¨ CSRF æ”»å‡»é¢ä¸‹ï¼Œæ²¡æœ‰å®žé™…ç”¨é€”ã€‚"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key æ— æ•ˆ'}), 401

    user_id = get_current_user_id()
    db = get_db()
    rows = db.execute('SELECT id, name, type, group_name FROM categories WHERE user_id = ? ORDER BY type, id', (user_id,)).fetchall()
    categories = [{'id': r['id'], 'name': r['name'], 'type': r['type'], 'group_name': r['group_name']} for r in rows]
    return jsonify({'ok': True, 'categories': categories})


@app.route('/api/transactions/sync', methods=['POST'])
@csrf.exempt
def api_sync_transactions():
    """æ‰¹é‡åŒæ­¥ç§»åŠ¨ç«¯ç¦»çº¿è®°è´¦æ•°æ®ã€‚åªè®¤ X-API-KEYï¼Œä¸æŽ¥å— session cookie â€”â€” è¿™ä¸ªç«¯ç‚¹
    ä»Žæœªè¢«ç½‘é¡µç«¯è°ƒç”¨è¿‡ï¼Œä¿ç•™ cookie å¤‡ç”¨è®¤è¯åªä¼šå¹³ç™½è®©å†™å…¥æ“ä½œæš´éœ²åœ¨ CSRF æ”»å‡»é¢ä¸‹ã€‚"""
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key æ— æ•ˆ'}), 401

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
                    item.get('category') or 'å…¶ä»–',
                    float(item.get('amount') or 0.0),
                    item.get('note') or 'ç¦»çº¿å½•å…¥',
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
    """ä¸‹è½½ 100% åŽŸç”Ÿä¸“å±ž Android ä¼´ä¾£ App å®‰è£…åŒ… (å… MacroDroid / é›¶ç¬¬ä¸‰æ–¹å·¥å…·)"""
    download_dir = os.path.join(app.root_path, 'static', 'download')
    return send_from_directory(download_dir, 'ledger-app.apk', as_attachment=True, download_name='æˆ‘çš„è´¦æœ¬.apk')


@app.route('/auto-track')
def auto_track_page():
    """Auto Track é…ç½®ä¸Žæµ‹è¯•é¡µé¢"""
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
# LLM è¯­è¨€å­¦ä¹ æ ·æœ¬åº“ (Few-Shot Datasheet ç®¡ç†æŽ¥å£)
# ---------------------------------------------------------------------------

@app.route('/api/llm-samples', methods=['GET'])
def api_llm_samples_list():
    db = get_db()
    rows = db.execute("SELECT * FROM llm_learning_samples ORDER BY id ASC").fetchall()
    return jsonify({'ok': True, 'samples': [dict(r) for r in rows]})


@app.route('/api/llm-samples/add', methods=['POST'])
def api_llm_samples_add():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': 'è¯·å…ˆç™»å½•åŽå†æ·»åŠ æ ·æœ¬'}), 401
    db = get_db()
    data = request.get_json(silent=True) or request.form
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'ok': False, 'message': 'æ ·æœ¬é€šçŸ¥æ–‡æœ¬ä¸èƒ½ä¸ºç©º'}), 400

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

    return jsonify({'ok': True, 'message': 'æˆåŠŸå½•å…¥å­¦ä¹ æ ·æœ¬åº“ï¼å¤§æ¨¡åž‹ä¸‹æ¬¡é‡åˆ°ç±»ä¼¼é€šçŸ¥å°†ç…§æ­¤å­¦ä¹ ã€‚'})


@app.route('/api/llm-samples/delete/<int:sample_id>', methods=['POST'])
def api_llm_samples_delete(sample_id):
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': 'è¯·å…ˆç™»å½•'}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples WHERE id = ?", (sample_id,))
    db.commit()
    return jsonify({'ok': True, 'message': 'æ ·æœ¬å·²æˆåŠŸåˆ é™¤'})


@app.route('/api/llm-samples/reset', methods=['POST'])
def api_llm_samples_reset():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': 'è¯·å…ˆç™»å½•'}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples")
    db.commit()
    seed_learning_samples(db)
    return jsonify({'ok': True, 'message': 'å·²æˆåŠŸå°†å­¦ä¹ æ ·æœ¬åº“æ¢å¤ä¸ºå®˜æ–¹é¢„è®¾è¯­æ–™åº“ï¼'})


# ---------------------------------------------------------------------------
# åˆ†ç±»ç®¡ç†
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
            return jsonify({'ok': False, 'message': 'åˆ†ç±»åç§°ä¸èƒ½ä¸ºç©º'}), 400
        flash('åˆ†ç±»åç§°ä¸èƒ½ä¸ºç©º', 'error')
        return redirect(url_for('categories_page'))
    try:
        db.execute('INSERT INTO categories (user_id, type, group_name, name) VALUES (?,?,?,?)', (user_id, type_, group_name, name))
        db.commit()
        if is_ajax_request():
            return jsonify({'ok': True, 'message': 'åˆ†ç±»å·²æ·»åŠ '})
        flash('åˆ†ç±»å·²æ·»åŠ ', 'success')
    except sqlite3.IntegrityError:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'è¯¥åˆ†ç±»å·²å­˜åœ¨'}), 400
        flash('è¯¥åˆ†ç±»å·²å­˜åœ¨', 'error')
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
        return jsonify({'ok': True, 'message': 'åˆ†ç±»å·²åˆ é™¤ï¼ˆåŽ†å²è®°å½•ä¸­çš„æ—§æ•°æ®ä¸å—å½±å“ï¼‰', 'id': cat_id})
    flash('åˆ†ç±»å·²åˆ é™¤ï¼ˆåŽ†å²è®°å½•ä¸­çš„æ—§æ•°æ®ä¸å—å½±å“ï¼‰', 'success')
    return redirect(url_for('categories_page'))


@app.route('/categories/<int:cat_id>/budget', methods=['POST'])
def set_category_budget(cat_id):
    """è®¾ç½®æˆ–å–æ¶ˆæŸä¸ªæ”¯å‡ºåˆ†ç±»çš„æœˆåº¦é¢„ç®—ä¸Šé™"""
    user_id = get_current_user_id()
    db = get_db()
    cat = db.execute("SELECT name FROM categories WHERE id = ? AND user_id = ? AND type = 'expense'", (cat_id, user_id)).fetchone()
    if not cat:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'åˆ†ç±»ä¸å­˜åœ¨'}), 404
        flash('åˆ†ç±»ä¸å­˜åœ¨', 'error')
        return redirect(url_for('categories_page'))

    raw_limit = (request.form.get('monthly_limit') or '').strip()
    now = datetime.now().isoformat()

    if not raw_limit:
        db.execute('DELETE FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name']))
        db.commit()
        bump_data_version('budget', {'category': cat['name'], 'user_id': user_id})
        msg = f'å·²å–æ¶ˆã€Œ{cat["name"]}ã€çš„æœˆåº¦é¢„ç®—'
    else:
        try:
            limit = float(raw_limit)
        except ValueError:
            limit = -1
        if limit <= 0:
            if is_ajax_request():
                return jsonify({'ok': False, 'message': 'é¢„ç®—é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—'}), 400
            flash('é¢„ç®—é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—', 'error')
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
        msg = f'å·²è®¾ç½®ã€Œ{cat["name"]}ã€çš„æœˆåº¦é¢„ç®—ä¸º RM{limit:.2f}'

    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg})
    flash(msg, 'success')
    return redirect(url_for('categories_page'))


# ---------------------------------------------------------------------------
# å›ºå®š / é‡å¤æ”¶æ”¯
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
            return jsonify({'ok': False, 'message': 'é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—'}), 400
        flash('é‡‘é¢å¿…é¡»æ˜¯å¤§äºŽ 0 çš„æ•°å­—', 'error')
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
        return jsonify({'ok': True, 'message': 'å›ºå®šæ”¶æ”¯è§„åˆ™å·²æ·»åŠ '})
    flash('å›ºå®šæ”¶æ”¯è§„åˆ™å·²æ·»åŠ ', 'success')
    return redirect(url_for('recurring_page'))


@app.route('/recurring/<int:rule_id>/delete', methods=['POST'])
def delete_recurring(rule_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute('DELETE FROM recurring_rules WHERE id = ? AND user_id = ?', (rule_id, user_id))
    db.commit()
    bump_data_version('recurring_delete', {'id': rule_id, 'user_id': user_id})
    if is_ajax_request():
        return jsonify({'ok': True, 'message': 'è§„åˆ™å·²åˆ é™¤', 'id': rule_id})
    flash('è§„åˆ™å·²åˆ é™¤', 'success')
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
        msg = 'è§„åˆ™å·²åœç”¨' if row['is_active'] else 'è§„åˆ™å·²å¯ç”¨'
        if is_ajax_request():
            return jsonify({'ok': True, 'message': msg, 'id': rule_id, 'is_active': new_active})
        flash(msg, 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'æœªæ‰¾åˆ°å¯¹åº”è§„åˆ™'}), 404
    return redirect(url_for('recurring_page'))


@app.route('/recurring/generate', methods=['POST'])
def manual_generate_recurring():
    user_id = get_current_user_id()
    count = generate_due_recurring(user_id)
    if count:
        bump_data_version('recurring_generate', {'count': count, 'user_id': user_id})
        msg = f'å·²ç”Ÿæˆ {count} æ¡æœ¬æœˆå›ºå®šæ”¶æ”¯è®°å½•'
    else:
        msg = 'æœ¬æœˆå›ºå®šæ”¶æ”¯å·²å…¨éƒ¨ç”Ÿæˆï¼Œæ— éœ€é‡å¤ç”Ÿæˆ'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'count': count})
    flash(msg, 'success')
    return redirect(url_for('recurring_page'))


# ---------------------------------------------------------------------------
# æ‰¹é‡å¯¼å…¥ (CSV / Excel)
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
        flash('è¯·é€‰æ‹©è¦å¯¼å…¥çš„æ–‡ä»¶', 'error')
        return redirect(url_for('import_page'))

    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in ('.csv', '.xlsx', '.xls'):
        flash('ä»…æ”¯æŒ .csv / .xlsx / .xls æ–‡ä»¶', 'error')
        return redirect(url_for('import_page'))

    token = uuid.uuid4().hex
    saved_path = os.path.join(UPLOAD_DIR, token + ext)
    file.save(saved_path)

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        os.remove(saved_path)
        flash(f'æ–‡ä»¶è¯»å–å¤±è´¥ï¼š{e}', 'error')
        return redirect(url_for('import_page'))

    if df.empty:
        os.remove(saved_path)
        flash('æ–‡ä»¶ä¸­æ²¡æœ‰æ•°æ®', 'error')
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
        flash('å¯¼å…¥ä¼šè¯å·²è¿‡æœŸï¼Œè¯·é‡æ–°ä¸Šä¼ æ–‡ä»¶', 'error')
        return redirect(url_for('import_page'))

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        flash(f'æ–‡ä»¶è¯»å–å¤±è´¥ï¼š{e}', 'error')
        return redirect(url_for('import_page'))

    date_col = f.get('date_col')
    amount_col = f.get('amount_col')
    type_mode = f.get('type_mode')  # sign / fixed_expense / fixed_income / column
    type_col = f.get('type_col')
    category_col = f.get('category_col')
    note_col = f.get('note_col')
    default_category = (f.get('default_category') or 'æœªåˆ†ç±»').strip()
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
                tx_type = 'income' if ('æ”¶å…¥' in raw_type or raw_type.lower() == 'income') else 'expense'
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

    flash(f'å¯¼å…¥å®Œæˆï¼šæˆåŠŸ {inserted} æ¡ï¼Œè·³è¿‡ {skipped} æ¡', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# å°ç¥¨è¯†åˆ«ä¸Žæ™ºèƒ½ AA åˆ†è´¦ (Split Bill)
# ---------------------------------------------------------------------------

def parse_receipt_text_to_items(raw_text):
    """
    å…¨çƒé€šç”¨å°ç¥¨è§£æžå¼•æ“Ž (Global Universal Receipt Engine)
    æ”¯æŒç¾Žæ¬§ã€ä¸­æ—¥éŸ©ã€ä¸œå—äºšç­‰å¤šå›½è´§å¸ç¬¦å·ã€å›½é™…æ•°å­—æ ¼å¼ã€å¤šè¯­ç§ç¨Žåˆ¶ä¸Žå°è´¹/æœåŠ¡è´¹ç»“æž„ã€‚
    """
    if not raw_text:
        return {'items': [], 'subtotal': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'total': 0.0, 'currency_symbol': 'RM'}

    # 1. è´§å¸ç¬¦å·è‡ªé€‚åº”æŽ¢æµ‹
    currency_symbol = 'RM'
    if re.search(r'\b(?:RM|MYR)\b', raw_text, re.IGNORECASE):
        currency_symbol = 'RM'
    elif re.search(r'(?:S\$|\bSGD\b|GST\s*REG)', raw_text, re.IGNORECASE):
        currency_symbol = 'S$'
    elif re.search(r'(?:â‚¬|\bEUR\b|TTC|TVA|HT\b)', raw_text):
        currency_symbol = 'â‚¬'
    elif re.search(r'(?:Â£|\bGBP\b)', raw_text):
        currency_symbol = 'Â£'
    elif re.search(r'(?:Â¥|å††|\bJPY\b|ãŠä¼šè¨ˆ|æ¶ˆè²»ç¨Ž)', raw_text):
        currency_symbol = 'Â¥'
    elif re.search(r'(?:â‚©|ì›|\bKRW\b|ê²°ì œ|ë¶€ê°€ì„¸)', raw_text):
        currency_symbol = 'â‚©'
    elif re.search(r'(?:à¸¿|\bTHB\b)', raw_text):
        currency_symbol = 'à¸¿'
    elif re.search(r'(?:Rp|\bIDR\b)', raw_text):
        currency_symbol = 'Rp'
    elif re.search(r'(?:â‚«|\bVND\b)', raw_text):
        currency_symbol = 'â‚«'
    elif re.search(r'(?:NT\$|\bTWD\b)', raw_text):
        currency_symbol = 'NT$'
    elif re.search(r'(?:HK\$|\bHKD\b)', raw_text):
        currency_symbol = 'HK$'
    elif re.search(r'\$', raw_text):
        currency_symbol = '$'
    elif re.search(r'[\u4e00-\u9fa5]', raw_text):
        currency_symbol = 'Â¥' if ('Â¥' in raw_text or 'å…ƒ' in raw_text or 'å¾®ä¿¡' in raw_text or 'æ”¯ä»˜å®' in raw_text) else 'RM'

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

    # å¸¸è§æ”¯ä»˜ä¸Žç»“ç®—æ–¹å¼ï¼ˆå¿…é¡»æŽ’é™¤ï¼Œä¸èƒ½å½“ä½œæ¶ˆè´¹èœå“ï¼‰
    exclude_payment_patterns = [
        r'\b(?:cash|change|change\s*due|tendered|due)\b',
        r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|nets|eftpos)\b',
        r'\b(?:tng|touch\s*[\'â€™]?n\s*go|grabpay|boost|alipay|wechat|duit\s*now|duitnow|paypay|line\s*pay|kakaopay|promptpay)\b',
        r'\b(?:carte\s*bancaire|rendu|barzahlung|kartenzahlung|r[uÃ¼]ckgeld|efectivo|cambio|contanti|resto)\b',
        r'(?:çŽ°é‡‘|æ‰¾é›¶|å®žæ”¶|æ‰¾å›ž|å¾®ä¿¡æ”¯ä»˜|æ”¯ä»˜å®|æ‰«ç æ”¯ä»˜|åˆ·å¡|ãŠé‡£ã‚Š|é ã‚Š|ã‚¯ãƒ¬ã‚¸ãƒƒãƒˆ|é›»å­ãƒžãƒãƒ¼|æ±ºæ¸ˆ|í˜„ê¸ˆ|ê±°ìŠ¤ë¦„ëˆ|ì‹ ìš©ì¹´ë“œ|tunai|baki|kembalian)'
    ]

    # ç¥¨å¤´ã€åœ°å€ã€é‚®ç¼–ã€æµæ°´å·ã€æ¡Œå·ã€é—®å€™è¯­ç­‰éžå•†å“è¡Œ
    exclude_header_noise = [
        r'\b(?:invoice|receipt|bill\s*no|table|date|time|tel|phone|drawer|reg|cashier|server|chk|check\s*closed)\b',
        r'\b(?:terminal|merchant|auth|approval|ref|pax|order|order\s*#|siret|gst\s*reg|gst\s*no|co\s*no)\b',
        r'\b(?:items?\s*count|item\s*count|total\s*qty|qty\s*total|qty\s*item|price\s*\(myr\))\b',
        r'\b(?:thank\s*you|please\s*come|merci|danke|terima\s*kasih|arigato|grazia)\b',
        r'(?:å•å·|å°å·|å®¢æ•°|æ”¶é“¶å‘˜|æ—¶é—´|å“å|æ•°é‡|é‡‘é¢|è°¢è°¢æƒ é¡¾|æ¬¢è¿Žå†æ¬¡å…‰ä¸´|æ¯Žåº¦ã‚ã‚ŠãŒã¨ã†ã”ã–ã„ã¾ã™|ã¾ãŸã®ãŠè¶Šã—ã‚’|ê°ì‚¬í•©ë‹ˆë‹¤|ãƒ†ãƒ¼ãƒ–ãƒ«|äººæ•°|ãƒ¬ã‚¸|ãƒ¬ã‚·ãƒ¼ãƒˆ)',
        r'^[x*\-_=+#\s\d|.:/]+$',
        r'\b[x*]{4,}\b'
    ]

    # åœ°å€ä¸Žé‚®ç¼–ç‰¹å¾ (é˜²æ­¢å°† New York NY 10010 æˆ– Singapore 329801 è¯¯è®¤ä¸ºå•†å“)
    address_keywords = [
        'road', 'street', 'avenue', 'boulevard', 'jalan', 'lorong', 'lane', 'park', 'block',
        'blvd', 'ave', 'st.', 'rd.', 'singapore', 'new york', 'penang', 'paris', 'tokyo',
        'åŒº', 'è·¯', 'è¡—', 'å·', 'å··', 'é“', 'å¸‚', 'çœ'
    ]

    def normalize_numbers(raw_str):
        s = raw_str
        # æ¬§æ´²é€—å·å°æ•°ï¼š6,40 â‚¬ æˆ– 23,55 -> 6.40, 23.55
        s = re.sub(r'(\d+),(\d{2})(?:\s*(?:â‚¬|EUR|\b))', r'\1.\2', s)
        # å¸¸è§åƒåˆ†ä½ï¼š1,480 æˆ– 1,480.00 -> 1480 æˆ– 1480.00
        s = re.sub(r'(\d+),(\d{3})\b', r'\1\2', s)
        # å¼‚å¸¸ç©ºæ ¼å°æ•°ï¼š4 .95 -> 4.95
        s = re.sub(r'(\d+)\s*\.\s*(\d+)', r'\1.\2', s)
        # ä¿®æ­£çƒ­æ•çº¸è¯¯è¯†åˆ« Â¥ å­—æ¯ (å¦‚ Â¥t -> Vt)
        s = re.sub(r'Â¥([A-Za-z])', r'V\1', s)
        return s

    for line in lines:
        clean_line = normalize_numbers(line)
        lower = clean_line.lower()

        # 1. åŒ¹é…å°è´¹ä¸ŽæœåŠ¡è´¹ (Tip, Gratuity, Service Charge, Svc Chg, SC, æœåŠ¡è´¹, å¸­æ–™)
        is_sc_line = (any((re.search(r'\b' + re.escape(k) + r'\b', lower) is not None) if len(k) <= 3 else (k in lower) for k in ['tip', 'gratuity', 'pourboire', 'trinkgeld', 'service charge', 'svc charge', 'svc chg', 'service fee', 'sc']) or
                      any(k in clean_line for k in ['æœåŠ¡è´¹', 'æœå‹™è²»', 'ãŠé€šã—', 'å¸­æ–™', 'ë´‰ì‚¬ë£Œ']))
        if is_sc_line and not any(k in clean_line for k in ['èŒ¶ä½', 'è°ƒæ–™']):
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                service_charge = float(amounts[-1])
            continue

        # 2. åŒ¹é…æ”¿åºœç¨Ž / å¢žå€¼ç¨Ž / VAT / GST / SST / TVA / MwSt / IVA / æ¶ˆè´¹ç¨Ž / ë¶€ê°€ì„¸
        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'sales tax', 'vat', 'tva', 'mwst', 'ust', 'iva', 'tax']) or any(k in clean_line for k in ['æ¶ˆè´¹ç¨Ž', 'æ¶ˆè²»ç¨Ž', 'å¢žå€¼ç¨Ž', 'ç¨Žè´¹', 'ç¨Žé¢', 'å†…ç¨Ž', 'å¤–ç¨Ž', 'ë¶€ê°€ì„¸']):
            if 'total' not in lower and 'subtotal' not in lower and 'åˆè®¡' not in clean_line and 'å°è®¡' not in clean_line and 'å°è¨ˆ' not in clean_line:
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    tax = float(amounts[-1])
                continue

        # 3. åŒ¹é…æŠ¹é›¶ä¸Žèˆå…¥ (Rounding, Bill Rounding, Rnd, æŠ¹é›¶)
        if any(k in lower for k in ['rounding', 'bill rounding', 'round adj', 'rnd']) or 'æŠ¹é›¶' in clean_line or 'èˆå…¥' in clean_line:
            m_rnd = re.search(r'([-+]?\s*[0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if m_rnd:
                rnd_val = float(m_rnd.group(1).replace(' ', ''))
                # æŠ¹é›¶é‡‘é¢æœ¬è´¨ä¸Šåªä¼šæ˜¯å¾ˆå°çš„è°ƒæ•´ï¼ˆé€šå¸¸åœ¨ -1 åˆ° +1 ä¹‹é—´ï¼‰ï¼Œå¦‚æžœ OCR æŠŠè¿™è¡Œ
                # è®¤é”™æˆä¸€ä¸ªç¦»è°±çš„å¤§æ•°å­—ï¼ˆæ¯”å¦‚æŠŠ "0.00" è®¤æˆ "8"ï¼‰ï¼Œä¸Žå…¶ç…§å•å…¨æ”¶ä¸€ä¸ªæ˜Žæ˜¾ä¸
                # åˆç†çš„æŠ¹é›¶é‡‘é¢ï¼Œä¸å¦‚ç›´æŽ¥å½“ä½œæ²¡è®¤å‡ºæ¥ï¼Œç»´æŒé»˜è®¤çš„ 0.00ã€‚
                if abs(rnd_val) <= 1.0:
                    rounding = rnd_val
            continue

        # 4. åŒ¹é…ä¼˜æƒ ä¸ŽæŠ˜æ‰£ (Discount, Promo, Voucher, Rebate, ä¼˜æƒ , æŠ˜æ‰£, æ»¡å‡, å‰²å¼•, í• ì¸)
        if any(k in lower for k in ['discount', 'promo', 'voucher', 'rebate', 'remise', 'rabatt', 'descuento']) or any(k in clean_line for k in ['ä¼˜æƒ ', 'æŠ˜æ‰£', 'æ»¡å‡', 'æŠµæ‰£', 'å‰²å¼•', 'å€¤å¼•', 'í• ì¸']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                discount = float(amounts[-1])
            continue

        # 5. åŒ¹é…å°è®¡ Subtotal / Total HT / Zwischensumme / å°è®¡ / å°è¨ˆ / æ¶ˆè´¹å°è®¡
        if any(k in lower for k in ['subtotal', 'sub-total', 'total ht', 'zwischensumme', 'sous-total', 'net amount']) or any(k in clean_line for k in ['å°è®¡', 'å°è¨ˆ', 'æ¶ˆè´¹å°è®¡', 'å° è¨ˆ']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                subtotal = float(amounts[-1])
            continue

        # 6. åŒ¹é…æ€»é‡‘é¢ Total / Grand Total / Total TTC / Gesamtbetrag / åˆè®¡ / æ€»è®¡ / å®žä»˜ / ãŠä¼šè¨ˆ / åˆè¨ˆé‡‘é¡ / ê²°ì œê¸ˆì•¡ / Jumlah
        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'amount due', 'total payable', 'amount payable', 'total ttc', 'gesamtbetrag', 'endbetrag', 'importe total', 'totale', 'total', 'jumlah']) or any(k in clean_line for k in ['åˆè®¡', 'æ€»è®¡', 'å®žä»˜', 'å®žæ”¶', 'åº”æ”¶', 'ç»“ç®—', 'ãŠä¼šè¨ˆ', 'åˆè¨ˆé‡‘é¡', 'åˆè¨ˆ', 'í•©ê³„', 'ê²°ì œê¸ˆì•¡', 'ì´ê¸ˆì•¡']):
            if 'total ht' not in lower and 'subtotal' not in lower and 'æ¶ˆè´¹å°è®¡' not in clean_line:
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    total = float(amounts[-1])
                continue

        # 7. æŽ’é™¤æ”¯ä»˜æ–¹å¼è¡Œä¸Žå™ªå£°è¡Œ
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_payment_patterns):
            continue
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_header_noise):
            continue

        # 8. æŽ’é™¤åœ°å€è¡Œä¸­çš„é‚®ç¼–è¯†åˆ« (å¦‚ NY 10010, Singapore 329801)
        if any(kw in lower for kw in address_keywords):
            if re.search(r'\b\d{4,6}\b\s*$', clean_line):
                continue

        # 8.5 æŽ’é™¤"å•ä»·/ä»½"æ ‡æ³¨çš„å»¶ç»­è¡Œ (å¦‚ "(Takeaway) (14.90/ea)")
        # æœ‰äº›æ”¶é“¶ç³»ç»Ÿ (å¦‚ FEEDME SMART POS) ä¼šæŠŠå“é¡¹æ‹†æˆå¥½å‡ ä¸ªåŽŸå§‹è¡Œè¾“å‡ºï¼šç¬¬ä¸€è¡Œæ˜¯
        # "æ•°é‡ å“å ... ä»·æ ¼"ï¼ŒæŽ¥ä¸‹æ¥è¿˜ä¼šæœ‰ä¸€è¡Œä¸“é—¨é‡å¤æ ‡æ³¨ "(å•ä»·/ea)"ã€‚è¿™ç§å»¶ç»­è¡Œæœ¬èº«
        # ä¸æ˜¯æ–°çš„å“é¡¹ï¼Œåªæ˜¯æŠŠå·²ç»åœ¨ç¬¬ä¸€è¡ŒæŠ“åˆ°çš„ä»·æ ¼å†è®²ä¸€æ¬¡ï¼Œå¦‚æžœä¸æŽ’é™¤æŽ‰ï¼Œä¼šè¢«è¯¯åˆ¤æˆ
        # ä¸€ç¬”æ–°çš„ã€å“åä¹±ä¸ƒå…«ç³Ÿçš„å“é¡¹ (æ¯”å¦‚æŠŠ "(Takeaway)" è¿™å‡ ä¸ªå­—å½“æˆå“å)ã€‚
        if re.search(r'[0-9]+\.?[0-9]*\s*/\s*ea\b', lower):
            continue

        # 9. æå–å¸¸è§„å•å“è¡Œï¼šæ‰¾è¿™ä¸€è¡Œé‡Œ"æœ€åŽä¸€ä¸ªé•¿å¾—åƒä»·æ ¼çš„æ•°å­—"ï¼Œä»·æ ¼å‰é¢å½“å“åï¼Œ
        # ä»·æ ¼åŽé¢ä¸ç®¡æ˜¯ä»€ä¹ˆå†…å®¹ï¼ˆè¡Œå°¾å¸¸è§çš„ OCR ä¹±ç ç¬¦å·ã€å•ä½æ ‡æ³¨ã€å¤šä½™ç©ºç™½ç­‰ï¼‰ä¸€å¾‹ä¸¢å¼ƒï¼Œ
        # ä¸è¦æ±‚è¡Œå°¾å¿…é¡»ç²¾ç¡®ç¬¦åˆæŸä¸ªå…è®¸å­—ç¬¦çš„ç™½åå•â€”â€”çœŸå®žæ‹ç…§è¯†åˆ«å‡ºæ¥çš„æ–‡å­—ï¼Œè¡Œå°¾å¸¸å¸¸ä¼š
        # å¸¦ä¸€ä¸¤ä¸ªæ‚è®¯ç¬¦å·ï¼ˆæ¯”å¦‚å…¨å½¢é€—å·ã€ç«–çº¿ï¼‰ï¼Œåªè¦æ±‚"ç²¾ç¡®åŒ¹é…åˆ°è¡Œå°¾"å¾ˆå®¹æ˜“è¢«è¿™ç±»æ‚è®¯æ‹–ç´¯
        # åˆ°æ•´è¡Œéƒ½æŠ“ä¸åˆ°ï¼Œè¿™é‡Œæ”¹æˆåªæ‰¾ä»·æ ¼æœ¬èº«ï¼Œä»·æ ¼åŽé¢çš„ä¸œè¥¿ç›´æŽ¥å¿½ç•¥ã€‚
        price_pattern = re.compile(
            r'(?:RM|MYR|\$|S\$|â‚¬|EUR|Â£|GBP|Â¥|å††|â‚©|ì›|à¸¿|Rp|â‚«)\s*[0-9]+(?:\.[0-9]{1,2})?'
            r'|(?<![0-9.])[0-9]+\.[0-9]{1,2}(?![0-9])',
            re.IGNORECASE
        )
        price_matches = list(price_pattern.finditer(clean_line))
        m_item = price_matches[-1] if price_matches else None
        if m_item:
            name_raw = clean_line[:m_item.start()].strip(' -:\t#$*Â¥â‚¬Â£â€œ"\'|.,;ï¼Œã€')
            name_raw = re.sub(r'^(?:RM|MYR|\$|S\$|â‚¬|Â£|Â¥|å††|â‚©)\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'\s*(?:RM|MYR|\$|S\$|â‚¬|Â£|Â¥|å††|â‚©)\s*$', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[ï¼ˆ(]?(?:Takeaway|TA|Dine[- ]in)[)ï¼‰]?\s*(?:\([0-9.]+/ea\))?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[ï¼ˆ(]?[0-9.]+/ea[)ï¼‰]?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = name_raw.strip(' -:\t#$*Â¥â€œ"\'|.,;ï¼Œã€')

            num_match = re.search(r'[0-9]+(?:\.[0-9]{1,2})?', m_item.group())
            price_val = float(num_match.group()) if num_match else 0.0

            # è¿‡æ»¤éžå•†å“çš„é‚®ç¼–æˆ–è¿‡å¤§éžå•å“æ•°å­— (éž JPY/KRW/VND/IDR å¸ç§æ—¶ï¼Œå•å“ä»·æ ¼é€šå¸¸ä¸ä¼šè¶…è¿‡ 5000)
            if currency_symbol in ['$', 'â‚¬', 'Â£', 'RM', 'S$'] and price_val > 5000:
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

                # å¸¸è§"æ•°é‡ + å“å + å•ä»· + å°è®¡"åŒä¸€è¡Œçš„æŽ’ç‰ˆï¼ˆå¦‚ "2 Roti Canai 1.10 2.20"ï¼‰ï¼Œ
                # ä¸Šé¢å·²ç»æŠŠæœ€åŽä¸€ä¸ªæ•°å­—(å°è®¡)æŠ“æˆ priceï¼Œä½†å•ä»·å¯èƒ½è¿˜æ®‹ç•™åœ¨ name_raw å°¾éƒ¨ï¼Œ
                # ä¾‹å¦‚ name_raw ä¼šå˜æˆ "Roti Canai 1.10"ã€‚è¿™é‡ŒæŠŠè¿™ç§æ®‹ç•™çš„å•ä»·æ•°å­—åŽ»æŽ‰ï¼Œ
                # ä¸ç„¶å“åä¼šè¢«è¯¯é»ä¸Šä¸€ä¸ªä»·é’±ã€‚
                name_raw = re.sub(
                    r'\s+(?:RM|MYR|\$|S\$|â‚¬|Â£|Â¥|å††|â‚©)?\s*[0-9]+\.[0-9]{2}\s*$',
                    '',
                    name_raw,
                    flags=re.IGNORECASE
                ).strip(' -:\t#$*')

                items.append({
                    'name': name_raw,
                    'price': price_val,
                    'quantity': qty
                })

    # è‹¥æœªæ‰¾åˆ° subtotalï¼Œåˆ™ä»Ž items æ±‚å’Œ
    calc_subtotal = sum(i['price'] for i in items)
    if subtotal == 0:
        subtotal = round(calc_subtotal, 2)

    # æ¯”ä¾‹æ¢ç®—
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
    """å°ç¥¨æ‹ç…§ AA åˆ†è´¦é¡µé¢"""
    return render_template('split_bill.html', today=date.today().isoformat())


@app.route('/split-bill/parse-text', methods=['POST'])
@csrf.exempt
def split_bill_parse_text():
    """è§£æžå°ç¥¨æ–‡æœ¬æˆ–ç²˜è´´å†…å®¹"""
    text = request.form.get('text', '').strip()
    if not text:
        return jsonify({'ok': False, 'message': 'æœªæä¾›å°ç¥¨å†…å®¹'}), 400

    parsed = parse_receipt_text_to_items(text)
    return jsonify({'ok': True, 'data': parsed})


_rapid_ocr_engine = None

def get_rapid_ocr():
    global _rapid_ocr_engine
    if _rapid_ocr_engine is None:
        try:
            from rapidocr_onnxruntime import RapidOCR
            _rapid_ocr_engine = RapidOCR()
            app.logger.info("RapidOCR engine initialized successfully")
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
    """åˆ†æž OCR è¯†åˆ«æ¡†çš„é•¿å®½æ¯”ä¸Žåº•éƒ¨ç»“ç®—å…³é”®è¯ä½ç½®ï¼Œè¯„ä¼°å½“å‰å›¾ç‰‡çš„æœå‘æ˜¯å¦ä¸ºæ­£ç«‹"""
    if not ocr_res:
        return {'horiz': 0, 'vert': 0, 'footer_bottom': 0, 'footer_top': 0, 'count': 0}
    horiz = 0
    vert = 0
    footer_bottom = 0
    footer_top = 0
    footer_keywords = [
        'total', 'subtotal', 'sub-total', 'grand total', 'net total', 'change', 'rounding',
        'duitnow', 'cash', 'card', 'visa', 'mastercard', 'thank', 'scan', 'pos', 'powered',
        'feedme', 'tax', 'service', 'balance', 'åˆè®¡', 'æ€»è®¡', 'å°è®¡', 'å®žæ”¶', 'æ‰¾é›¶', 'è°¢è°¢',
        'ãŠä¼šè¨ˆ', 'åˆè¨ˆ', 'í•©ê³„'
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


def smart_orient_receipt_ocr(pil_img, engine):
    """
    æ™ºèƒ½è‡ªåŠ¨æ–¹å‘æ ¡æ­£ OCRï¼š
    åº”å¯¹ç”¨æˆ·æ¨ªæ‹ã€ä¾§å‘ï¼ˆ90Â°/270Â°ï¼‰æˆ–é¢ å€’ï¼ˆ180Â°ï¼‰ä¸Šä¼ çš„å„ç±»å°ç¥¨ï¼ˆåŒ…æ‹¬æ—  EXIF ä¿¡æ¯çš„ WhatsApp åŽ‹ç¼©å›¾ï¼‰ï¼Œ
    é€šè¿‡æ–‡å­—æ¡†æ¨ªçºµå‡ ä½•æ¯”çŽ‡ä¸Žå°ç¥¨åº•éƒ¨ç»“ç®—å…³é”®è¯åŠ æƒè¯„åˆ†ï¼Œè‡ªåŠ¨çº æ­£è‡³æ­£ç«‹æ–¹å‘åŽå†è¿›è¡Œæ–‡æœ¬è¡Œèšç±»å’Œè¯­ä¹‰è§£æžã€‚
    """
    import numpy as np

    # 1. åˆå§‹è§’åº¦ (0Â°) æµ‹è¯•è¯†åˆ«
    res0, _ = engine(np.array(pil_img))
    if not res0:
        return res0, "", {'items': [], 'subtotal': 0.0, 'total': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'currency_symbol': '$'}, 0

    stats0 = get_ocr_orientation_stats(res0, pil_img.height)
    raw_text0 = cluster_ocr_blocks_to_lines(res0)
    parsed0 = parse_receipt_text_to_items(raw_text0)

    # å¿«é€Ÿç›´å‡ºæ¡ä»¶ï¼šå¦‚æžœæ¨ªå‘æ–‡å­—æ¡†è¿œå¤šäºŽçºµå‘æ¡†ï¼Œä¸”åº•éƒ¨å…³é”®è¯ä½äºŽä¸‹åŠéƒ¨åˆ†æˆ–å·²æˆåŠŸè§£æžå‡ºå¤šä¸ªå•†å“
    if stats0['horiz'] > max(5, stats0['vert'] * 1.5) and (stats0['footer_bottom'] >= stats0['footer_top'] or len(parsed0['items']) > 0):
        return res0, raw_text0, parsed0, 0

    # å€™é€‰è§’åº¦åˆ¤æ–­ï¼š
    # è‹¥çºµå‘æ–‡å­—æ¡†å ä¼˜ï¼Œè¯´æ˜Žç”¨æˆ·ä¾§å‘æ‰‹æœºæ‹ç…§ï¼ˆ90Â° æˆ– 270Â°ï¼‰
    # è‹¥æ¨ªå‘å¤šä½†åº•éƒ¨å…³é”®è¯åœ¨ä¸ŠåŠéƒ¨åˆ†ï¼Œè¯´æ˜Žç”¨æˆ·æŠŠå°ç¥¨å€’è¿‡æ¥æ‹äº†ï¼ˆ180Â°ï¼‰
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

    candidates = []
    for angle in test_angles:
        rot_img = pil_img.rotate(angle, expand=True)
        r_res, _ = engine(np.array(rot_img))
        if not r_res:
            continue
        r_stats = get_ocr_orientation_stats(r_res, rot_img.height)
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
            app.logger.info("Auto-corrected receipt orientation by %dÂ° (score %d vs original %d)", best[1], best[0], score0)
            return best[2], best[3], best[4], best[1]

    return res0, raw_text0, parsed0, 0


@app.route('/split-bill/ocr-upload', methods=['POST'])
@csrf.exempt
def split_bill_ocr_upload():
    """æœ¬åœ° RapidOCR æ·±åº¦å­¦ä¹ å°ç¥¨è¯†åˆ«æŽ¥å£ï¼ˆé›¶äº‘ç«¯ä¾èµ–ï¼Œæ”¯æŒå…¨æ–¹å‘è‡ªé€‚åº”çº åä¸ŽåŒè¡Œå¯¹é½ï¼‰"""
    file = request.files.get('file') or request.files.get('receipt_image')
    if not file or not file.filename:
        return jsonify({'ok': False, 'message': 'æœªæ£€æµ‹åˆ°ä¸Šä¼ çš„å°ç¥¨ç…§ç‰‡'}), 400

    engine = get_rapid_ocr()
    if not engine:
        return jsonify({'ok': False, 'message': 'æœ¬åœ° RapidOCR å¼•æ“Žæœªå®‰è£…æˆ–åˆå§‹åŒ–å¤±è´¥'}), 500

    try:
        img_bytes = file.read()
        import io
        from PIL import Image, ImageOps
        pil_img = Image.open(io.BytesIO(img_bytes))
        # ä¼˜å…ˆè¯»å– EXIF æ ‡ç­¾çº æ­£æ—‹è½¬
        try:
            pil_img = ImageOps.exif_transpose(pil_img)
        except Exception:
            pass
        if pil_img.mode != 'RGB':
            pil_img = pil_img.convert('RGB')

        # æ ¸å¿ƒï¼šå³ä½¿æ—  EXIF æ ‡ç­¾ï¼ˆå¦‚ WhatsApp åŽ‹ç¼©å›¾ï¼‰ï¼Œä¹Ÿèƒ½ä¾æ®æ–‡å­—æ¡†å‡ ä½•ä¸Žå°ç¥¨å¸ƒå±€è‡ªåŠ¨æ—‹è½¬çº æ­£
        result, raw_text, parsed, rot = smart_orient_receipt_ocr(pil_img, engine)
        if not result or not raw_text:
            return jsonify({'ok': False, 'message': 'æœªèƒ½è¯†åˆ«å‡ºæ–‡å­—ï¼Œè¯·ç¡®ä¿å°ç¥¨æ¸…æ™°å¹³æ•´'}), 200

        return jsonify({'ok': True, 'data': parsed, 'raw_text': raw_text, 'rotation_applied': rot})
    except Exception as e:
        app.logger.error("RapidOCR recognition failed: %s", e)
        return jsonify({'ok': False, 'message': f'å°ç¥¨è¯†åˆ«å¤±è´¥: {str(e)}'}), 500


@app.route('/split-bill/save-record', methods=['POST'])
def split_bill_save_record():
    """å°† AA åˆ†è´¦ä¸­å±žäºŽè‡ªå·±çš„éƒ¨åˆ†ä¸€é”®å­˜å…¥ä¸»è´¦æœ¬"""
    f = request.form
    try:
        amount = float(f.get('amount', 0))
    except ValueError:
        amount = 0

    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': 'è®°è´¦é‡‘é¢å¿…é¡»å¤§äºŽ 0'}), 400
        flash('è®°è´¦é‡‘é¢å¿…é¡»å¤§äºŽ 0', 'error')
        return redirect(url_for('split_bill_page'))

    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()
    note = f.get('note', '').strip() or 'èšé¤ AA åˆ†æ‘Šæ¶ˆè´¹'
    tx_date = f.get('date') or date.today().isoformat()
    category = f.get('category') or 'é¤é¥®'

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

    msg = f'å·²æˆåŠŸè®°å…¥æ”¯å‡ºï¼š{note} {money_filter(amount)}'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'budget_alert': budget_alert})
    flash(msg, 'success')
    return redirect(url_for('records'))


# ============================================================
# è´Ÿå€ºã€ä¿¡ç”¨å¡ä¸Žåˆ†æœŸä»˜æ¬¾è¿½è¸ªè·¯ç”± (Liabilities & Installment Tracker)
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

    # 1. èŽ·å–å½“æœˆåˆšæ€§è¿˜æ¬¾çŽ°é‡‘æµæŽ’ç¨‹äº‹ä»¶
    cashflow_data = get_monthly_cashflow_events(db, user_id, t_year, t_month)

    # 2. èŽ·å–åˆ†æœŸä»˜æ¬¾åˆ—è¡¨
    installments = db.execute('''
        SELECT i.*, a.name as card_name, a.due_day as card_due_day
        FROM installments i
        LEFT JOIN accounts a ON i.account_id = a.id
        WHERE i.user_id = ?
        ORDER BY CASE WHEN i.status='active' THEN 0 ELSE 1 END, i.id DESC
    ''', (user_id,)).fetchall()

    # è®¡ç®—å„åˆ†æœŸå®žæ—¶å‰©ä½™æœ¬é‡‘ä¸Žè¿›åº¦
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
        
        # é¢„ä¼°ç»“æ¸…å¹´æœˆ
        try:
            f_dt = datetime.strptime(item.get('first_due_date'), "%Y-%m-%d").date()
            from liabilities_tracker import add_months_clamped
            settle_dt = add_months_clamped(f_dt, tenure - 1)
            item['settle_month'] = settle_dt.strftime('%Y-%m')
        except Exception:
            item['settle_month'] = 'â€”'

        item['remaining_periods'] = rem_periods
        item['remaining_balance'] = rem_bal
        item['progress_pct'] = min(100, int((paid / tenure) * 100)) if tenure > 0 else 100
        if item.get('status') == 'active':
            total_inst_debt += rem_bal
        inst_list.append(item)

    # 3. èŽ·å–å›ºå®šè´·æ¬¾åˆ—è¡¨
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

    # 4. èŽ·å–é“¶è¡Œå¡ä¸Žä¿¡ç”¨å¡åˆ—è¡¨
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
        flash('è¯·è¾“å…¥åˆ†æœŸé¡¹ç›®åç§°', 'error')
        return redirect(url_for('liabilities_page'))
    
    try:
        total_amount = float(request.form.get('total_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
    except (ValueError, TypeError):
        flash('åˆ†æœŸé‡‘é¢æˆ–æœŸæ•°æ ¼å¼ä¸æ­£ç¡®', 'error')
        return redirect(url_for('liabilities_page'))

    first_due_date = request.form.get('first_due_date') or date.today().isoformat()
    account_id = request.form.get('account_id') or None
    note = (request.form.get('note') or '').strip()

    # è®¡ç®—æ¯æœˆæ ‡å‡†æ‘Šé”€
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
    flash(f'å·²æˆåŠŸæ·»åŠ å…æ¯åˆ†æœŸé¡¹ç›®ï¼š{title}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/loan', methods=['POST'])
def api_add_loan():
    user_id = get_current_user_id()
    db = get_db()
    
    title = (request.form.get('title') or '').strip()
    if not title:
        flash('è¯·è¾“å…¥è´·æ¬¾é¡¹ç›®åç§°', 'error')
        return redirect(url_for('liabilities_page'))
    
    try:
        loan_amount = float(request.form.get('loan_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
        interest_rate = float(request.form.get('annual_interest_rate', 0.0))
        due_day = int(request.form.get('due_day', 5))
    except (ValueError, TypeError):
        flash('è´·æ¬¾é‡‘é¢ã€åˆ©çŽ‡æˆ–æœŸæ•°æ ¼å¼ä¸æ­£ç¡®', 'error')
        return redirect(url_for('liabilities_page'))

    method = request.form.get('method') or 'reducing_balance'
    debit_account_id = request.form.get('debit_account_id') or None
    start_date = request.form.get('start_date') or date.today().isoformat()
    note = (request.form.get('note') or '').strip()

    # è‹¥ç”¨æˆ·æ‰‹åŠ¨è¾“å…¥äº†æœˆä¾›é‡‘é¢ï¼Œåˆ™ä¼˜å…ˆä½¿ç”¨ï¼›å¦åˆ™è‡ªåŠ¨ç”¨è´¢åŠ¡ç®—æ³•æŽ¨ç®—
    custom_monthly = request.form.get('monthly_payment')
    try:
        if custom_monthly and float(custom_monthly) > 0:
            monthly_payment = float(custom_monthly)
            # ç®€å•å‰©ä½™æœ¬é‡‘é¢„ä¼°
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
    flash(f'å·²æˆåŠŸæ·»åŠ è´·æ¬¾è®°å½•ï¼š{title}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/account', methods=['POST'])
def api_add_account():
    user_id = get_current_user_id()
    db = get_db()
    
    name = (request.form.get('name') or '').strip()
    acc_type = request.form.get('type') or 'credit_card'
    if not name:
        flash('è¯·è¾“å…¥è´¦æˆ·/å¡ç‰‡åç§°', 'error')
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
    flash(f'å·²æˆåŠŸæ·»åŠ å¡ç‰‡/è´¦æˆ·ï¼š{name}', 'success')
    return redirect(url_for('liabilities_page'))


@app.route('/api/liabilities/sync', methods=['POST'])
def api_sync_liabilities():
    user_id = get_current_user_id()
    db = get_db()
    today_str = date.today().isoformat()
    res = sync_installments_to_monthly_statement(db, today_str)
    msg = f"åŒæ­¥å®Œæˆï¼šå·²è‡ªåŠ¨æŒ‚è´¦ {res['syncedRecords']} ç¬”æµæ°´ï¼Œå·²ç»“æ¸…å½’æ¡£ {res['completedInstallments']} ç¬”åˆ†æœŸã€‚"
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
    flash('å·²åˆ é™¤è¯¥è´Ÿå€ºè®°å½•', 'success')
    return redirect(url_for('liabilities_page'))


# ---------------------------------------------------------------------------
# è´¦æˆ·ç®¡ç† (Account Management) - é“¶è¡Œè´¦æˆ·ä¸Žä¿¡ç”¨å¡
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
        flash('è´¦æˆ·åç§°ä¸èƒ½ä¸ºç©º', 'error')
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
    flash(f'è´¦æˆ·ã€Œ{name}ã€å·²æˆåŠŸæ·»åŠ ', 'success')
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
        flash('è´¦æˆ·åç§°ä¸èƒ½ä¸ºç©º', 'error')
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
    flash(f'è´¦æˆ·ã€Œ{name}ã€å·²æ›´æ–°', 'success')
    return redirect(url_for('accounts_page'))


@app.route('/api/accounts/<int:acc_id>/toggle', methods=['POST'])
def manage_toggle_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT is_active FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash('è´¦æˆ·ä¸å­˜åœ¨', 'error')
        return redirect(url_for('accounts_page'))
    new_state = 0 if row['is_active'] else 1
    db.execute('UPDATE accounts SET is_active=? WHERE id=? AND user_id=?', (new_state, acc_id, user_id))
    db.commit()
    flash('è´¦æˆ·çŠ¶æ€å·²æ›´æ–°', 'success')
    return redirect(url_for('accounts_page'))


@app.route('/api/accounts/<int:acc_id>/delete', methods=['POST'])
def manage_delete_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT name FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash('è´¦æˆ·ä¸å­˜åœ¨', 'error')
        return redirect(url_for('accounts_page'))
    # Unlink subscriptions and installments before deletion
    db.execute('UPDATE subscriptions SET payment_method_id=NULL WHERE payment_method_id=? AND user_id=?', (acc_id, user_id))
    db.execute('UPDATE installments SET account_id=NULL WHERE account_id=? AND user_id=?', (acc_id, user_id))
    db.execute('DELETE FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id))
    db.commit()
    flash(f'è´¦æˆ·ã€Œ{row["name"]}ã€å·²åˆ é™¤', 'success')
    return redirect(url_for('accounts_page'))


# ---------------------------------------------------------------------------
# è®¢é˜…æœåŠ¡å¤§åŽ…ä¸Žç»­è´¹æé†’ (Subscription Management & Renewal Alerts)
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

    # ç»Ÿè®¡åˆ†ç±»æœˆå‡ç­‰æ•ˆåˆ†å¸ƒ
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
            cat = s.category or "å…¶ä»–"
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
    category = (request.form.get('category') or 'æµåª’ä½“').strip()
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
        flash('è¯·è¾“å…¥è®¢é˜…æœåŠ¡åç§°', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
        if cost <= 0:
            raise ValueError()
    except ValueError:
        flash('è¯·è¾“å…¥æœ‰æ•ˆçš„æ‰£è´¹é‡‘é¢', 'error')
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
    flash(f'æˆåŠŸæ·»åŠ è®¢é˜…æœåŠ¡ï¼š{name}', 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/edit', methods=['POST'])
def api_edit_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()

    name = (request.form.get('name') or '').strip()
    category = (request.form.get('category') or 'æµåª’ä½“').strip()
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
        flash('è®¢é˜…åç§°ä¸èƒ½ä¸ºç©º', 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
    except ValueError:
        flash('é‡‘é¢æ ¼å¼ä¸æ­£ç¡®', 'error')
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
    flash(f'å·²æ›´æ–°è®¢é˜…æœåŠ¡ï¼š{name}', 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/toggle-status', methods=['POST'])
def api_toggle_subscription_status(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT status, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('æ‰¾ä¸åˆ°æŒ‡å®šè®¢é˜…è®°å½•', 'error')
        return redirect(url_for('subscriptions_page'))

    new_status = 'PAUSED' if row['status'] == 'ACTIVE' else 'ACTIVE'
    db.execute("UPDATE subscriptions SET status = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_status, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_status', user_id=user_id)
    state_desc = 'å·²æ¢å¤æ´»è·ƒè®¡è´¹' if new_status == 'ACTIVE' else 'å·²æš‚åœæ‰£æ¬¾ç›‘æŽ§'
    flash(f"å·²å°†ã€{row['name']}ã€‘{state_desc}", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/toggle-cancel-target', methods=['POST'])
def api_toggle_cancel_target(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT target_to_cancel, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('æ‰¾ä¸åˆ°æŒ‡å®šè®¢é˜…è®°å½•', 'error')
        return redirect(url_for('subscriptions_page'))

    new_flag = 0 if row['target_to_cancel'] else 1
    db.execute("UPDATE subscriptions SET target_to_cancel = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_flag, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_cancel_target', user_id=user_id)
    tip = 'å·²æ ‡è®°ä¸ºã€æ‰“ç®—é€€è®¢ã€‘ï¼Œå°†åœ¨æ‰£æ¬¾å‰é«˜äº®é¢„è­¦æ‹¦æˆªï¼' if new_flag else 'å·²å–æ¶ˆé€€è®¢æ ‡è®°'
    flash(f"ã€{row['name']}ã€‘{tip}", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/roll', methods=['POST'])
def api_roll_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT * FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash('æ‰¾ä¸åˆ°æŒ‡å®šè®¢é˜…è®°å½•', 'error')
        return redirect(url_for('subscriptions_page'))

    d = dict(row)
    curr_date = datetime.strptime(d['next_billing_date'], '%Y-%m-%d').date()
    cycle = BillingCycle(d['billing_cycle'])
    anchor_day = d.get('anchor_day') or curr_date.day

    new_date = rollToNextBillingDate(curr_date, cycle, anchor_day)

    # å¯é€‰ï¼šåŒæ­¥è®°å…¥è´¦æœ¬æµæ°´
    record_expense = request.form.get('record_expense') in ('1', 'on', 'true')
    if record_expense:
        db.execute("""
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at)
            VALUES (?, ?, 'expense', 'è®¢é˜…ä¸Žå‘¨æœŸå›ºå®š', ?, ?, ?, 'subscription', datetime('now'))
        """, (
            user_id, curr_date.isoformat(), d['category'], float(d['cost']),
            f"è®¢é˜…ç»­è´¹: {d['name']} ({d['billing_cycle']})"
        ))

    db.execute("""
        UPDATE subscriptions
        SET next_billing_date = ?, anchor_day = ?, updated_at = datetime('now')
        WHERE id = ? AND user_id = ?
    """, (new_date.isoformat(), anchor_day, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_roll', user_id=user_id)
    extra_msg = 'ï¼Œå¹¶å·²è‡ªåŠ¨ç”Ÿæˆå½“æœŸè®°è´¦æ”¯å‡º' if record_expense else ''
    flash(f"ã€{d['name']}ã€‘å·²æˆåŠŸç»­æœŸè‡³ {new_date.isoformat()}{extra_msg}ï¼", 'success')
    return redirect(url_for('subscriptions_page'))


@app.route('/api/subscriptions/<int:sub_id>/delete', methods=['POST'])
def api_delete_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute("DELETE FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id))
    db.commit()
    bump_data_version('subscription_delete', user_id=user_id)
    flash('å·²åˆ é™¤è¯¥è®¢é˜…æœåŠ¡è®°å½•', 'success')
    return redirect(url_for('subscriptions_page'))


# å¯åŠ¨æ—¶ç¡®ä¿æ•°æ®åº“åˆå§‹åŒ–
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


