import json
import logging
from datetime import datetime
from flask import (
    Blueprint,
    render_template,
    request,
    jsonify,
    flash,
    redirect,
    url_for,
    Response,
    session
)

from core.db import (
    get_db,
    get_current_user_id,
    get_user_settings,
    update_user_settings,
    DEFAULT_USER_SETTINGS
)
from core.auth import is_ajax_request

logger = logging.getLogger(__name__)

settings_bp = Blueprint('settings', __name__)


@settings_bp.route('/settings', endpoint='settings_page')
def settings_page():
    if not session.get('logged_in'):
        return redirect(url_for('auth.login'))

    user_id = get_current_user_id()
    db = get_db()

    settings = get_user_settings(user_id, db=db)

    # 获取当前用户的活跃账户列表（用于默认账户下拉选项）
    accounts = []
    try:
        acc_rows = db.execute(
            "SELECT id, name, type, currency FROM accounts WHERE user_id = ? AND is_active = 1 ORDER BY id ASC",
            (str(user_id),)
        ).fetchall()
        accounts = [dict(r) for r in acc_rows]
    except Exception as e:
        logger.debug("Failed to fetch accounts for settings: %s", e)

    # 统计数据简报
    stats = {'tx_count': 0, 'acc_count': len(accounts)}
    try:
        r = db.execute("SELECT COUNT(*) as cnt FROM transactions WHERE user_id = ?", (str(user_id),)).fetchone()
        if r:
            stats['tx_count'] = r['cnt'] if hasattr(r, 'keys') else r[0]
    except Exception as e:
        logger.debug("Failed to count transactions: %s", e)

    return render_template(
        'settings.html',
        settings=settings,
        accounts=accounts,
        stats=stats
    )


@settings_bp.route('/api/settings/update', methods=['POST'], endpoint='api_update_settings')
def api_update_settings():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录'}), 401

    user_id = get_current_user_id()
    data = request.form if request.form else (request.get_json(silent=True) or {})

    updates = {}

    # 1. 外观主题
    if 'theme_mode' in data:
        tm = str(data.get('theme_mode', '')).strip().lower()
        if tm in ('system', 'dark', 'light'):
            updates['theme_mode'] = tm

    # 2. 货币符号
    if 'currency_symbol' in data:
        sym = str(data.get('currency_symbol', '')).strip()
        if sym and len(sym) <= 8:
            updates['currency_symbol'] = sym

    # 3. 默认账户
    if 'default_account_id' in data:
        val = data.get('default_account_id')
        if val in ('', None, 'none'):
            updates['default_account_id'] = None
        else:
            try:
                updates['default_account_id'] = int(val)
            except (ValueError, TypeError):
                pass

    # 4. 默认分组
    if 'default_group' in data:
        dg = str(data.get('default_group', '')).strip().lower()
        if dg in ('main', 'side'):
            updates['default_group'] = dg

    # 5. 预算与账单起始日 (1 - 31)
    if 'budget_start_day' in data:
        try:
            day = int(data.get('budget_start_day', 1))
            if 1 <= day <= 31:
                updates['budget_start_day'] = day
        except (ValueError, TypeError):
            pass

    # 6. 默认仪表盘视图
    if 'default_dashboard_view' in data:
        vw = str(data.get('default_dashboard_view', '')).strip().lower()
        if vw in ('monthly', 'overview'):
            updates['default_dashboard_view'] = vw

    # 7. 去重滑窗分钟数
    if 'dedup_window_minutes' in data:
        try:
            win = int(data.get('dedup_window_minutes', 120))
            if 5 <= win <= 1440:
                updates['dedup_window_minutes'] = win
        except (ValueError, TypeError):
            pass

    # 8. 表格显示密度
    if 'table_density' in data:
        td = str(data.get('table_density', '')).strip().lower()
        if td in ('comfortable', 'compact'):
            updates['table_density'] = td

    # 9. 触感微震动
    if 'haptic_feedback' in data:
        val = str(data.get('haptic_feedback', '')).strip()
        updates['haptic_feedback'] = 1 if val in ('1', 'true', 'on', 'yes') else 0

    if not updates:
        return jsonify({'ok': False, 'message': '未检测到需更新的有效字段'}), 400

    db = get_db()
    success = update_user_settings(user_id, updates, db=db)

    if success:
        if is_ajax_request():
            return jsonify({'ok': True, 'message': '个性化设置已保存', 'settings': updates})
        flash('偏好设置已成功更新', 'success')
        return redirect(url_for('settings.settings_page'))
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '保存设置失败，请稍后重试'}), 500
        flash('保存设置失败', 'error')
        return redirect(url_for('settings.settings_page'))


@settings_bp.route('/api/settings/reset', methods=['POST'], endpoint='api_reset_settings')
def api_reset_settings():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录'}), 401

    user_id = get_current_user_id()
    db = get_db()
    success = update_user_settings(user_id, DEFAULT_USER_SETTINGS, db=db)
    if success:
        return jsonify({'ok': True, 'message': '已恢复为默认偏好设置'})
    return jsonify({'ok': False, 'message': '恢复默认失败'}), 500


@settings_bp.route('/api/settings/export-data', methods=['GET'], endpoint='api_export_data')
def api_export_data():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录'}), 401

    user_id = get_current_user_id()
    username = session.get('username') or f'user_{user_id}'
    db = get_db()

    # 导出个人全量结构化数据
    export_payload = {
        'export_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
        'user_id': user_id,
        'username': username,
        'settings': get_user_settings(user_id, db=db),
        'accounts': [],
        'categories': [],
        'transactions': [],
        'recurring_rules': [],
        'subscriptions': [],
        'installments': []
    }

    try:
        # Accounts
        for row in db.execute("SELECT * FROM accounts WHERE user_id = ?", (str(user_id),)).fetchall():
            export_payload['accounts'].append(dict(row))
        # Categories
        for row in db.execute("SELECT * FROM categories WHERE user_id = ?", (str(user_id),)).fetchall():
            export_payload['categories'].append(dict(row))
        # Transactions
        for row in db.execute("SELECT * FROM transactions WHERE user_id = ? ORDER BY date DESC", (str(user_id),)).fetchall():
            export_payload['transactions'].append(dict(row))
        # Recurring rules
        for row in db.execute("SELECT * FROM recurring_rules WHERE user_id = ?", (str(user_id),)).fetchall():
            export_payload['recurring_rules'].append(dict(row))
        # Subscriptions
        for row in db.execute("SELECT * FROM subscriptions WHERE user_id = ?", (str(user_id),)).fetchall():
            export_payload['subscriptions'].append(dict(row))
        # Installments
        for row in db.execute("SELECT * FROM installments WHERE user_id = ?", (str(user_id),)).fetchall():
            export_payload['installments'].append(dict(row))
    except Exception as e:
        logger.error("Data export error for user %s: %s", user_id, e, exc_info=True)
        return jsonify({'ok': False, 'message': f'导出失败: {str(e)}'}), 500

    filename = f"ledger_backup_{username}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    json_bytes = json.dumps(export_payload, ensure_ascii=False, indent=2).encode('utf-8')

    return Response(
        json_bytes,
        mimetype='application/json; charset=utf-8',
        headers={
            'Content-Disposition': f'attachment; filename="{filename}"'
        }
    )
