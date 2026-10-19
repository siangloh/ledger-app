"""
services/budget_service.py
预算核心服务层 (Budget Management Service)

职责：
1. 月度总预算与各分类支出预算的统一建模与持久化
2. 账单周期自动对齐（支持自定义发薪周期/预算起始日）
3. 动态预算执行率、超支预警状态计算与每日安全建议花费额度 (Daily Safe Allowance)
4. 数据版本自增 (bump_data_version) 支撑多端实时同步
"""

import logging
from datetime import datetime, date
from typing import Any, Dict, List, Optional

from core.db import bump_data_version, get_user_settings
from core.utils import get_billing_cycle_dates, get_category_budget_status

logger = logging.getLogger(__name__)


def get_overall_budget_limit(user_id: Any, db: Any) -> float:
    """
    获取用户的月度总预算上限。若未设置返回 0.0。
    """
    if not db or not user_id:
        return 0.0
    try:
        row = db.execute(
            "SELECT monthly_limit FROM category_budgets WHERE user_id = ? AND category = '__OVERALL__'",
            (str(user_id),)
        ).fetchone()
        if row and row['monthly_limit'] is not None:
            return max(0.0, float(row['monthly_limit']))
    except Exception as e:
        logger.debug("Failed to query overall budget for user %s: %s", user_id, e)
    return 0.0


def set_overall_budget(user_id: Any, monthly_limit: float, db: Any) -> Dict[str, Any]:
    """
    设置或取消用户的月度总预算上限。当 monthly_limit <= 0 时视为取消总预算。
    """
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库未就绪'}

    limit = round(float(monthly_limit or 0.0), 2)
    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    try:
        if limit <= 0.0:
            db.execute(
                "DELETE FROM category_budgets WHERE user_id = ? AND category = '__OVERALL__'",
                (str(user_id),)
            )
            db.commit()
            bump_data_version('overall_budget_cancelled', {'user_id': str(user_id)}, user_id=str(user_id), db=db)
            return {'ok': True, 'monthly_limit': 0.0, 'message': '已取消月度总预算'}
        else:
            db.execute('''
                INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at)
                VALUES (?, '__OVERALL__', ?, ?, ?)
                ON CONFLICT(user_id, category) DO UPDATE SET
                    monthly_limit = excluded.monthly_limit,
                    updated_at = excluded.updated_at
            ''', (str(user_id), limit, now_iso, now_iso))
            db.commit()
            bump_data_version('overall_budget_updated', {'monthly_limit': limit}, user_id=str(user_id), db=db)
            return {'ok': True, 'monthly_limit': limit, 'message': f'已将月度总预算设置为 {limit:.2f}'}
    except Exception as e:
        logger.warning("Failed to set overall budget for user %s: %s", user_id, e)
        return {'ok': False, 'message': '设置总预算失败，请重试'}


def set_category_budget(user_id: Any, category: str, monthly_limit: float, db: Any) -> Dict[str, Any]:
    """
    设置某个分类的月度预算限额。当 monthly_limit <= 0 时视为取消该分类预算。
    """
    if not db or not user_id or not category:
        return {'ok': False, 'message': '参数缺失'}

    cat = category.strip()
    if cat == '__OVERALL__':
        return set_overall_budget(user_id, monthly_limit, db)

    limit = round(float(monthly_limit or 0.0), 2)
    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    try:
        if limit <= 0.0:
            db.execute(
                "DELETE FROM category_budgets WHERE user_id = ? AND category = ?",
                (str(user_id), cat)
            )
            db.commit()
            bump_data_version('budget_cancelled', {'category': cat}, user_id=str(user_id), db=db)
            return {'ok': True, 'category': cat, 'monthly_limit': 0.0, 'message': f'已取消「{cat}」的预算限额'}
        else:
            db.execute('''
                INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, category) DO UPDATE SET
                    monthly_limit = excluded.monthly_limit,
                    updated_at = excluded.updated_at
            ''', (str(user_id), cat, limit, now_iso, now_iso))
            db.commit()
            bump_data_version('budget_updated', {'category': cat, 'monthly_limit': limit}, user_id=str(user_id), db=db)
            return {'ok': True, 'category': cat, 'monthly_limit': limit, 'message': f'已将「{cat}」预算设置为 {limit:.2f}'}
    except Exception as e:
        logger.warning("Failed to set category budget for user %s, cat %s: %s", user_id, cat, e)
        return {'ok': False, 'message': '设置分类预算失败，请重试'}


def delete_category_budget(user_id: Any, category: str, db: Any) -> Dict[str, Any]:
    """
    删除某个分类的月度预算。
    """
    return set_category_budget(user_id, category, 0.0, db)


def get_budget_overview(user_id: Any, month: Optional[str] = None, db: Any = None) -> Dict[str, Any]:
    """
    获取指定月份（或账单周期）的全面预算状态概览：
    包括：
    - 账单周期信息（起始日、总天数、已过天数、剩余天数）
    - 总预算执行情况（总限额、总支出、剩余额度、执行百分比、每日建议花费额度 daily_allowance、警戒级别）
    - 各子分类预算状态详情与对应图表颜色
    - 预算健康度统计（超支数、警戒数、安全数）
    """
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库未就绪'}

    if not month:
        month = date.today().strftime('%Y-%m')

    # 读取用户个性化偏好中的账单周期起始日
    user_settings = get_user_settings(user_id, db=db)
    start_day = int(user_settings.get('budget_start_day', 1) or 1)

    start_date, end_date, cycle_label = get_billing_cycle_dates(month, start_day)

    # 计算周期内的天数与进度
    today = date.today()
    try:
        s_date = datetime.strptime(start_date, '%Y-%m-%d').date()
        e_date = datetime.strptime(end_date, '%Y-%m-%d').date()
        total_days = max(1, (e_date - s_date).days + 1)

        if today < s_date:
            passed_days = 0
            remaining_days = total_days
        elif today > e_date:
            passed_days = total_days
            remaining_days = 0
        else:
            passed_days = (today - s_date).days + 1
            remaining_days = max(0, (e_date - today).days + 1)
    except Exception as e:
        logger.debug("Calculate cycle dates error: %s", e)
        total_days = 30
        passed_days = 15
        remaining_days = 15

    # 查询周期内实际支出总额
    spent_row = db.execute(
        "SELECT SUM(amount) as total FROM transactions "
        "WHERE user_id = ? AND type = 'expense' AND date BETWEEN ? AND ? AND deleted_at IS NULL",
        (str(user_id), start_date, end_date)
    ).fetchone()
    total_spent = round(float(spent_row['total'] or 0.0) if spent_row else 0.0, 2)

    # 查询显式设置的月度总预算
    overall_limit = get_overall_budget_limit(user_id, db)

    # 查询所有已设置的分项预算状态
    cat_budgets = get_category_budget_status(db, user_id, month, start_day=start_day)

    # 关联分类颜色
    cat_rows = db.execute(
        "SELECT name, color FROM categories WHERE user_id = ? AND type = 'expense'",
        (str(user_id),)
    ).fetchall()
    color_map = {r['name']: (r['color'] or '#0F766E') for r in cat_rows}

    enriched_cats: List[Dict[str, Any]] = []
    sum_category_limits = 0.0
    for b in cat_budgets:
        b_cat = b['category']
        b_limit = b['limit']
        sum_category_limits += b_limit
        enriched_cats.append({
            'category': b_cat,
            'limit': b_limit,
            'spent': b['spent'],
            'remaining': b['remaining'],
            'pct': b['pct'],
            'level': b['level'],
            'color': color_map.get(b_cat, '#0F766E')
        })

    # 若未设置显式总预算，但设置了分类预算，可以以各分类预算之和作为参考总预算
    effective_limit = overall_limit if overall_limit > 0.0 else sum_category_limits
    is_explicit_overall = overall_limit > 0.0

    if effective_limit > 0.0:
        remaining_overall = round(effective_limit - total_spent, 2)
        overall_pct = round((total_spent / effective_limit) * 100.0, 1)
        if overall_pct > 100.0:
            overall_level = 'over'
        elif overall_pct == 100.0:
            overall_level = 'reached'
        elif overall_pct >= 70.0:
            overall_level = 'warn'
        else:
            overall_level = 'ok'

        if remaining_days > 0 and remaining_overall > 0:
            daily_allowance = round(remaining_overall / remaining_days, 2)
        else:
            daily_allowance = 0.0
    else:
        remaining_overall = 0.0
        overall_pct = 0.0
        overall_level = 'none'
        daily_allowance = 0.0

    over_count = sum(1 for c in enriched_cats if c['level'] == 'over')
    warn_count = sum(1 for c in enriched_cats if c['level'] in ('warn', 'reached'))
    ok_count = sum(1 for c in enriched_cats if c['level'] == 'ok')

    return {
        'ok': True,
        'month': month,
        'billing_cycle': {
            'start_date': start_date,
            'end_date': end_date,
            'label': cycle_label,
            'start_day': start_day,
            'total_days': total_days,
            'passed_days': passed_days,
            'remaining_days': remaining_days,
        },
        'overall': {
            'limit': round(effective_limit, 2),
            'spent': round(total_spent, 2),
            'remaining': round(remaining_overall, 2),
            'pct': overall_pct,
            'level': overall_level,
            'daily_allowance': daily_allowance,
            'is_explicit': is_explicit_overall,
            'is_set': effective_limit > 0.0
        },
        'categories': enriched_cats,
        'summary': {
            'total_categories_budgeted': len(enriched_cats),
            'over_count': over_count,
            'warn_count': warn_count,
            'ok_count': ok_count
        }
    }
