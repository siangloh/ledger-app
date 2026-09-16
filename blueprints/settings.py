import csv
import codecs
import io
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

    # 2. 货币与符号
    if 'default_currency' in data:
        dc = str(data.get('default_currency', '')).strip().upper()
        if dc and len(dc) <= 6:
            updates['default_currency'] = dc

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

    # 10. 日期格式
    if 'date_format' in data:
        df_val = str(data.get('date_format', '')).strip()
        if df_val in ('YYYY-MM-DD', 'DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY/MM/DD'):
            updates['date_format'] = df_val

    # 11. 数字千分位格式
    if 'number_format' in data:
        nf_val = str(data.get('number_format', '')).strip().lower()
        if nf_val in ('comma', 'space'):
            updates['number_format'] = nf_val

    # 12. 时区管理
    if 'timezone' in data:
        tz_val = str(data.get('timezone', '')).strip()
        try:
            import zoneinfo
            zoneinfo.ZoneInfo(tz_val)
            updates['timezone'] = tz_val
        except Exception:
            pass

    # 13. 自然语言免确认直接入账
    if 'nlp_confirm_required' in data:
        val = str(data.get('nlp_confirm_required', '')).strip().lower()
        updates['nlp_confirm_required'] = 1 if val in ('1', 'true', 'on', 'yes') else 0

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
    export_format = request.args.get('format', 'json').strip().lower()

    if export_format == 'csv':
        try:
            rows = db.execute('''
                SELECT t.id, t.date, t.type, t.group_name, t.category, t.amount,
                       COALESCE(a.name, '默认账户') as account_name,
                       t.note, t.tags, t.source,
                       t.from_savings, t.from_savings_category, t.created_at
                FROM transactions t
                LEFT JOIN accounts a ON t.account_id = a.id
                WHERE t.user_id = ?
                ORDER BY t.date DESC, t.id DESC
            ''', (str(user_id),)).fetchall()

            output = io.StringIO()
            writer = csv.writer(output)
            writer.writerow([
                '流水号', '记账日期', '收支类型', '分组', '分类', '金额',
                '账户', '备注/商户', '标签', '录入渠道', '储蓄支出', '储蓄分类', '创建时间'
            ])
            type_map = {'income': '收入', 'expense': '支出', 'savings': '储蓄'}
            group_map = {'main': '主业', 'side': '副业'}
            source_map = {
                'manual': '手动录入', 'nlp': '智能记账', 'import': '批量导入',
                'recurring': '固定收支', 'auto_track': '自动记账', 'split_bill': 'AA分账'
            }
            for r in rows:
                writer.writerow([
                    r['id'],
                    r['date'],
                    type_map.get(r['type'], r['type']),
                    group_map.get(r['group_name'], r['group_name'] or ''),
                    r['category'] or '',
                    f"{float(r['amount'] or 0):.2f}",
                    r['account_name'],
                    r['note'] or '',
                    r['tags'] or '',
                    source_map.get(r['source'], r['source'] or ''),
                    '是' if r['from_savings'] else '否',
                    r['from_savings_category'] or '',
                    r['created_at'] or ''
                ])
            csv_bytes = codecs.BOM_UTF8 + output.getvalue().encode('utf-8')
            csv_filename = f"ledger_transactions_{username}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
            return Response(
                csv_bytes,
                mimetype='text/csv; charset=utf-8-sig',
                headers={
                    'Content-Disposition': f'attachment; filename="{csv_filename}"'
                }
            )
        except Exception as e:
            logger.error("CSV export error for user %s: %s", user_id, e, exc_info=True)
            return jsonify({'ok': False, 'message': f'CSV 导出失败: {str(e)}'}), 500

    # 导出个人全量结构化数据 (JSON)
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
