"""
Native API Service for mobile companion apps (Android Jetpack Compose & heads-up overlays).
Adheres strictly to Thin Controller and Explicit Parameters rules (no implicit Flask context).
"""
import json
import logging
import secrets
from calendar import monthrange
from datetime import datetime, date
from decimal import Decimal
from typing import Dict, Any, Optional, Union

from services.source_tracking_service import format_source_display

logger = logging.getLogger(__name__)


def shift_month(m_str: str, delta: int) -> str:
    """Helper to shift YYYY-MM by delta months."""
    try:
        y, m = map(int, m_str.split('-'))
        total_m = y * 12 + (m - 1) + delta
        new_y = total_m // 12
        new_m = total_m % 12 + 1
        return f"{new_y:04d}-{new_m:02d}"
    except Exception as e:
        logger.debug("Failed to shift month %s by %s: %s", m_str, delta, e)
        return m_str


# -----------------------------------------------------------------------------
# 进程级带数据版本 (data_version) 安全失效的计算缓存 (符合多 Worker 安全规则)
# -----------------------------------------------------------------------------
_OVERVIEW_CACHE: Dict[tuple, Dict[str, Any]] = {}
_ACCOUNTS_CACHE: Dict[tuple, Dict[str, Any]] = {}


def get_native_overview_summary(
    user_id: Any,
    period: str = "month",  # "month", "3m", "year"
    selected_month: Optional[str] = None,
    db: Any = None,
    lang: str = "zh"
) -> Dict[str, Any]:
    """
    Computes summary metrics for native mobile Overview screen (ExpenseMY aesthetic):
    - Total spending (MYR)
    - Regular expenses vs. Transfers (self-transfers excluded)
    - Uncategorized inbox counter and items
    - Spending trend sparkline points
    - Recent transactions list
    """
    if not db or not user_id:
        return {
            'ok': False,
            'message': 'Invalid user or database connection'
        }

    today = date.today()
    if not selected_month or len(selected_month.split('-')) != 2:
        selected_month = today.strftime('%Y-%m')

    # 多 Worker 安全的数据版本缓存感知：版本未变时秒级直接命中缓存，无需重复全表扫描
    from core.db import get_data_version
    current_version = get_data_version(user_id=str(user_id), db=db)
    cache_key = (str(user_id), current_version, period, selected_month, lang)
    if cache_key in _OVERVIEW_CACHE:
        cached_res = dict(_OVERVIEW_CACHE[cache_key])
        return cached_res

    # Determine date range
    if period == "3m":
        start_month = shift_month(selected_month, -2)
        start_date = f"{start_month}-01"
        try:
            y, m = map(int, selected_month.split('-'))
            last_day = monthrange(y, m)[1]
        except Exception:
            last_day = 28
        end_date = f"{selected_month}-{last_day:02d}"
    elif period == "year":
        try:
            y = int(selected_month.split('-')[0])
        except Exception:
            y = today.year
        start_date = f"{y:04d}-01-01"
        end_date = f"{y:04d}-12-31"
    else:  # default "month"
        start_date = f"{selected_month}-01"
        try:
            y, m = map(int, selected_month.split('-'))
            last_day = monthrange(y, m)[1]
        except Exception:
            last_day = 31
        end_date = f"{selected_month}-{last_day:02d}"

    # 1. Fetch transactions within date range
    rows = db.execute('''
        SELECT id, date, type, category, amount, note, source, channel, created_at
        FROM transactions
        WHERE user_id = ? AND date BETWEEN ? AND ? AND deleted_at IS NULL
        ORDER BY date DESC, id DESC
    ''', (str(user_id), start_date, end_date)).fetchall()

    expense_amount = 0.0
    income_amount = 0.0
    transfer_amount = 0.0
    uncategorized_count = 0
    uncategorized_amount = 0.0
    uncategorized_items = []
    daily_spending: Dict[str, float] = {}
    category_spending: Dict[str, float] = {}

    UNCATEGORIZED_NAMES = {'其他', '未分类', '待分类', 'other', 'others', 'uncategorized', ''}

    regular_uncat_items = []
    for r in rows:
        amt = float(r['amount'] or 0.0)
        cat = (r['category'] or '').strip()
        t_type = (r['type'] or 'expense').strip()
        d = r['date']

        if t_type == 'income':
            if cat in ('内部划转', '转账/充值', '自转', '互转'):
                transfer_amount += amt
            else:
                income_amount += amt
        elif t_type == 'expense':
            # 区分内部划转/转账与常规外部支出
            if cat in ('内部划转', '转账/充值', '自转', '互转'):
                transfer_amount += amt
            else:
                expense_amount += amt
                daily_spending[d] = daily_spending.get(d, 0.0) + amt
                cat_name = cat or '其他'
                category_spending[cat_name] = category_spending.get(cat_name, 0.0) + amt

            # 统计待归类交易（仅包含已入账但类别未明确的普通交易）
            if cat.lower() in UNCATEGORIZED_NAMES or not cat:
                uncategorized_count += 1
                uncategorized_amount += amt
                regular_uncat_items.append({
                    'id': r['id'],
                    'date': r['date'],
                    'amount': amt,
                    'note': r['note'] or '未命名支出',
                    'category': cat or '待分类',
                    'is_pending_on_hold': False,
                    'source_type': 'transaction'
                })

    # 待确认暂存表 (on_hold) 具有最高待办优先级，排在最前端
    on_hold_items = []
    try:
        on_hold_stats = db.execute('''
            SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0.0) AS total_amt
            FROM pending_transactions
            WHERE user_id = ? AND status = 'on_hold'
        ''', (str(user_id),)).fetchone()
        if on_hold_stats:
            uncategorized_count += int(on_hold_stats['cnt'] or 0)
            uncategorized_amount += float(on_hold_stats['total_amt'] or 0.0)

        on_hold_rows = db.execute('''
            SELECT id, date, type, category, amount, note, merchant, on_hold_reason, review_reason, suspected_duplicate_id
            FROM pending_transactions
            WHERE user_id = ? AND status = 'on_hold'
            ORDER BY id DESC LIMIT 50
        ''', (str(user_id),)).fetchall()
        for p in on_hold_rows:
            p_amt = float(p['amount'] or 0.0)
            rr = p['review_reason'] or ''
            on_hold_r = (p['on_hold_reason'] or '').lower()
            if rr == 'suspected_duplicate' or p['suspected_duplicate_id'] or '重复' in on_hold_r:
                eff_reason = 'suspected_duplicate'
            elif rr == 'unmatched_account' or '账户' in on_hold_r:
                eff_reason = 'unmatched_account'
            elif rr == 'on_hold_transfer' or '转账' in on_hold_r or '充值' in on_hold_r:
                eff_reason = 'on_hold_transfer'
            else:
                eff_reason = 'unclear_category'

            dup_info = None
            if p['suspected_duplicate_id']:
                dup_row = db.execute(
                    "SELECT id, date, amount, note, category FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                    (p['suspected_duplicate_id'], str(user_id))
                ).fetchone()
                if dup_row:
                    dup_info = dict(dup_row)

            on_hold_items.append({
                'id': p['id'],
                'date': p['date'],
                'amount': p_amt,
                'note': p['note'] or p['merchant'] or '待确认交易',
                'category': p['category'] or '待确认',
                'is_pending_on_hold': True,
                'source_type': 'pending',
                'review_reason': eff_reason,
                'on_hold_reason': p['on_hold_reason'] or '',
                'suspected_duplicate_id': p['suspected_duplicate_id'],
                'suspected_duplicate': dup_info
            })
    except Exception as e:
        logger.debug("Check pending on_hold transactions skipped: %s", e)

    # 待确认转账置顶，避免被普通未分类挤掉；总容量扩充至 100 条
    uncategorized_items = on_hold_items + regular_uncat_items[:max(0, 100 - len(on_hold_items))]

    # 严格对齐口径：自转内部划转不计入日常消费总支出
    total_spending = round(expense_amount, 2)
    transfer_amount = round(transfer_amount, 2)
    income_amount = round(income_amount, 2)
    net_balance = round(income_amount - expense_amount, 2)

    # 分类消费占比排行
    category_breakdown = []
    if expense_amount > 0:
        for c_name, c_amt in sorted(category_spending.items(), key=lambda x: x[1], reverse=True):
            pct = round((c_amt / expense_amount) * 100, 1)
            category_breakdown.append({
                'category': c_name,
                'amount': round(c_amt, 2),
                'percentage': pct
            })

    # 2. 生成 Sparkline 趋势点
    sparkline_points = []
    sorted_dates = sorted(daily_spending.keys())
    for d in sorted_dates:
        sparkline_points.append({
            'date': d,
            'amount': round(daily_spending[d], 2)
        })

    # 3. 近期交易列表 (最多 25 笔)
    recent_list = []
    for r in rows[:25]:
        disp = format_source_display(r['source'], r['channel'] if 'channel' in r.keys() else None, lang=lang, note=r['note'])
        recent_list.append({
            'id': r['id'],
            'date': r['date'],
            'type': r['type'],
            'category': r['category'] or '其他',
            'amount': float(r['amount'] or 0.0),
            'note': r['note'] or '',
            'source': disp['source'],
            'channel': disp['channel'],
            'source_label': disp['source_label'],
            'channel_label': disp['channel_label'],
            'badge_text': disp['badge_text'],
            'badge_icon': disp['badge_icon'],
            'badge_emoji': disp['badge_emoji'],
            'badge_color': disp['badge_color'],
            'created_at': r['created_at']
        })

    # 4. 获取最新处理通知时间作为 Listening 心跳参考
    last_active_time = None
    try:
        last_notif = db.execute('''
            SELECT created_at FROM processed_notifications
            WHERE user_id = ?
            ORDER BY id DESC LIMIT 1
        ''', (str(user_id),)).fetchone()
        if last_notif and last_notif['created_at']:
            last_active_time = last_notif['created_at']
    except Exception as e:
        logger.debug("Failed to query last processed notification: %s", e)

    user_categories = []
    try:
        cat_rows = db.execute(
            'SELECT name FROM categories WHERE user_id = ? AND type = "expense" ORDER BY id',
            (str(user_id),)
        ).fetchall()
        user_categories = [r['name'] for r in cat_rows if r['name']]
    except Exception as e:
        logger.debug("Failed to query user categories: %s", e)

    if not user_categories:
        user_categories = ["餐饮", "交通", "杂货", "账单", "购物", "娱乐", "医疗", "其他"]
    elif "其他" not in user_categories:
        user_categories.append("其他")

    res = {
        'ok': True,
        'period': period,
        'selected_month': selected_month,
        'start_date': start_date,
        'end_date': end_date,
        'total_spending': total_spending,
        'expense_amount': round(expense_amount, 2),
        'income_amount': round(income_amount, 2),
        'net_balance': net_balance,
        'transfer_amount': round(transfer_amount, 2),
        'category_breakdown': category_breakdown,
        'uncategorized_count': uncategorized_count,
        'uncategorized_amount': round(uncategorized_amount, 2),
        'uncategorized_items': uncategorized_items,
        'categories': user_categories,
        'sparkline': sparkline_points,
        'recent_transactions': recent_list,
        'listening_status': {
            'active': True,
            'last_active_time': last_active_time or today.isoformat()
        }
    }
    try:
        from services.budget_service import get_budget_overview
        budget_overview = get_budget_overview(user_id=user_id, month=selected_month, db=db)
        res['budget'] = budget_overview
    except Exception as e:
        logger.debug("Failed to attach budget to native overview: %s", e)
        res['budget'] = None

    if len(_OVERVIEW_CACHE) > 200:
        _OVERVIEW_CACHE.clear()
    _OVERVIEW_CACHE[cache_key] = res
    return res


def update_transaction_quick_category(
    user_id: Any,
    transaction_id: int,
    category: str,
    remember_merchant: bool = False,
    custom_note: Optional[str] = None,
    db: Any = None
) -> Dict[str, Any]:
    """
    Instantly updates a transaction's category from the mobile floating card or notification action.
    Optionally saves the merchant category override rule for future automatic classification.
    """
    if not db or not user_id or not transaction_id or not category:
        return {'ok': False, 'message': '参数缺失'}

    clean_category = category.strip()
    target_tx = db.execute('''
        SELECT id, note, category, amount, date FROM transactions
        WHERE id = ? AND user_id = ? AND deleted_at IS NULL
    ''', (transaction_id, str(user_id))).fetchone()

    if not target_tx:
        return {'ok': False, 'message': '未找到对应的交易记录'}

    merchant_note = (custom_note or target_tx['note'] or '').strip()
    update_sql = "UPDATE transactions SET category = ?"
    params = [clean_category]

    if custom_note is not None and custom_note.strip():
        update_sql += ", note = ?"
        params.append(custom_note.strip())

    update_sql += " WHERE id = ? AND user_id = ?"
    params.extend([transaction_id, str(user_id)])

    db.execute(update_sql, params)

    # 如果用户勾选了“记住此商户规则”，写入 merchant_category_overrides
    if remember_merchant and merchant_note and not merchant_note.startswith('['):
        try:
            now_iso = datetime.now().isoformat()
            db.execute('''
                INSERT OR REPLACE INTO merchant_category_overrides (user_id, merchant_note, category, updated_at)
                VALUES (?, ?, ?, ?)
            ''', (str(user_id), merchant_note, clean_category, now_iso))
            logger.info("Saved merchant category override for user %s: '%s' -> '%s'", user_id, merchant_note, clean_category)
        except Exception as e:
            logger.warning("Failed to save merchant override: %s", e)

    db.commit()

    return {
        'ok': True,
        'message': f'分类已更新为【{clean_category}】',
        'transaction_id': transaction_id,
        'category': clean_category,
        'remembered': bool(remember_merchant and merchant_note)
    }


# -----------------------------------------------------------------------------
# 原生明细流水接口 (Transactions List / Filter / Search / Pagination)
# -----------------------------------------------------------------------------

def get_native_transactions_list(
    user_id: Any,
    tx_type: Optional[str] = None,
    month: Optional[str] = None,
    category: Optional[str] = None,
    search: Optional[str] = None,
    account_id: Optional[int] = None,
    start: Optional[str] = None,
    end: Optional[str] = None,
    min_amount: Optional[Union[float, str]] = None,
    max_amount: Optional[Union[float, str]] = None,
    source: Optional[str] = None,
    channel: Optional[str] = None,
    page: int = 1,
    limit: int = 50,
    db: Any = None,
    lang: str = "zh"
) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': 'Invalid user or database connection'}

    where_clauses = ["t.user_id = ?", "t.deleted_at IS NULL"]
    params = [str(user_id)]

    INTERNAL_TRANSFER_CATS = ('内部划转', '转账/充值', '自转', '互转')
    cats_placeholder = ','.join('?' for _ in INTERNAL_TRANSFER_CATS)

    if tx_type == 'transfer':
        where_clauses.append(f"(t.type = 'transfer' OR t.category IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'expense':
        where_clauses.append(f"t.type = 'expense' AND (t.category IS NULL OR t.category NOT IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'income':
        where_clauses.append(f"t.type = 'income' AND (t.category IS NULL OR t.category NOT IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type and tx_type not in ('all', ''):
        where_clauses.append("t.type = ?")
        params.append(tx_type)

    if month and len(month.split('-')) == 2:
        try:
            y, m = map(int, month.split('-'))
            last_day = monthrange(y, m)[1]
            where_clauses.append("t.date >= ? AND t.date <= ?")
            params.extend([f"{month}-01", f"{month}-{last_day:02d}"])
        except Exception:
            where_clauses.append("t.date LIKE ?")
            params.append(f"{month}%")


    if start:
        where_clauses.append("t.date >= ?")
        params.append(start)

    if end:
        where_clauses.append("t.date <= ?")
        params.append(end)

    if category and category not in ('全部', 'all', ''):
        where_clauses.append("t.category = ?")
        params.append(category)

    if account_id:
        where_clauses.append("t.account_id = ?")
        params.append(account_id)

    if min_amount is not None and str(min_amount).strip() != '':
        try:
            where_clauses.append("t.amount >= ?")
            params.append(float(min_amount))
        except (ValueError, TypeError):
            pass

    if max_amount is not None and str(max_amount).strip() != '':
        try:
            where_clauses.append("t.amount <= ?")
            params.append(float(max_amount))
        except (ValueError, TypeError):
            pass

    if source and source not in ('all', ''):
        s_clean = str(source).strip().lower()
        if s_clean in ('notification', 'auto_track', 'auto_track_confirmed'):
            where_clauses.append("(t.source = 'notification' OR t.source = 'auto_track' OR t.source = 'auto_track_confirmed')")
        elif s_clean in ('unknown', 'legacy'):
            where_clauses.append("(t.source = 'unknown' OR t.source IS NULL OR t.source = '')")
        else:
            where_clauses.append("t.source = ?")
            params.append(s_clean)

    if channel and channel not in ('all', ''):
        where_clauses.append("t.channel = ?")
        params.append(str(channel).strip().lower())

    if search and search.strip():
        term = f"%{search.strip()}%"
        where_clauses.append("(t.note LIKE ? OR t.tags LIKE ? OR t.category LIKE ? OR t.source LIKE ? OR t.channel LIKE ?)")
        params.extend([term, term, term, term, term])

    where_sql = " AND ".join(where_clauses)

    # 优化总数统计：避免在计数时执行昂贵的 LEFT JOIN accounts 与整行投影子查询
    count_query = f"SELECT COUNT(*) AS total FROM transactions t WHERE {where_sql}"
    total_count = db.execute(count_query, params).fetchone()['total']

    # Order and paginate
    page = max(1, page)
    limit = max(1, min(limit, 100))
    offset = (page - 1) * limit

    data_query = f'''
        SELECT t.id, t.date, t.type, t.category, t.amount, t.note, t.tags, t.account_id,
               t.source, t.channel, t.import_batch_id, t.source_ref, t.created_at,
               t.updated_at, t.last_edit_source, t.merged_info,
               a.name AS account_name, a.type AS account_type
        FROM transactions t
        LEFT JOIN accounts a ON t.account_id = a.id AND a.user_id = t.user_id
        WHERE {where_sql}
        ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?
    '''
    data_params = list(params) + [limit, offset]
    rows = db.execute(data_query, data_params).fetchall()
    accounts_cache = {a['id']: a for a in db.execute("SELECT id, name, type FROM accounts WHERE user_id = ?", (str(user_id),)).fetchall()}
    items = []
    for r in rows:
        disp = format_source_display(r['source'], r['channel'], lang=lang, note=r['note'])
        acc_id = r['account_id']
        acc_name = r['account_name']
        acc_type = r['account_type']
        if not acc_id and accounts_cache:
            from services.source_tracking_service import resolve_account_for_channel
            resolved_id = resolve_account_for_channel(db, user_id, channel=disp['channel'], note=r['note'])
            if resolved_id and resolved_id in accounts_cache:
                acc_id = resolved_id
                acc_name = accounts_cache[resolved_id]['name']
                acc_type = accounts_cache[resolved_id]['type']
        items.append({
            'id': r['id'],
            'date': r['date'],
            'type': r['type'],
            'category': r['category'] or '其他',
            'amount': float(r['amount'] or 0.0),
            'note': r['note'] or '',
            'tags': r['tags'] or '',
            'account_id': acc_id,
            'account_name': acc_name or '未指定账户',
            'account_type': acc_type or '',
            'source': disp['source'],
            'channel': disp['channel'],
            'source_label': disp['source_label'],
            'channel_label': disp['channel_label'],
            'badge_text': disp['badge_text'],
            'badge_icon': disp['badge_icon'],
            'badge_emoji': disp['badge_emoji'],
            'badge_color': disp['badge_color'],
            'import_batch_id': r['import_batch_id'],
            'source_ref': r['source_ref'],
            'created_at': r['created_at'],
            'updated_at': r['updated_at'],
            'last_edit_source': r['last_edit_source'],
            'is_edited': bool(r['updated_at']),
            'has_merged_evidence': bool(r['merged_info'])
        })

    has_more = (offset + len(items)) < total_count

    return {
        'ok': True,
        'page': page,
        'limit': limit,
        'total_count': total_count,
        'has_more': has_more,
        'items': items
    }


def add_native_transaction(user_id: Any, data: Dict[str, Any], db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version

    try:
        amount = float(data.get('amount') or 0.0)
    except (ValueError, TypeError):
        amount = 0.0

    if amount <= 0:
        return {'ok': False, 'message': '金额必须大于 0'}

    tx_type = (data.get('type') or 'expense').strip()
    category = (data.get('category') or '其他').strip()
    tx_date = (data.get('date') or date.today().isoformat()).strip()
    note = (data.get('note') or '').strip()
    tags = (data.get('tags') or '').strip()

    account_id = data.get('account_id')
    try:
        account_id = int(account_id) if account_id else None
    except (ValueError, TypeError):
        account_id = None

    if account_id:
        acc = db.execute(
            "SELECT id FROM accounts WHERE id = ? AND user_id = ? AND is_active = 1",
            (account_id, str(user_id))
        ).fetchone()
        if not acc:
            return {'ok': False, 'message': '指定的账户不存在或无权访问'}

    now_iso = datetime.now().isoformat()

    # 接口级幂等与极速重复点击防抖保障
    client_tx_id = (data.get('client_tx_id') or '').strip()
    if client_tx_id:
        existing_tx = db.execute(
            "SELECT id FROM transactions WHERE user_id = ? AND tags LIKE ?",
            (str(user_id), f"%client_id:{client_tx_id}%")
        ).fetchone()
        if existing_tx:
            logger.info("Idempotent hit for client_tx_id %s -> tx %s", client_tx_id, existing_tx['id'])
            return {'ok': True, 'id': existing_tx['id'], 'message': '账目已存在 (幂等保护)'}
        tags = f"{tags} client_id:{client_tx_id}".strip()
    else:
        # 兜底轻量级防重复重放：15秒内同用户、同金额、同日期、同类型、同备注、同账户如果刚写入过，避免网络抖动重发造成双重记账
        recent_dup = db.execute('''
            SELECT id FROM transactions
            WHERE user_id = ? AND date = ? AND type = ? AND abs(amount - ?) < 0.005
              AND note = ? AND COALESCE(account_id, 0) = COALESCE(?, 0)
              AND created_at >= datetime('now', '-15 seconds')
            ORDER BY id DESC LIMIT 1
        ''', (str(user_id), tx_date, tx_type, amount, note, account_id)).fetchone()
        if recent_dup:
            logger.info("Debounce hit for duplicate transaction within 15s: id %s", recent_dup['id'])
            return {'ok': True, 'id': recent_dup['id'], 'message': '账目已保存 (防止重复提交)'}

    # 如果是账户互转 (transfer)
    to_account_id = data.get('to_account_id')
    try:
        to_account_id = int(to_account_id) if to_account_id else None
    except (ValueError, TypeError):
        to_account_id = None

    if tx_type == 'transfer':
        if not account_id or not to_account_id:
            return {'ok': False, 'message': '账户互转必须同时指定转出账户与转入账户'}
        if account_id == to_account_id:
            return {'ok': False, 'message': '转出账户与转入账户不能相同'}

        to_acc = db.execute(
            "SELECT id FROM accounts WHERE id = ? AND user_id = ? AND is_active = 1",
            (to_account_id, str(user_id))
        ).fetchone()
        if not to_acc:
            return {'ok': False, 'message': '目标转入账户不存在或无权访问'}

        pair_id = secrets.token_hex(8)
        pair_tag = f"transfer_pair:{pair_id}"
        merged_tags = f"{tags} {pair_tag}".strip()

        from_acc_row = db.execute("SELECT name FROM accounts WHERE id = ?", (account_id,)).fetchone()
        to_acc_row = db.execute("SELECT name FROM accounts WHERE id = ?", (to_account_id,)).fetchone()
        from_name = from_acc_row['name'] if from_acc_row else '转出账户'
        to_name = to_acc_row['name'] if to_acc_row else '转入账户'

        out_note = note or f"转账至 {to_name}"
        in_note = note or f"由 {from_name} 转入"

        cur1 = db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at, tags, account_id)
            VALUES (?, ?, 'expense', '内部划转', ?, ?, 'manual', ?, ?, ?)
        ''', (str(user_id), tx_date, amount, out_note, now_iso, merged_tags, account_id))

        db.execute('''
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at, tags, account_id)
            VALUES (?, ?, 'income', '内部划转', ?, ?, 'manual', ?, ?, ?)
        ''', (str(user_id), tx_date, amount, in_note, now_iso, merged_tags, to_account_id))

        db.commit()
        bump_data_version('transfer', {'amount': amount, 'user_id': user_id}, user_id=user_id, db=db)
        return {'ok': True, 'id': cur1.lastrowid, 'message': '账户转账记录已保存'}

    # 常规支出或收入：对齐 Web 端的 group_name 与储蓄语义
    group_name = data.get('group_name')
    if tx_type == 'income' and not group_name:
        cat_row = db.execute(
            "SELECT group_name FROM categories WHERE user_id = ? AND type = 'income' AND name = ?",
            (str(user_id), category)
        ).fetchone()
        if cat_row and cat_row['group_name']:
            group_name = cat_row['group_name']
        else:
            setting = db.execute("SELECT default_group FROM user_settings WHERE user_id = ?", (str(user_id),)).fetchone()
            group_name = (setting['default_group'] if setting and setting['default_group'] else 'main')
    elif tx_type != 'income':
        group_name = None

    from_savings = 1 if (tx_type == 'expense' and data.get('from_savings') in (1, '1', True, 'true', 'on')) else 0
    from_savings_category = (data.get('from_savings_category') or '').strip() if from_savings else None

    cur = db.execute('''
        INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at, tags, account_id, from_savings, from_savings_category)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'manual', ?, ?, ?, ?, ?)
    ''', (str(user_id), tx_date, tx_type, group_name, category, amount, note, now_iso, tags, account_id, from_savings, from_savings_category))

    # 自动记忆商户分类规则
    if note and category and tx_type == 'expense' and not note.startswith('['):
        try:
            db.execute('''
                INSERT OR REPLACE INTO merchant_category_overrides (user_id, merchant_note, category, updated_at)
                VALUES (?, ?, ?, ?)
            ''', (str(user_id), note, category, now_iso))
        except Exception as e:
            logger.debug("Auto save merchant category override skipped: %s", e)

    db.commit()
    bump_data_version('add', {'id': cur.lastrowid, 'amount': amount, 'type': tx_type, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'id': cur.lastrowid, 'message': '记账成功'}


def update_native_transaction_full(user_id: Any, tx_id: int, data: Dict[str, Any], db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version

    tx = db.execute('SELECT id FROM transactions WHERE id = ? AND user_id = ?', (tx_id, str(user_id))).fetchone()
    if not tx:
        return {'ok': False, 'message': '未找到此交易记录'}

    try:
        amount = float(data.get('amount') or 0.0)
    except (ValueError, TypeError):
        amount = 0.0

    if amount <= 0:
        return {'ok': False, 'message': '金额必须大于 0'}

    tx_type = (data.get('type') or 'expense').strip()
    category = (data.get('category') or '其他').strip()
    tx_date = (data.get('date') or date.today().isoformat()).strip()
    note = (data.get('note') or '').strip()
    tags = (data.get('tags') or '').strip()

    account_id = data.get('account_id')
    try:
        account_id = int(account_id) if account_id else None
    except (ValueError, TypeError):
        account_id = None

    if account_id:
        acc = db.execute(
            "SELECT id FROM accounts WHERE id = ? AND user_id = ? AND is_active = 1",
            (account_id, str(user_id))
        ).fetchone()
        if not acc:
            return {'ok': False, 'message': '指定的账户不存在或无权访问'}

    db.execute('''
        UPDATE transactions
        SET date = ?, type = ?, category = ?, amount = ?, note = ?, tags = ?, account_id = ?
        WHERE id = ? AND user_id = ?
    ''', (tx_date, tx_type, category, amount, note, tags, account_id, tx_id, str(user_id)))

    db.commit()
    bump_data_version('edit', {'id': tx_id, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': '交易记录已更新'}


def undo_native_transaction(user_id: Any, tx_id: int, db: Any = None) -> Dict[str, Any]:
    """Retry-safe undo using the same recoverable deletion as the records UI."""
    if not user_id or db is None:
        return {'ok': False, 'message': 'Unauthorized'}
    row = db.execute('SELECT deleted_at FROM transactions WHERE id = ? AND user_id = ?',
                     (tx_id, str(user_id))).fetchone()
    if row is None:
        return {'ok': False, 'message': 'Transaction not found'}
    if row['deleted_at'] is not None:
        return {'ok': True, 'already_undone': True}
    return delete_native_transaction(user_id=user_id, tx_id=tx_id, db=db)


def delete_native_transaction(user_id: Any, tx_id: int, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version
    now_iso = datetime.now().isoformat()

    tx = db.execute('SELECT id, tags, date, category, amount, note, type, group_name, account_id, deleted_at FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL', (tx_id, str(user_id))).fetchone()
    if not tx:
        return {'ok': False, 'message': '未找到此交易记录或已被移入回收站'}

    tags = tx['tags'] or ''
    pair_tag = None
    for token in tags.split():
        if token.startswith('transfer_pair:'):
            pair_tag = token
            break

    if pair_tag:
        paired = db.execute("SELECT id, date, type, group_name, category, amount, note, tags, account_id, deleted_at FROM transactions WHERE user_id = ? AND tags LIKE ? AND deleted_at IS NULL", (str(user_id), f"%{pair_tag}%")).fetchall()
        snapshot_items = [dict(p) for p in paired]
        desc = "删除成对转账交易"
        cur = db.execute(
            "INSERT INTO operation_snapshots (user_id, operation_type, description, snapshot_data, created_at) VALUES (?, ?, ?, ?, ?)",
            (str(user_id), 'single_delete', desc, json.dumps(snapshot_items), now_iso)
        )
        snapshot_id = cur.lastrowid
        db.execute("UPDATE transactions SET deleted_at = ? WHERE user_id = ? AND tags LIKE ?", (now_iso, str(user_id), f"%{pair_tag}%"))
    else:
        snapshot_items = [dict(tx)]
        desc = f"删除交易: {tx['date']} {tx['category'] or ''} {tx['amount']}"
        cur = db.execute(
            "INSERT INTO operation_snapshots (user_id, operation_type, description, snapshot_data, created_at) VALUES (?, ?, ?, ?, ?)",
            (str(user_id), 'single_delete', desc, json.dumps(snapshot_items), now_iso)
        )
        snapshot_id = cur.lastrowid
        db.execute('UPDATE transactions SET deleted_at = ? WHERE id = ? AND user_id = ?', (now_iso, tx_id, str(user_id)))

    db.commit()
    bump_data_version('delete', {'id': tx_id, 'user_id': user_id, 'snapshot_id': snapshot_id, 'recycle': True}, user_id=user_id, db=db)
    return {'ok': True, 'message': '交易记录已移入回收站', 'snapshot_id': snapshot_id}


# -----------------------------------------------------------------------------
# 原生资产账户接口 (Accounts & Assets)
# -----------------------------------------------------------------------------

def get_native_accounts_summary(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    # 多 Worker 安全的数据版本缓存感知
    from core.db import get_data_version
    current_version = get_data_version(user_id=str(user_id), db=db)
    cache_key = (str(user_id), current_version)
    if cache_key in _ACCOUNTS_CACHE:
        return dict(_ACCOUNTS_CACHE[cache_key])

    acc_rows = db.execute('''
        SELECT id, name, type, credit_limit, currency, statement_day, due_day, is_active, created_at,
               last_reconciled_at, reconciled_balance
        FROM accounts
        WHERE user_id = ? AND is_active = 1
        ORDER BY type ASC, id ASC
    ''', (str(user_id),)).fetchall()

    accounts = []
    total_assets = 0.0
    total_liabilities = 0.0

    # 性能优化：单次 GROUP BY 查询合并计算所有账户资金流，彻底消除 N+1 循环全表扫描
    flow_rows = db.execute('''
        SELECT
            account_id,
            COALESCE(SUM(CASE WHEN type = 'income' THEN amount ELSE 0.0 END), 0.0) AS income_sum,
            COALESCE(SUM(CASE WHEN type = 'expense' THEN amount ELSE 0.0 END), 0.0) AS expense_sum,
            COALESCE(SUM(CASE WHEN is_reconciled = 0 OR is_reconciled IS NULL THEN 1 ELSE 0 END), 0) AS unreconciled_count
        FROM transactions
        WHERE user_id = ? AND account_id IS NOT NULL AND deleted_at IS NULL
        GROUP BY account_id
    ''', (str(user_id),)).fetchall()
    flow_map = {r['account_id']: r for r in flow_rows}

    for a in acc_rows:
        a_id = a['id']
        a_type = a['type']
        flow = flow_map.get(a_id)

        income_sum = float(flow['income_sum']) if flow else 0.0
        expense_sum = float(flow['expense_sum']) if flow else 0.0
        balance = income_sum - expense_sum

        credit_limit = float(a['credit_limit'] or 0.0)
        if a_type == 'credit_card':
            # 信用卡：支出增加已用额度（负债），还款收入降低已用额度
            used_amount = max(0.0, expense_sum - income_sum)
            total_liabilities += used_amount
            balance = used_amount  # 显示已用欠款
        else:
            if balance >= 0:
                total_assets += balance
            else:
                total_liabilities += abs(balance)

        accounts.append({
            'id': a_id,
            'name': a['name'],
            'type': a_type,
            'balance': round(balance, 2),
            'credit_limit': credit_limit,
            'currency': a['currency'] or 'MYR',
            'statement_day': a['statement_day'],
            'due_day': a['due_day'],
            'last_reconciled_at': a['last_reconciled_at'],
            'reconciled_balance': a['reconciled_balance'],
            'unreconciled_count': int(flow['unreconciled_count']) if flow else 0
        })

    net_worth = round(total_assets - total_liabilities, 2)

    setting = db.execute(
        "SELECT default_account_id, default_group FROM user_settings WHERE user_id = ?",
        (str(user_id),)
    ).fetchone()
    default_acc_id = setting['default_account_id'] if setting else None
    default_grp = (setting['default_group'] if setting and setting['default_group'] else 'main')

    res = {
        'ok': True,
        'default_account_id': default_acc_id,
        'default_group': default_grp,
        'total_assets': round(total_assets, 2),
        'total_liabilities': round(total_liabilities, 2),
        'net_worth': net_worth,
        'accounts': accounts
    }
    if len(_ACCOUNTS_CACHE) > 200:
        _ACCOUNTS_CACHE.clear()
    _ACCOUNTS_CACHE[cache_key] = res
    return res


def add_or_update_native_account(user_id: Any, data: Dict[str, Any], db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version

    name = (data.get('name') or '').strip()
    if not name:
        return {'ok': False, 'message': '账户名称不能为空'}

    acc_id = data.get('id')
    acc_type = (data.get('type') or 'savings').strip()
    currency = (data.get('currency') or 'MYR').strip()
    try:
        credit_limit = float(data.get('credit_limit') or 0.0)
    except (ValueError, TypeError):
        credit_limit = 0.0

    now_iso = datetime.now().isoformat()

    if acc_id:
        db.execute('''
            UPDATE accounts
            SET name = ?, type = ?, credit_limit = ?, currency = ?
            WHERE id = ? AND user_id = ?
        ''', (name, acc_type, credit_limit, currency, acc_id, str(user_id)))
        msg = '账户已更新'
    else:
        db.execute('''
            INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at)
            VALUES (?, ?, ?, ?, ?, 1, ?)
        ''', (str(user_id), name, acc_type, credit_limit, currency, now_iso))
        msg = '新账户已创建'

    db.commit()
    bump_data_version('account_update', {'name': name, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': msg}


# -----------------------------------------------------------------------------
# 原生更多工具与配置接口 (Categories, Pending, Tools)
# -----------------------------------------------------------------------------

def get_native_categories_list(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    rows = db.execute('''
        SELECT id, name, type, group_name
        FROM categories
        WHERE user_id = ?
        ORDER BY id ASC
    ''', (str(user_id),)).fetchall()

    expense_cats = []
    income_cats = []
    savings_cats = []
    for r in rows:
        item = {'id': r['id'], 'name': r['name'], 'type': r['type']}
        if r['type'] == 'income':
            income_cats.append(item)
        elif r['type'] == 'savings':
            savings_cats.append(item)
        else:
            expense_cats.append(item)

    if not expense_cats:
        expense_cats = [{'id': 0, 'name': n, 'type': 'expense'} for n in ["餐饮", "交通", "杂货", "账单", "购物", "娱乐", "医疗", "其他"]]
    if not income_cats:
        income_cats = [{'id': 0, 'name': n, 'type': 'income'} for n in ["工资", "兼职", "奖金", "投资", "其他"]]
    if not savings_cats:
        savings_cats = [{'id': 0, 'name': n, 'type': 'savings'} for n in ["定期存款", "应急基金", "投资理财", "心愿基金"]]

    return {
        'ok': True,
        'expense_categories': expense_cats,
        'income_categories': income_cats,
        'savings_categories': savings_cats
    }


def get_native_tools_summary(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from subscription_tracker import BillingCycle, default_mock_exchange_rate_provider

    # 1. 订阅列表（复用 Web 端周期月均与汇率折算，支持账户关联）
    sub_rows = db.execute('''
        SELECT s.id, s.name, s.category, s.billing_cycle, s.cost, s.currency, s.next_billing_date, s.auto_renew,
               s.payment_method_id, a.name AS account_name
        FROM subscriptions s
        LEFT JOIN accounts a ON s.payment_method_id = a.id
        WHERE s.user_id = ? AND s.status = 'ACTIVE'
        ORDER BY s.next_billing_date ASC
    ''', (str(user_id),)).fetchall()

    subscriptions = []
    total_monthly_sub = Decimal("0.0")
    for s in sub_rows:
        cost_val = Decimal(str(s['cost'] or 0.0))
        curr = (s['currency'] or 'MYR').upper()
        rate = default_mock_exchange_rate_provider(curr, 'MYR')
        cost_target = cost_val * rate
        cycle = (s['billing_cycle'] or 'monthly').lower()

        if cycle == BillingCycle.MONTHLY.value or cycle == 'monthly':
            m_cost = cost_target
        elif cycle == BillingCycle.WEEKLY.value or cycle == 'weekly':
            m_cost = (cost_target * Decimal("52")) / Decimal("12")
        elif cycle == BillingCycle.QUARTERLY.value or cycle == 'quarterly':
            m_cost = cost_target / Decimal("3")
        elif cycle == BillingCycle.SEMI_ANNUAL.value or cycle == 'semi_annual':
            m_cost = cost_target / Decimal("6")
        else:
            m_cost = cost_target / Decimal("12")

        total_monthly_sub += m_cost
        subscriptions.append({
            'id': s['id'],
            'name': s['name'],
            'category': s['category'],
            'billing_cycle': cycle,
            'cost': float(cost_val),
            'currency': curr,
            'next_billing_date': s['next_billing_date'],
            'auto_renew': bool(s['auto_renew']),
            'account_id': s['payment_method_id'],
            'account_name': s['account_name']
        })

    # 固定收支表中的固定支出 (recurring_rules expense)
    try:
        rec_exp_rows = db.execute('''
            SELECT r.id, r.category, r.amount, r.day_of_month, r.note, r.account_id, a.name AS account_name
            FROM recurring_rules r
            LEFT JOIN accounts a ON r.account_id = a.id
            WHERE r.user_id = ? AND r.type = 'expense' AND r.is_active = 1
            ORDER BY r.day_of_month ASC
        ''', (str(user_id),)).fetchall()
        for r in rec_exp_rows:
            amt = Decimal(str(r['amount'] or 0.0))
            total_monthly_sub += amt
            dom = r['day_of_month'] or 1
            name = r['category'] if not r['note'] else f"{r['category']} - {r['note']}"
            subscriptions.append({
                'id': 10000 + int(r['id']),
                'name': name,
                'category': r['category'] or '日常',
                'billing_cycle': 'monthly',
                'cost': float(amt),
                'currency': 'MYR',
                'next_billing_date': f"每月{dom}号",
                'auto_renew': True,
                'account_id': r['account_id'],
                'account_name': r['account_name']
            })
    except Exception as e:
        logger.debug("Fetch recurring expense rules error: %s", e)

    # 2. 固定收入列表 (recurring_rules income)
    fixed_incomes = []
    total_monthly_income = Decimal("0.0")
    try:
        inc_rows = db.execute('''
            SELECT r.id, r.category, r.amount, r.day_of_month, r.note, r.account_id, a.name AS account_name
            FROM recurring_rules r
            LEFT JOIN accounts a ON r.account_id = a.id
            WHERE r.user_id = ? AND r.type = 'income' AND r.is_active = 1
            ORDER BY r.day_of_month ASC
        ''', (str(user_id),)).fetchall()
        for r in inc_rows:
            amt = Decimal(str(r['amount'] or 0.0))
            total_monthly_income += amt
            dom = r['day_of_month'] or 1
            name = r['category'] if not r['note'] else f"{r['category']} - {r['note']}"
            fixed_incomes.append({
                'id': r['id'],
                'name': name,
                'category': r['category'] or '工资',
                'amount': float(amt),
                'currency': 'MYR',
                'arrival_day': f"每月{dom}号",
                'is_active': True,
                'account_id': r['account_id'],
                'account_name': r['account_name']
            })
    except Exception as e:
        logger.debug("Fetch recurring income rules error: %s", e)

    # 3. 分期与负债
    inst_rows = db.execute('''
        SELECT id, title, total_amount, tenure_months, paid_periods, monthly_amount, status
        FROM installments
        WHERE user_id = ? AND status = 'active'
        ORDER BY id DESC
    ''', (str(user_id),)).fetchall()

    installments = []
    total_remaining_inst = 0.0
    for i in inst_rows:
        tot = float(i['total_amount'] or 0.0)
        tenure = max(1, int(i['tenure_months'] or 1))
        paid = min(tenure, int(i['paid_periods'] or 0))
        m_amt = float(i['monthly_amount'] or (tot / tenure))
        remain = max(0.0, tot - (paid * m_amt))
        total_remaining_inst += remain
        installments.append({
            'id': i['id'],
            'title': i['title'],
            'total_amount': tot,
            'tenure_months': tenure,
            'paid_periods': paid,
            'monthly_amount': m_amt,
            'remaining_amount': round(remain, 2)
        })

    return {
        'ok': True,
        'subscriptions': subscriptions,
        'total_monthly_sub': float(round(total_monthly_sub, 2)),
        'fixed_incomes': fixed_incomes,
        'total_monthly_income': float(round(total_monthly_income, 2)),
        'installments': installments,
        'total_remaining_inst': round(total_remaining_inst, 2)
    }


# -----------------------------------------------------------------------------
# 原生回收站与操作快照撤销接口 (Recycle Bin & Undo)
# -----------------------------------------------------------------------------

def get_native_recycle_bin(user_id: Any, page: int = 1, limit: int = 50, search: Optional[str] = None, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    where_clauses = ["t.user_id = ?", "t.deleted_at IS NOT NULL"]
    params = [str(user_id)]
    if search and search.strip():
        term = f"%{search.strip()}%"
        where_clauses.append("(t.note LIKE ? OR t.category LIKE ? OR t.tags LIKE ?)")
        params.extend([term, term, term])

    where_sql = " AND ".join(where_clauses)
    total = db.execute(f"SELECT COUNT(*) AS cnt FROM transactions t WHERE {where_sql}", params).fetchone()['cnt']

    page = max(1, page)
    limit = max(1, min(limit, 100))
    offset = (page - 1) * limit

    data_query = f'''
        SELECT t.id, t.date, t.type, t.category, t.amount, t.note, t.tags, t.account_id,
               t.source, t.created_at, t.deleted_at, a.name AS account_name
        FROM transactions t
        LEFT JOIN accounts a ON t.account_id = a.id AND a.user_id = t.user_id
        WHERE {where_sql}
        ORDER BY t.deleted_at DESC, t.id DESC LIMIT ? OFFSET ?
    '''
    rows = db.execute(data_query, params + [limit, offset]).fetchall()
    items = []
    for r in rows:
        items.append({
            'id': r['id'],
            'date': r['date'],
            'type': r['type'],
            'category': r['category'] or '其他',
            'amount': float(r['amount'] or 0.0),
            'note': r['note'] or '',
            'tags': r['tags'] or '',
            'account_id': r['account_id'],
            'account_name': r['account_name'] or '默认账户',
            'source': r['source'] or 'manual',
            'created_at': r['created_at'],
            'deleted_at': r['deleted_at']
        })

    return {
        'ok': True,
        'items': items,
        'total_count': total,
        'page': page,
        'limit': limit,
        'has_more': (offset + len(items)) < total
    }


def restore_native_recycle_bin(user_id: Any, tx_id: Optional[int] = None, ids: Optional[list] = None, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version
    restore_ids = []
    if tx_id:
        restore_ids.append(tx_id)
    if ids:
        restore_ids.extend([int(i) for i in ids if str(i).isdigit()])

    if not restore_ids:
        return {'ok': False, 'message': '未指定要恢复的记录'}

    placeholders = ','.join('?' * len(restore_ids))
    cur = db.execute(
        f"UPDATE transactions SET deleted_at = NULL WHERE user_id = ? AND id IN ({placeholders}) AND deleted_at IS NOT NULL",
        [str(user_id)] + restore_ids
    )
    restored_count = cur.rowcount
    db.commit()
    bump_data_version('restore', {'restored_count': restored_count, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': f'成功恢复 {restored_count} 条记录', 'restored_count': restored_count}


def permanent_delete_native_recycle_bin(user_id: Any, tx_id: Optional[int] = None, ids: Optional[list] = None, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version
    del_ids = []
    if tx_id:
        del_ids.append(tx_id)
    if ids:
        del_ids.extend([int(i) for i in ids if str(i).isdigit()])

    if not del_ids:
        return {'ok': False, 'message': '未指定要彻底删除的记录'}

    placeholders = ','.join('?' * len(del_ids))
    cur = db.execute(
        f"DELETE FROM transactions WHERE user_id = ? AND id IN ({placeholders}) AND deleted_at IS NOT NULL",
        [str(user_id)] + del_ids
    )
    deleted_count = cur.rowcount
    db.commit()
    bump_data_version('permanent_delete', {'deleted_count': deleted_count, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': f'已彻底销毁 {deleted_count} 条记录', 'deleted_count': deleted_count}


def empty_native_recycle_bin(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version
    cur = db.execute("DELETE FROM transactions WHERE user_id = ? AND deleted_at IS NOT NULL", (str(user_id),))
    cnt = cur.rowcount
    db.commit()
    bump_data_version('empty_recycle_bin', {'deleted_count': cnt, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': f'回收站已清空，共销毁 {cnt} 条记录', 'deleted_count': cnt}


def undo_native_operation(user_id: Any, snapshot_id: int, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version
    snap = db.execute(
        "SELECT * FROM operation_snapshots WHERE id = ? AND user_id = ?",
        (snapshot_id, str(user_id))
    ).fetchone()
    if not snap:
        return {'ok': False, 'message': '未找到该操作快照'}

    if snap['is_reverted']:
        return {'ok': False, 'message': '该操作已经撤销过，无需重复撤销'}

    try:
        items = json.loads(snap['snapshot_data'])
    except Exception as e:
        logger.error("Failed to parse snapshot json: %s", e)
        return {'ok': False, 'message': '快照数据损坏'}

    op_type = snap['operation_type']
    if op_type in ('single_delete', 'batch_delete'):
        for item in items:
            db.execute(
                "UPDATE transactions SET deleted_at = ? WHERE id = ? AND user_id = ?",
                (item.get('deleted_at'), item['id'], str(user_id))
            )
    elif op_type == 'batch_edit':
        for item in items:
            db.execute(
                "UPDATE transactions SET type = ?, group_name = ?, category = ? WHERE id = ? AND user_id = ?",
                (item.get('type'), item.get('group_name'), item.get('category'), item['id'], str(user_id))
            )

    db.execute("UPDATE operation_snapshots SET is_reverted = 1 WHERE id = ?", (snapshot_id,))
    db.commit()
    bump_data_version('undo', {'snapshot_id': snapshot_id, 'operation_type': op_type, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': '已成功撤销操作', 'reverted_count': len(items)}


