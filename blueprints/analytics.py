from calendar import monthrange
from datetime import date
from flask import Blueprint, request, jsonify, render_template

from core.db import get_db, get_current_user_id
from core.utils import shift_month

analytics_bp = Blueprint('analytics', __name__)


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

    # 统计当月与上月的绝对支出
    cur_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{current_month}%")
    ).fetchall()
    cur_month_map = {r['category'] or '其他': float(r['total'] or 0) for r in cur_month_rows}

    prev_month_rows = db.execute(
        "SELECT category, SUM(amount) as total FROM transactions WHERE user_id = ? AND type='expense' AND date LIKE ? GROUP BY category",
        (user_id, f"{prev_month}%")
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

    cat_rows = db.execute("SELECT name, color FROM categories WHERE user_id = ?", (user_id,)).fetchall()
    cat_colors = {r['name']: r['color'] for r in cat_rows if r['color']}

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
            'color': cat_colors.get(cat, '#fb7185'),
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


@analytics_bp.route('/api/category-insights', endpoint='api_category_insights')
def api_category_insights():
    user_id = get_current_user_id()
    db = get_db()
    time_range = request.args.get('range', 'all')
    start_date = (request.args.get('start') or '').strip() or None
    end_date = (request.args.get('end') or '').strip() or None
    data = get_category_insights_data(db, time_range, start_date, end_date, user_id=user_id)
    return jsonify(data)


@analytics_bp.route('/categories/insights', endpoint='category_insights_page')
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


@analytics_bp.route('/api/dashboard-charts', endpoint='api_dashboard_charts')
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
            c = r['category'] or '其他'
            expense_by_category[c] = expense_by_category.get(c, 0.0) + r['amount']

    sorted_expenses = sorted(expense_by_category.items(), key=lambda x: x[1], reverse=True)

    return jsonify({
        'ok': True,
        'month': month,
        'income': {
            'labels': ['主业收入', '副业收入'],
            'values': [round(income_group.get('main', 0.0), 2), round(income_group.get('side', 0.0), 2)]
        },
        'expense': {
            'labels': [k for k, _ in sorted_expenses],
            'values': [round(v, 2) for _, v in sorted_expenses]
        }
    })


@analytics_bp.route('/api/overview', endpoint='api_overview')
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

    # 月均计算：有月份跨度按跨度算，否则按实际有记录的月份数，至少为 1
    num_months = max(len(month_keys), 1)
    avg_income = total_income / num_months
    avg_expense = total_expense / num_months
    avg_savings = total_savings / num_months
    net_savings = total_income - regular_expense - total_savings

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
