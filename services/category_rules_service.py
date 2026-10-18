"""
services/category_rules_service.py
商户分类规则与纠正记忆核心服务层 (Merchant Category Rules & Auto-Classification Service)

职责：
1. 维护用户定义的商户规则库与自动纠正记忆 (CRUD)
2. 多级智能匹配算法 (精确匹配 -> 关键字包含模糊匹配 -> 正则表达式匹配)
3. 规则命中统计与自学习频次累计 (apply_count, updated_at)
4. 一键追溯应用规则到历史未分类/相关交易流水
"""

import logging
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

from core.db import bump_data_version

logger = logging.getLogger(__name__)


def get_category_rules(
    user_id: Any,
    db: Any,
    search: Optional[str] = None,
    page: int = 1,
    limit: int = 50
) -> Dict[str, Any]:
    """
    分页获取用户的分类规则列表，支持商户关键字与分类检索。
    """
    if not db or not user_id:
        return {'ok': False, 'message': '参数缺失', 'rules': [], 'total_count': 0}

    offset = max(0, (page - 1) * limit)
    params: List[Any] = [str(user_id)]
    where_sql = "WHERE user_id = ?"

    if search and search.strip():
        where_sql += " AND (merchant_note LIKE ? OR category LIKE ?)"
        s_term = f"%{search.strip()}%"
        params.extend([s_term, s_term])

    count_row = db.execute(f"SELECT COUNT(*) as cnt FROM merchant_category_overrides {where_sql}", params).fetchone()
    total_count = count_row['cnt'] if count_row else 0

    query_sql = f"""
        SELECT user_id, merchant_note, category,
               COALESCE(match_type, 'contains') as match_type,
               COALESCE(apply_count, 0) as apply_count,
               COALESCE(is_active, 1) as is_active,
               created_at, updated_at
        FROM merchant_category_overrides
        {where_sql}
        ORDER BY is_active DESC, updated_at DESC, LENGTH(merchant_note) DESC
        LIMIT ? OFFSET ?
    """
    rows = db.execute(query_sql, params + [limit, offset]).fetchall()

    rules = []
    for r in rows:
        rules.append({
            'merchant_pattern': r['merchant_note'],
            'category': r['category'],
            'match_type': r['match_type'],
            'apply_count': r['apply_count'],
            'is_active': 1 if r['is_active'] else 0,
            'created_at': r['created_at'],
            'updated_at': r['updated_at']
        })

    return {
        'ok': True,
        'rules': rules,
        'total_count': total_count,
        'page': page,
        'limit': limit,
        'has_more': (offset + len(rules)) < total_count
    }


def create_or_update_rule(
    user_id: Any,
    merchant_pattern: str,
    category: str,
    match_type: str = 'contains',
    is_active: int = 1,
    db: Any = None
) -> Dict[str, Any]:
    """
    创建或更新一条商户分类规则。
    """
    if not db or not user_id:
        return {'ok': False, 'message': '未授权或数据库未就绪'}

    pattern = (merchant_pattern or '').strip()
    cat = (category or '').strip()
    if not pattern:
        return {'ok': False, 'message': '商户匹配模式不能为空'}
    if not cat:
        return {'ok': False, 'message': '目标分类不能为空'}

    valid_types = ('exact', 'contains', 'regex')
    m_type = match_type.strip().lower() if match_type else 'contains'
    if m_type not in valid_types:
        m_type = 'contains'

    if m_type == 'regex':
        try:
            re.compile(pattern)
        except re.error as e:
            return {'ok': False, 'message': f'无效的正则表达式: {e}'}

    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    db.execute('''
        INSERT INTO merchant_category_overrides (
            user_id, merchant_note, category, match_type, is_active, updated_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, merchant_note) DO UPDATE SET
            category = excluded.category,
            match_type = excluded.match_type,
            is_active = excluded.is_active,
            updated_at = excluded.updated_at
    ''', (str(user_id), pattern, cat, m_type, 1 if is_active else 0, now_iso, now_iso))
    db.commit()

    bump_data_version('category_rule_update', {
        'merchant_pattern': pattern,
        'category': cat,
        'match_type': m_type
    }, user_id=str(user_id), db=db)

    return {
        'ok': True,
        'merchant_pattern': pattern,
        'category': cat,
        'match_type': m_type,
        'is_active': 1 if is_active else 0,
        'updated_at': now_iso
    }


def delete_rule(
    user_id: Any,
    merchant_pattern: str,
    db: Any
) -> Dict[str, Any]:
    """
    删除一条商户分类规则。
    """
    if not db or not user_id or not merchant_pattern:
        return {'ok': False, 'message': '参数缺失'}

    pattern = merchant_pattern.strip()
    db.execute(
        "DELETE FROM merchant_category_overrides WHERE user_id = ? AND merchant_note = ?",
        (str(user_id), pattern)
    )
    db.commit()

    bump_data_version('category_rule_delete', {'merchant_pattern': pattern}, user_id=str(user_id), db=db)
    return {'ok': True, 'message': '规则已成功删除'}


def toggle_rule_status(
    user_id: Any,
    merchant_pattern: str,
    db: Any
) -> Dict[str, Any]:
    """
    启用/停用一条商户分类规则。
    """
    if not db or not user_id or not merchant_pattern:
        return {'ok': False, 'message': '参数缺失'}

    pattern = merchant_pattern.strip()
    row = db.execute(
        "SELECT is_active FROM merchant_category_overrides WHERE user_id = ? AND merchant_note = ?",
        (str(user_id), pattern)
    ).fetchone()
    if not row:
        return {'ok': False, 'message': '规则不存在'}

    new_state = 0 if row['is_active'] else 1
    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    db.execute(
        "UPDATE merchant_category_overrides SET is_active = ?, updated_at = ? WHERE user_id = ? AND merchant_note = ?",
        (new_state, now_iso, str(user_id), pattern)
    )
    db.commit()

    bump_data_version('category_rule_toggle', {'merchant_pattern': pattern, 'is_active': new_state}, user_id=str(user_id), db=db)
    return {'ok': True, 'is_active': new_state}


def match_category_for_note(
    note: str,
    user_id: Any,
    db: Any
) -> Optional[str]:
    """
    核心多级匹配引擎：输入备注字符串，在活跃规则库中寻觅最契合的分类。
    匹配优先级：
    1. 精确匹配 (exact match)
    2. 包含关键字模糊匹配 (contains match，按模式长度降序，长词优先)
    3. 正则表达式匹配 (regex match)
    """
    if not db or not user_id or not note:
        return None

    clean_note = note.strip()
    if not clean_note:
        return None

    # 查询该用户所有已启用的规则
    rules = db.execute('''
        SELECT merchant_note, category, COALESCE(match_type, 'contains') as match_type, apply_count
        FROM merchant_category_overrides
        WHERE user_id = ? AND COALESCE(is_active, 1) = 1
        ORDER BY LENGTH(merchant_note) DESC, apply_count DESC
    ''', (str(user_id),)).fetchall()

    if not rules:
        return None

    matched_rule: Optional[Dict[str, Any]] = None

    # Step 1: 精确匹配检查 (优先)
    clean_lower = clean_note.lower()
    for r in rules:
        m_type = r['match_type']
        p = r['merchant_note'].strip()
        if m_type == 'exact' and p.lower() == clean_lower:
            matched_rule = dict(r)
            break

    # Step 2: 关键字包含模糊匹配 (长模式优先匹配，如 'GrabFood' 优先于 'Grab')
    if not matched_rule:
        for r in rules:
            m_type = r['match_type']
            p = r['merchant_note'].strip()
            if m_type in ('contains', '') and p:
                if p.lower() in clean_lower:
                    matched_rule = dict(r)
                    break

    # Step 3: 正则表达式匹配
    if not matched_rule:
        for r in rules:
            m_type = r['match_type']
            p = r['merchant_note'].strip()
            if m_type == 'regex' and p:
                try:
                    if re.search(p, clean_note, re.IGNORECASE):
                        matched_rule = dict(r)
                        break
                except re.error:
                    continue

    if matched_rule:
        cat = matched_rule['category']
        pattern = matched_rule['merchant_note']
        # 异步非阻塞累加命中频次
        try:
            db.execute('''
                UPDATE merchant_category_overrides
                SET apply_count = COALESCE(apply_count, 0) + 1
                WHERE user_id = ? AND merchant_note = ?
            ''', (str(user_id), pattern))
            db.commit()
        except Exception as e:
            logger.debug("Failed to increment rule apply_count: %s", e)
        return cat

    return None


def apply_rule_to_history(
    user_id: Any,
    merchant_pattern: str,
    category: str,
    match_type: str = 'contains',
    only_uncategorized: bool = False,
    db: Any = None
) -> Dict[str, Any]:
    """
    一键追溯将单条规则应用到历史交易流水中。
    :param only_uncategorized: 若为 True，仅更新分类为“未分类”、“其他”或空的交易；若为 False，更新所有匹配记录。
    """
    if not db or not user_id or not merchant_pattern or not category:
        return {'ok': False, 'message': '参数缺失'}

    pattern = merchant_pattern.strip()
    cat = category.strip()
    m_type = (match_type or 'contains').strip().lower()

    where_conds = ["user_id = ?", "deleted_at IS NULL"]
    params: List[Any] = [str(user_id)]

    if only_uncategorized:
        where_conds.append("(category IS NULL OR category = '' OR category IN ('其他', '未分类', '待分类', 'Other', 'Uncategorized'))")

    where_clause = " AND ".join(where_conds)
    rows = db.execute(f"SELECT id, note, category FROM transactions WHERE {where_clause}", params).fetchall()

    affected_ids = []
    p_lower = pattern.lower()

    if m_type == 'exact':
        for r in rows:
            note = (r['note'] or '').strip()
            if note.lower() == p_lower and r['category'] != cat:
                affected_ids.append(r['id'])
    elif m_type == 'regex':
        try:
            regex = re.compile(pattern, re.IGNORECASE)
            for r in rows:
                note = (r['note'] or '').strip()
                if regex.search(note) and r['category'] != cat:
                    affected_ids.append(r['id'])
        except re.error as e:
            return {'ok': False, 'message': f'无效正则表达式: {e}'}
    else:  # 'contains'
        for r in rows:
            note = (r['note'] or '').strip()
            if p_lower in note.lower() and r['category'] != cat:
                affected_ids.append(r['id'])

    if not affected_ids:
        return {'ok': True, 'count': 0, 'message': '历史流水中未检索到需要更新的记录'}

    # 批量更新命中的交易记录
    placeholders = ','.join(['?'] * len(affected_ids))
    update_params = [cat] + affected_ids
    db.execute(f'''
        UPDATE transactions
        SET category = ?
        WHERE id IN ({placeholders})
    ''', update_params)

    # 累加命中计数
    db.execute('''
        UPDATE merchant_category_overrides
        SET apply_count = COALESCE(apply_count, 0) + ?
        WHERE user_id = ? AND merchant_note = ?
    ''', (len(affected_ids), str(user_id), pattern))

    db.commit()

    bump_data_version('batch_categorize_history', {
        'merchant_pattern': pattern,
        'category': cat,
        'count': len(affected_ids)
    }, user_id=str(user_id), db=db)

    return {
        'ok': True,
        'count': len(affected_ids),
        'category': cat,
        'message': f'成功对 {len(affected_ids)} 笔历史交易重分类为「{cat}」'
    }


def apply_all_rules_to_history(
    user_id: Any,
    only_uncategorized: bool = True,
    db: Any = None
) -> Dict[str, Any]:
    """
    全量规则重筛：将用户库中所有启用的商户分类规则按优先级依次应用到历史交易中。
    """
    if not db or not user_id:
        return {'ok': False, 'message': '参数缺失'}

    rules = db.execute('''
        SELECT merchant_note, category, COALESCE(match_type, 'contains') as match_type
        FROM merchant_category_overrides
        WHERE user_id = ? AND COALESCE(is_active, 1) = 1
        ORDER BY LENGTH(merchant_note) DESC, apply_count DESC
    ''', (str(user_id),)).fetchall()

    if not rules:
        return {'ok': True, 'count': 0, 'message': '暂无启用的分类规则'}

    total_updated = 0
    for r in rules:
        res = apply_rule_to_history(
            user_id=user_id,
            merchant_pattern=r['merchant_note'],
            category=r['category'],
            match_type=r['match_type'],
            only_uncategorized=only_uncategorized,
            db=db
        )
        if res.get('ok'):
            total_updated += res.get('count', 0)

    return {
        'ok': True,
        'count': total_updated,
        'message': f'全量规则追溯完成，共更新 {total_updated} 笔交易分类'
    }
