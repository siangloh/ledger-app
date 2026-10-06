"""
Merchant service for query suggestions, frequency ranking, and smart auto-complete.
Adheres to Thin Controller / Explicit Parameters principles (no implicit Flask context).
"""
import logging
from typing import List, Dict, Any, Optional

logger = logging.getLogger(__name__)


def get_merchant_suggestions(
    user_id: int,
    query: str = "",
    tx_type: Optional[str] = None,
    limit: int = 10,
    db: Any = None
) -> List[Dict[str, Any]]:
    """
    Retrieve smart merchant suggestions based on transaction history and remembered category overrides.

    :param user_id: ID of the current user.
    :param query: Search keyword (matches note/merchant or category).
    :param tx_type: Optional transaction type ('expense' or 'income').
    :param limit: Maximum number of suggestions to return (1-30).
    :param db: Active database connection.
    :return: List of merchant suggestion dicts sorted by frequency and recency.
    """
    if not db or not user_id:
        return []

    limit = max(1, min(int(limit or 10), 30))
    clean_query = (query or "").strip()

    try:
        # 1. 查询该用户交易记录中历史商户聚合数据，并关联 merchant_category_overrides 保证获取最新的覆盖分类
        base_sql = '''
            SELECT 
                TRIM(t1.note) AS name,
                COALESCE(mco.category, t1.category) AS category,
                t1.type AS type,
                COUNT(*) AS count,
                ROUND(AVG(t1.amount), 2) AS avg_amount,
                (SELECT amount FROM transactions t2 
                 WHERE t2.user_id = ? AND TRIM(t2.note) = TRIM(t1.note) 
                 ORDER BY t2.date DESC, t2.id DESC LIMIT 1) AS last_amount,
                (SELECT account_id FROM transactions t3 
                 WHERE t3.user_id = ? AND TRIM(t3.note) = TRIM(t1.note) 
                 ORDER BY t3.date DESC, t3.id DESC LIMIT 1) AS last_account_id,
                MAX(t1.date) AS last_date
            FROM transactions t1
            LEFT JOIN merchant_category_overrides mco ON mco.merchant_note = TRIM(t1.note)
            WHERE t1.user_id = ? 
              AND t1.note IS NOT NULL 
              AND TRIM(t1.note) != '' 
              AND TRIM(t1.note) NOT LIKE '[%'
        '''
        params = [user_id, user_id, user_id]

        if clean_query:
            base_sql += ' AND (TRIM(t1.note) LIKE ? OR COALESCE(mco.category, t1.category) LIKE ?)'
            like_pat = f"%{clean_query}%"
            params.extend([like_pat, like_pat])

        if tx_type:
            base_sql += ' AND t1.type = ?'
            params.append(tx_type)

        base_sql += '''
            GROUP BY TRIM(t1.note), COALESCE(mco.category, t1.category), t1.type
            ORDER BY count DESC, last_date DESC
            LIMIT ?
        '''
        params.append(limit)

        rows = db.execute(base_sql, tuple(params)).fetchall()

        results: List[Dict[str, Any]] = []
        seen_names = set()

        for r in rows:
            name = (r['name'] or '').strip()
            if not name or name in seen_names:
                continue
            seen_names.add(name)

            results.append({
                'name': name,
                'category': r['category'] or '其他',
                'type': r['type'] or 'expense',
                'count': int(r['count'] or 1),
                'avg_amount': float(r['avg_amount'] or 0.0),
                'last_amount': float(r['last_amount'] or 0.0),
                'last_account_id': r['last_account_id'],
                'last_date': r['last_date'] or ''
            })

        # 2. 如果存在 merchant_category_overrides 中记忆的商户且尚未包含在结果中，若匹配 query 则作为候选补入
        if clean_query and len(results) < limit:
            remaining = limit - len(results)
            override_sql = '''
                SELECT merchant_note, category, updated_at
                FROM merchant_category_overrides
                WHERE merchant_note LIKE ? OR category LIKE ?
                ORDER BY updated_at DESC
                LIMIT ?
            '''
            like_pat = f"%{clean_query}%"
            override_rows = db.execute(override_sql, (like_pat, like_pat, remaining * 2)).fetchall()
            for orow in override_rows:
                oname = (orow['merchant_note'] or '').strip()
                if not oname or oname in seen_names:
                    continue
                seen_names.add(oname)
                results.append({
                    'name': oname,
                    'category': orow['category'] or '其他',
                    'type': tx_type or 'expense',
                    'count': 1,
                    'avg_amount': 0.0,
                    'last_amount': 0.0,
                    'last_account_id': None,
                    'last_date': (orow['updated_at'] or '')[:10]
                })
                if len(results) >= limit:
                    break

        return results
    except Exception as e:
        logger.error("Error retrieving merchant suggestions for user %s: %s", user_id, e, exc_info=True)
        return []
