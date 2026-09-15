import os
import hmac
import turso_db

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.environ.get('DATA_DIR', BASE_DIR)
os.makedirs(DATA_DIR, exist_ok=True)
DB_PATH = os.path.join(DATA_DIR, 'ledger.db')
UPLOAD_DIR = os.path.join(DATA_DIR, 'uploads')
os.makedirs(UPLOAD_DIR, exist_ok=True)

# Turso 云数据库凭证
TURSO_URL = turso_db.TURSO_URL
TURSO_AUTH_TOKEN = turso_db.TURSO_AUTH_TOKEN

# 自动记账 API 鉴权密钥
AUTO_TRACK_KEY = os.environ.get('AUTO_TRACK_KEY')
AUTO_TRACK_DEBUG_LOG = os.environ.get('AUTO_TRACK_DEBUG_LOG', '0') == '1'
DEFAULT_AUTO_TRACK_KEY = 'zo}SxK_}_%0LO8w;'

# LLM 智能服务配置
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

APP_PASSWORD = os.environ.get('APP_PASSWORD')


import logging

logger = logging.getLogger(__name__)


def get_auto_track_key(db=None):
    """获取有效的 AUTO_TRACK_KEY（优先环境变量，次选数据库 system_settings，保底系统默认配套 key）"""
    if AUTO_TRACK_KEY and AUTO_TRACK_KEY.strip():
        return AUTO_TRACK_KEY.strip()
    if db:
        try:
            row = db.execute("SELECT value FROM system_settings WHERE key='auto_track_key'").fetchone()
            if row and row['value'] and str(row['value']).strip():
                return str(row['value']).strip()
        except Exception as e:
            logger.debug("Read system_settings auto_track_key skipped: %s", e)
    return DEFAULT_AUTO_TRACK_KEY


def is_valid_api_key(req_key, db=None):
    """检验 API Key 是否合法。使用恒定时间比较避免计时侧信道攻击。"""
    if not req_key:
        return False
    effective = get_auto_track_key(db)
    req_bytes = str(req_key).strip().encode('utf-8')
    if effective and hmac.compare_digest(req_bytes, effective.encode('utf-8')):
        return True
    if DEFAULT_AUTO_TRACK_KEY and hmac.compare_digest(req_bytes, DEFAULT_AUTO_TRACK_KEY.encode('utf-8')):
        return True
    return False


def get_app_password(db=None):
    """单用户访问密码"""
    env_pw = os.environ.get('APP_PASSWORD')
    if env_pw:
        return env_pw
    if db:
        try:
            row = db.execute("SELECT value FROM system_settings WHERE key='app_password'").fetchone()
            if row and row['value']:
                return row['value']
        except Exception as e:
            logger.debug("Read system_settings app_password skipped: %s", e)
    return None


def get_active_llm_provider():
    """返回当前优先启用的 LLM 供应商名称与模型"""
    if GEMINI_API_KEY:
        return {'provider': 'gemini', 'name': f'Google Gemini ({GEMINI_MODEL})', 'available': True}
    if DEEPSEEK_API_KEY:
        return {'provider': 'deepseek', 'name': 'DeepSeek (deepseek-chat)', 'available': True}
    if OPENAI_API_KEY:
        return {'provider': 'openai', 'name': f'OpenAI ({OPENAI_MODEL})', 'available': True}
    return {'provider': 'ollama', 'name': f'Local Ollama ({OLLAMA_MODEL})', 'available': False}
