# -*- coding: utf-8 -*-
"""
Saved Filters Service (交易搜索与保存筛选服务)
支持:
  - 筛选预设持久化 (CRUD, 固定 Pinned 置顶)
  - 默认常用快捷筛选预设自举 (本月大额、待核对、结余储蓄等)
  - 统一多维度筛选 SQL 构建器 (关键词、类型、分类、账户、时间范围、金额区间、来源)
"""

import json
import logging
from calendar import monthrange
from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple, Union

from core.db import bump_data_version

logger = logging.getLogger(__name__)

INTERNAL_TRANSFER_CATS = ('内部划转', '转账/充值', '自转', '互转')

DEFAULT_PRESETS = [
    {
        'name': '本月大额',
        'criteria': {'type': 'expense', 'date_range': 'this_month', 'min_amount': 100.0},
        'is_pinned': 1,
        'order_num': 1
    },
    {
        'name': '待核对/未分类',
        'criteria': {'category': '其他'},
        'is_pinned': 1,
        'order_num': 2
    },
    {
        'name': '结余储蓄',
        'criteria': {'type': 'savings'},
        'is_pinned': 1,
        'order_num': 3
    },
    {
        'name': '本月支出',
        'criteria': {'type': 'expense', 'date_range': 'this_month'},
        'is_pinned': 0,
        'order_num': 4
    }
]


def resolve_date_range(
    date_range: Optional[str] = None,
    custom_start: Optional[str] = None,
    custom_end: Optional[str] = None
) -> Tuple[Optional[str], Optional[str]]:
    """
    将预设的时间范围名称转换为标准的 (start_date, end_date) ISO 格式元组
    """
    if not date_range or date_range in ('all', 'custom'):
        return (custom_start or None, custom_end or None)

    today = date.today()
    if date_range == 'this_month':
        start_date = today.replace(day=1)
        _, last_day = monthrange(today.year, today.month)
        end_date = today.replace(day=last_day)
        return (start_date.isoformat(), end_date.isoformat())

    if date_range == 'last_month':
        first_of_this_month = today.replace(day=1)
        last_of_last_month = first_of_this_month - timedelta(days=1)
        start_date = last_of_last_month.replace(day=1)
        return (start_date.isoformat(), last_of_last_month.isoformat())

    if date_range == 'last_30_days':
        start_date = today - timedelta(days=30)
        return (start_date.isoformat(), today.isoformat())

    if date_range == 'this_year':
        start_date = today.replace(month=1, day=1)
        end_date = today.replace(month=12, day=31)
        return (start_date.isoformat(), end_date.isoformat())

    return (custom_start or None, custom_end or None)


def build_filter_sql(
    user_id: Any,
    q: Optional[str] = None,
    tx_type: Optional[str] = None,
    category: Optional[str] = None,
    account_id: Optional[Union[int, str]] = None,
    start: Optional[str] = None,
    end: Optional[str] = None,
    date_range: Optional[str] = None,
    min_amount: Optional[Union[float, str]] = None,
    max_amount: Optional[Union[float, str]] = None,
    source: Optional[str] = None,
    table_prefix: str = ''
) -> Tuple[str, List[Any]]:
    """
    统一构建流水筛选的 WHERE 条件与参数列表
    """
    prefix = f"{table_prefix}." if table_prefix else ""
    where_clauses = [f"{prefix}user_id = ?", f"{prefix}deleted_at IS NULL"]
    params: List[Any] = [str(user_id)]

    # 1. 时间范围解析
    calc_start, calc_end = resolve_date_range(date_range, start, end)
    if calc_start:
        where_clauses.append(f"{prefix}date >= ?")
        params.append(calc_start)
    if calc_end:
        where_clauses.append(f"{prefix}date <= ?")
        params.append(calc_end)

    # 2. 交易类型解析（支持转账/互转语义过滤）
    cats_placeholder = ','.join('?' for _ in INTERNAL_TRANSFER_CATS)
    if tx_type == 'transfer':
        where_clauses.append(f"({prefix}type = 'transfer' OR {prefix}category IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'expense':
        where_clauses.append(f"{prefix}type = 'expense' AND ({prefix}category IS NULL OR {prefix}category NOT IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'income':
        where_clauses.append(f"{prefix}type = 'income' AND ({prefix}category IS NULL OR {prefix}category NOT IN ({cats_placeholder}))")
        params.extend(INTERNAL_TRANSFER_CATS)
    elif tx_type == 'savings':
        where_clauses.append(f"{prefix}type = 'savings'")
    elif tx_type and tx_type not in ('all', ''):
        where_clauses.append(f"{prefix}type = ?")
        params.append(tx_type)

    # 3. 分类过滤
    if category and category not in ('all', '全部', ''):
        where_clauses.append(f"{prefix}category = ?")
        params.append(category)

    # 4. 账户过滤
    if account_id:
        try:
            aid = int(account_id)
            if aid > 0:
                where_clauses.append(f"{prefix}account_id = ?")
                params.append(aid)
        except (ValueError, TypeError):
            pass

    # 5. 金额区间过滤
    if min_amount is not None and str(min_amount).strip() != '':
        try:
            min_val = float(min_amount)
            where_clauses.append(f"{prefix}amount >= ?")
            params.append(min_val)
        except (ValueError, TypeError):
            pass

    if max_amount is not None and str(max_amount).strip() != '':
        try:
            max_val = float(max_amount)
            where_clauses.append(f"{prefix}amount <= ?")
            params.append(max_val)
        except (ValueError, TypeError):
            pass

    # 6. 来源过滤
    if source and source not in ('all', ''):
        where_clauses.append(f"{prefix}source = ?")
        params.append(source)

    # 7. 全局模糊关键词搜索（匹配备注、分类、标签、来源）
    if q and q.strip():
        term = f"%{q.strip()}%"
        where_clauses.append(f"({prefix}note LIKE ? OR {prefix}tags LIKE ? OR {prefix}category LIKE ? OR {prefix}source LIKE ?)")
        params.extend([term, term, term, term])

    return " AND ".join(where_clauses), params


def get_saved_filters(user_id: Any, db: Any = None) -> List[Dict[str, Any]]:
    """
    获取指定用户的全部保存筛选预设。
    若用户尚无任何预设，自动为其初始化默认常用预设。
    """
    if not db or not user_id:
        return []

    uid = str(user_id)
    rows = db.execute(
        "SELECT * FROM saved_filters WHERE user_id = ? ORDER BY is_pinned DESC, order_num ASC, id ASC",
        (uid,)
    ).fetchall()

    if not rows:
        # 首次查询自举初始化系统预设
        now_str = datetime.now().isoformat()
        for p in DEFAULT_PRESETS:
            db.execute(
                """
                INSERT INTO saved_filters (user_id, name, criteria, is_pinned, order_num, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (uid, p['name'], json.dumps(p['criteria'], ensure_ascii=False), p['is_pinned'], p['order_num'], now_str, now_str)
            )
        db.commit()
        rows = db.execute(
            "SELECT * FROM saved_filters WHERE user_id = ? ORDER BY is_pinned DESC, order_num ASC, id ASC",
            (uid,)
        ).fetchall()

    result = []
    for r in rows:
        try:
            criteria_dict = json.loads(r['criteria']) if isinstance(r['criteria'], str) else (r['criteria'] or {})
        except Exception:
            criteria_dict = {}

        result.append({
            'id': r['id'],
            'name': r['name'],
            'criteria': criteria_dict,
            'is_pinned': bool(r['is_pinned']),
            'order_num': r['order_num'],
            'created_at': r['created_at'],
            'updated_at': r['updated_at']
        })
    return result


def create_saved_filter(
    user_id: Any,
    name: str,
    criteria: Union[Dict[str, Any], str],
    is_pinned: int = 0,
    order_num: int = 0,
    db: Any = None
) -> Dict[str, Any]:
    """创建新的筛选预设"""
    if not db or not user_id:
        return {'ok': False, 'message': 'Missing user or db connection'}

    name = (name or '').strip()
    if not name:
        return {'ok': False, 'message': 'Filter name cannot be empty'}

    if isinstance(criteria, dict):
        criteria_str = json.dumps(criteria, ensure_ascii=False)
        criteria_dict = criteria
    else:
        criteria_str = str(criteria).strip()
        try:
            criteria_dict = json.loads(criteria_str)
        except Exception:
            criteria_dict = {}

    now_str = datetime.now().isoformat()
    cursor = db.execute(
        """
        INSERT INTO saved_filters (user_id, name, criteria, is_pinned, order_num, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (str(user_id), name, criteria_str, 1 if is_pinned else 0, order_num, now_str, now_str)
    )
    db.commit()
    new_id = cursor.lastrowid

    bump_data_version(event_type='saved_filters_change', user_id=user_id, db=db)

    return {
        'ok': True,
        'filter': {
            'id': new_id,
            'name': name,
            'criteria': criteria_dict,
            'is_pinned': bool(is_pinned),
            'order_num': order_num,
            'created_at': now_str,
            'updated_at': now_str
        }
    }


def update_saved_filter(
    user_id: Any,
    filter_id: int,
    name: Optional[str] = None,
    criteria: Optional[Union[Dict[str, Any], str]] = None,
    is_pinned: Optional[Union[int, bool]] = None,
    order_num: Optional[int] = None,
    db: Any = None
) -> Dict[str, Any]:
    """更新已有筛选预设"""
    if not db or not user_id or not filter_id:
        return {'ok': False, 'message': 'Invalid parameters'}

    existing = db.execute(
        "SELECT * FROM saved_filters WHERE id = ? AND user_id = ?",
        (filter_id, str(user_id))
    ).fetchone()
    if not existing:
        return {'ok': False, 'message': 'Saved filter not found'}

    updates = []
    params = []
    now_str = datetime.now().isoformat()

    if name is not None:
        trimmed = name.strip()
        if not trimmed:
            return {'ok': False, 'message': 'Filter name cannot be empty'}
        updates.append("name = ?")
        params.append(trimmed)

    if criteria is not None:
        if isinstance(criteria, dict):
            c_str = json.dumps(criteria, ensure_ascii=False)
        else:
            c_str = str(criteria)
        updates.append("criteria = ?")
        params.append(c_str)

    if is_pinned is not None:
        updates.append("is_pinned = ?")
        params.append(1 if is_pinned else 0)

    if order_num is not None:
        updates.append("order_num = ?")
        params.append(int(order_num))

    if not updates:
        return {'ok': True, 'message': 'No changes made'}

    updates.append("updated_at = ?")
    params.append(now_str)

    params.extend([filter_id, str(user_id)])
    sql = f"UPDATE saved_filters SET {', '.join(updates)} WHERE id = ? AND user_id = ?"
    db.execute(sql, params)
    db.commit()

    bump_data_version(event_type='saved_filters_change', user_id=user_id, db=db)

    updated_row = db.execute(
        "SELECT * FROM saved_filters WHERE id = ? AND user_id = ?",
        (filter_id, str(user_id))
    ).fetchone()

    try:
        criteria_dict = json.loads(updated_row['criteria']) if isinstance(updated_row['criteria'], str) else (updated_row['criteria'] or {})
    except Exception:
        criteria_dict = {}

    return {
        'ok': True,
        'filter': {
            'id': updated_row['id'],
            'name': updated_row['name'],
            'criteria': criteria_dict,
            'is_pinned': bool(updated_row['is_pinned']),
            'order_num': updated_row['order_num'],
            'created_at': updated_row['created_at'],
            'updated_at': updated_row['updated_at']
        }
    }


def delete_saved_filter(user_id: Any, filter_id: int, db: Any = None) -> Dict[str, Any]:
    """删除指定的筛选预设"""
    if not db or not user_id or not filter_id:
        return {'ok': False, 'message': 'Invalid parameters'}

    cursor = db.execute(
        "DELETE FROM saved_filters WHERE id = ? AND user_id = ?",
        (filter_id, str(user_id))
    )
    db.commit()

    if cursor.rowcount == 0:
        return {'ok': False, 'message': 'Saved filter not found'}

    bump_data_version(event_type='saved_filters_change', user_id=user_id, db=db)
    return {'ok': True, 'message': 'Saved filter deleted'}
