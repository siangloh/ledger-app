from calendar import monthrange
from datetime import date, datetime
from core.db import get_db, get_current_user_id, bump_data_version


def money_filter(value):
    """格式化为林吉特金额，如 RM 3,900.00"""
    try:
        value = float(value)
    except (TypeError, ValueError):
        value = 0.0
    return f"RM {value:,.2f}"


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


def get_category_budget_status(db, user_id=None, month=None):
    """
    返回当前用户每个已设置月度预算的支出分类的花费进度：
    [{category, limit, spent, remaining, pct, level}], 按 pct 从高到低排序。
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
    spent_by_category = {(r['category'] or '其他'): float(r['total'] or 0.0) for r in spent_rows}

    result = []
    for b in budgets:
        cat = b['category']
        limit = float(b['monthly_limit'])
        spent = spent_by_category.get(cat, 0.0)
        pct = (spent / limit * 100.0) if limit > 0 else 0.0
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
    检查某个分类本月花费是否新跨越了一个提醒阈值（70% / 100% / 150%）。
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
        'message': f'预算提醒：「{category}」本月已花 RM{spent:.2f} / RM{limit:.2f}（{round(pct)}%）',
    }


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
