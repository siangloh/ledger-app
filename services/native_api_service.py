"""
Native API Service for mobile companion apps (Android Jetpack Compose & heads-up overlays).
Adheres strictly to Thin Controller and Explicit Parameters rules (no implicit Flask context).
"""
import logging
from calendar import monthrange
from datetime import datetime, date
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
    transfer_amount = 0.0
    uncategorized_count = 0
    uncategorized_amount = 0.0
    uncategorized_items = []
    daily_spending: Dict[str, float] = {}

    UNCATEGORIZED_NAMES = {'其他', '未分类', '待分类', 'other', 'others', 'uncategorized', ''}

    regular_uncat_items = []
    for r in rows:
        amt = float(r['amount'] or 0.0)
        cat = (r['category'] or '').strip()
        t_type = (r['type'] or 'expense').strip()
        d = r['date']

        if t_type == 'expense':
            # 区分内部划转/转账与常规支出
            if cat in ('内部划转', '转账/充值', '自转', '互转'):
                transfer_amount += amt
            else:
                expense_amount += amt
                daily_spending[d] = daily_spending.get(d, 0.0) + amt

            # 统计待归类交易
            if cat.lower() in UNCATEGORIZED_NAMES or not cat:
                uncategorized_count += 1
                uncategorized_amount += amt
                regular_uncat_items.append({
                    'id': r['id'],
                    'date': r['date'],
                    'amount': amt,
                    'note': r['note'] or '未命名支出',
                    'category': cat or '待分类'
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
                'is_pending_on_hold': True
            })
    except Exception as e:
        logger.debug("Check pending on_hold transactions skipped: %s", e)

    # 待确认转账置顶，避免被普通未分类挤掉；总容量扩充至 100 条
    uncategorized_items = on_hold_items + regular_uncat_items[:max(0, 100 - len(on_hold_items))]

    total_spending = round(expense_amount + transfer_amount, 2)

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
        'transfer_amount': round(transfer_amount, 2),
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
