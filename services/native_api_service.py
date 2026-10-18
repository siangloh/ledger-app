"""
Native API Service for mobile companion apps (Android Jetpack Compose & heads-up overlays).
Adheres strictly to Thin Controller and Explicit Parameters rules (no implicit Flask context).
"""
import logging
import secrets
from calendar import monthrange
from datetime import datetime, date
from decimal import Decimal
from typing import Dict, Any, Optional

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


def get_native_overview_summary(
    user_id: Any,
    period: str = "month",  # "month", "3m", "year"
    selected_month: Optional[str] = None,
    db: Any = None
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
        SELECT id, date, type, category, amount, note, source, created_at
        FROM transactions
        WHERE user_id = ? AND date BETWEEN ? AND ?
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
            SELECT id, date, type, category, amount, note, merchant, on_hold_reason
            FROM pending_transactions
            WHERE user_id = ? AND status = 'on_hold'
            ORDER BY id DESC LIMIT 50
        ''', (str(user_id),)).fetchall()
        for p in on_hold_rows:
            p_amt = float(p['amount'] or 0.0)
            on_hold_items.append({
                'id': p['id'],
                'date': p['date'],
                'amount': p_amt,
                'note': p['note'] or p['merchant'] or '待确认划转',
                'category': '待确认转账',
                'is_pending_on_hold': True,
                'source_type': 'pending'
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
        recent_list.append({
            'id': r['id'],
            'date': r['date'],
            'type': r['type'],
            'category': r['category'] or '其他',
            'amount': float(r['amount'] or 0.0),
            'note': r['note'] or '',
            'source': r['source'] or 'auto_track',
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

    return {
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
        WHERE id = ? AND user_id = ?
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
    page: int = 1,
    limit: int = 50,
    db: Any = None
) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': 'Invalid user or database connection'}

    query = '''
        SELECT t.id, t.date, t.type, t.category, t.amount, t.note, t.tags, t.account_id,
               t.source, t.created_at, a.name AS account_name, a.type AS account_type
        FROM transactions t
        LEFT JOIN accounts a ON t.account_id = a.id AND a.user_id = t.user_id
        WHERE t.user_id = ?
    '''
    params = [str(user_id)]

    INTERNAL_TRANSFER_CATS = ('内部划转', '转账/充值', '自转', '互转')
    cats_placeholder = ','.join('?' for _ in INTERNAL_TRANSFER_CATS)

    if tx_type == 'transfer':
        query += f" AND (t.type = 'transfer' OR t.category IN ({cats_placeholder}))"
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'expense':
        query += f" AND t.type = 'expense' AND (t.category IS NULL OR t.category NOT IN ({cats_placeholder}))"
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'income':
        query += f" AND t.type = 'income' AND (t.category IS NULL OR t.category NOT IN ({cats_placeholder}))"
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type and tx_type not in ('all', ''):
        query += " AND t.type = ?"
        params.append(tx_type)

    if month and len(month.split('-')) == 2:
        query += " AND t.date LIKE ?"
        params.append(f"{month}%")

    if category and category not in ('全部', 'all', ''):
        query += " AND t.category = ?"
        params.append(category)

    if account_id:
        query += " AND t.account_id = ?"
        params.append(account_id)

    if search and search.strip():
        term = f"%{search.strip()}%"
        query += " AND (t.note LIKE ? OR t.tags LIKE ? OR t.category LIKE ?)"
        params.extend([term, term, term])

    # Count total
    count_query = f"SELECT COUNT(*) AS total FROM ({query}) sub"
    total_count = db.execute(count_query, params).fetchone()['total']

    # Order and paginate
    page = max(1, page)
    limit = max(1, min(limit, 100))
    offset = (page - 1) * limit
    query += " ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    rows = db.execute(query, params).fetchall()
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
            'account_name': r['account_name'] or '未指定账户',
            'account_type': r['account_type'] or '',
            'source': r['source'] or 'manual',
            'created_at': r['created_at']
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


def delete_native_transaction(user_id: Any, tx_id: int, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from core.db import bump_data_version

    tx = db.execute('SELECT id, tags FROM transactions WHERE id = ? AND user_id = ?', (tx_id, str(user_id))).fetchone()
    if not tx:
        return {'ok': False, 'message': '未找到此交易记录'}

    tags = tx['tags'] or ''
    pair_tag = None
    for token in tags.split():
        if token.startswith('transfer_pair:'):
            pair_tag = token
            break

    if pair_tag:
        # 成对转账：原子同步删除出账与入账记录
        db.execute("DELETE FROM transactions WHERE user_id = ? AND tags LIKE ?", (str(user_id), f"%{pair_tag}%"))
    else:
        db.execute('DELETE FROM transactions WHERE id = ? AND user_id = ?', (tx_id, str(user_id)))

    db.commit()
    bump_data_version('delete', {'id': tx_id, 'user_id': user_id}, user_id=user_id, db=db)
    return {'ok': True, 'message': '交易记录已删除'}


# -----------------------------------------------------------------------------
# 原生资产账户接口 (Accounts & Assets)
# -----------------------------------------------------------------------------

def get_native_accounts_summary(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    acc_rows = db.execute('''
        SELECT id, name, type, credit_limit, currency, statement_day, due_day, is_active, created_at
        FROM accounts
        WHERE user_id = ? AND is_active = 1
        ORDER BY type ASC, id ASC
    ''', (str(user_id),)).fetchall()

    accounts = []
    total_assets = 0.0
    total_liabilities = 0.0

    for a in acc_rows:
        a_id = a['id']
        a_type = a['type']
        # 计算该账户的净流入流出流水差额
        flow = db.execute('''
            SELECT
                COALESCE(SUM(CASE WHEN type = 'income' THEN amount ELSE 0.0 END), 0.0) AS income_sum,
                COALESCE(SUM(CASE WHEN type = 'expense' THEN amount ELSE 0.0 END), 0.0) AS expense_sum
            FROM transactions
            WHERE user_id = ? AND account_id = ?
        ''', (str(user_id), a_id)).fetchone()

        income_sum = float(flow['income_sum'] or 0.0)
        expense_sum = float(flow['expense_sum'] or 0.0)
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
            'due_day': a['due_day']
        })

    net_worth = round(total_assets - total_liabilities, 2)

    setting = db.execute(
        "SELECT default_account_id, default_group FROM user_settings WHERE user_id = ?",
        (str(user_id),)
    ).fetchone()
    default_acc_id = setting['default_account_id'] if setting else None
    default_grp = (setting['default_group'] if setting and setting['default_group'] else 'main')

    return {
        'ok': True,
        'default_account_id': default_acc_id,
        'default_group': default_grp,
        'total_assets': round(total_assets, 2),
        'total_liabilities': round(total_liabilities, 2),
        'net_worth': net_worth,
        'accounts': accounts
    }


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
    for r in rows:
        item = {'id': r['id'], 'name': r['name'], 'type': r['type']}
        if r['type'] == 'income':
            income_cats.append(item)
        else:
            expense_cats.append(item)

    if not expense_cats:
        expense_cats = [{'id': 0, 'name': n, 'type': 'expense'} for n in ["餐饮", "交通", "杂货", "账单", "购物", "娱乐", "医疗", "其他"]]
    if not income_cats:
        income_cats = [{'id': 0, 'name': n, 'type': 'income'} for n in ["工资", "兼职", "奖金", "投资", "其他"]]

    return {
        'ok': True,
        'expense_categories': expense_cats,
        'income_categories': income_cats
    }


def get_native_tools_summary(user_id: Any, db: Any = None) -> Dict[str, Any]:
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库连接失败'}

    from subscription_tracker import BillingCycle, default_mock_exchange_rate_provider

    # 1. 订阅列表（复用 Web 端周期月均与汇率折算）
    sub_rows = db.execute('''
        SELECT id, name, category, billing_cycle, cost, currency, next_billing_date, auto_renew
        FROM subscriptions
        WHERE user_id = ? AND status = 'ACTIVE'
        ORDER BY next_billing_date ASC
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
            'auto_renew': bool(s['auto_renew'])
        })

    # 2. 分期与负债
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
        'installments': installments,
        'total_remaining_inst': round(total_remaining_inst, 2)
    }

