"""
services/subscription_matching_service.py
订阅与分期自动匹配核心服务层
- 支持自动将进账短信/银行通知与用户的活跃订阅 (subscriptions) 及分期计划 (installments) 智能配对
- 支持打算退订 (target_to_cancel) 扣款拦截高亮预警
- 支持确认匹配后自动递增分期已还期数 (paid_periods) 及自动推算更新订阅下期扣款日 (next_billing_date)
- 遵循薄控制器、显式依赖注入与 system_metadata 版本号同步
"""

import re
import logging
from datetime import datetime, date
from typing import Any, Dict, List, Optional

from core.db import bump_data_version
from subscription_tracker import BillingCycle, rollToNextBillingDate

logger = logging.getLogger(__name__)


def _normalize_match_str(text: str) -> str:
    """内部辅助工具：规范化待匹配文本（去除特殊标点符号并转小写）"""
    if not text:
        return ""
    # 将所有非字母数字字符替换为空格，统一压缩并小写
    cleaned = re.sub(r'[^\w\s]+', ' ', str(text).lower())
    return re.sub(r'\s+', ' ', cleaned).strip()


def match_subscription(
    user_id: Any,
    amount: float,
    note: str = "",
    merchant: str = "",
    raw_text: str = "",
    tx_date: Optional[str] = None,
    db: Any = None
) -> Optional[Dict[str, Any]]:
    """
    智能匹配单笔交易是否对应某个活跃的周期订阅
    """
    if not db or not user_id:
        return None

    try:
        amt_float = float(amount)
    except (ValueError, TypeError):
        return None

    # 查询当前用户所有处于活跃状态的订阅
    subs = db.execute(
        """
        SELECT id, name, category, billing_cycle, cost, currency,
               next_billing_date, auto_renew, target_to_cancel, anchor_day,
               payment_method_id, note
        FROM subscriptions
        WHERE user_id = ? AND status = 'ACTIVE'
        """,
        (str(user_id),)
    ).fetchall()

    if not subs:
        return None

    combined_text = f"{merchant} {note} {raw_text}".strip()
    norm_combined = _normalize_match_str(combined_text)

    best_match: Optional[Dict[str, Any]] = None
    best_score = 0

    for s in subs:
        sub_name = s['name']
        sub_cost = float(s['cost'] or 0.0)
        norm_sub_name = _normalize_match_str(sub_name)

        if not norm_sub_name:
            continue

        # 1. 名字相似度检查
        name_matched = False
        if norm_sub_name in norm_combined or any(part in norm_combined for part in norm_sub_name.split() if len(part) >= 3):
            name_matched = True
        elif norm_combined and norm_combined in norm_sub_name:
            name_matched = True

        if not name_matched:
            continue

        # 2. 金额近似度检查 (允许汇率浮动或小额手续费误差 <= 0.05，或在 5% 以内)
        amount_diff = abs(sub_cost - amt_float)
        amount_ratio = amount_diff / sub_cost if sub_cost > 0 else 1.0

        amount_matched = False
        if amount_diff <= 0.05:
            amount_matched = True
        elif amount_ratio <= 0.05:
            amount_matched = True

        # 计算综合匹配得分
        score = 0
        if norm_sub_name in norm_combined:
            score += 50
        else:
            score += 30

        if amount_diff <= 0.05:
            score += 50
        elif amount_matched:
            score += 30

        # 日期加分：若交易日期与下次扣款日期相同或相近 (±7 天)
        if tx_date and s['next_billing_date']:
            try:
                td = datetime.strptime(str(tx_date)[:10], "%Y-%m-%d").date()
                sd = datetime.strptime(str(s['next_billing_date'])[:10], "%Y-%m-%d").date()
                day_diff = abs((td - sd).days)
                if day_diff <= 3:
                    score += 20
                elif day_diff <= 7:
                    score += 10
            except Exception:
                pass

        if score > best_score and (amount_matched or score >= 70):
            best_score = score
            confidence = 'exact' if (amount_diff <= 0.05 and norm_sub_name in norm_combined) else 'high' if score >= 60 else 'medium'
            best_match = {
                'id': s['id'],
                'name': sub_name,
                'category': s['category'],
                'cost': sub_cost,
                'currency': s['currency'],
                'billing_cycle': s['billing_cycle'],
                'next_billing_date': s['next_billing_date'],
                'auto_renew': bool(s['auto_renew']),
                'target_to_cancel': bool(s['target_to_cancel']),
                'anchor_day': s['anchor_day'],
                'confidence': confidence,
                'score': score
            }

    return best_match


def match_installment(
    user_id: Any,
    amount: float,
    note: str = "",
    merchant: str = "",
    raw_text: str = "",
    tx_date: Optional[str] = None,
    db: Any = None
) -> Optional[Dict[str, Any]]:
    """
    智能匹配单笔交易是否对应某个正在进行中的分期负债 (Installments)
    """
    if not db or not user_id:
        return None

    try:
        amt_float = float(amount)
    except (ValueError, TypeError):
        return None

    inst_rows = db.execute(
        """
        SELECT id, account_id, title, total_amount, tenure_months,
               paid_periods, monthly_amount, first_due_date, status, last_synced_month
        FROM installments
        WHERE user_id = ? AND status = 'active'
        """,
        (str(user_id),)
    ).fetchall()

    if not inst_rows:
        return None

    combined_text = f"{merchant} {note} {raw_text}".strip()
    norm_combined = _normalize_match_str(combined_text)

    best_match: Optional[Dict[str, Any]] = None
    best_score = 0

    for r in inst_rows:
        inst_title = r['title']
        monthly_amt = float(r['monthly_amount'] or 0.0)
        norm_title = _normalize_match_str(inst_title)

        if not norm_title:
            continue

        # 1. 标题与关键词匹配
        title_matched = False
        if norm_title in norm_combined or any(part in norm_combined for part in norm_title.split() if len(part) >= 3):
            title_matched = True
        elif norm_combined and norm_combined in norm_title:
            title_matched = True
        elif any(k in norm_combined for k in ['installment', 'ezypay', 'spaylater', '分期', 'ansuran']):
            # 若含有明显分期特征词且金额吻合
            title_matched = True

        if not title_matched:
            continue

        # 2. 金额吻合检查
        amt_diff = abs(monthly_amt - amt_float)
        if amt_diff > 0.05 and abs(amt_diff / monthly_amt) > 0.02:
            continue

        score = 60
        if norm_title in norm_combined:
            score += 30
        if amt_diff <= 0.05:
            score += 20

        if score > best_score:
            best_score = score
            paid = int(r['paid_periods'] or 0)
            tenure = int(r['tenure_months'] or 1)
            next_period = min(tenure, paid + 1)
            best_match = {
                'id': r['id'],
                'title': inst_title,
                'total_amount': float(r['total_amount']),
                'monthly_amount': monthly_amt,
                'tenure_months': tenure,
                'paid_periods': paid,
                'next_period': next_period,
                'first_due_date': r['first_due_date'],
                'confidence': 'exact' if amt_diff <= 0.05 else 'high',
                'score': score
            }

    return best_match


def match_transaction(
    user_id: Any,
    amount: float,
    note: str = "",
    merchant: str = "",
    raw_text: str = "",
    tx_date: Optional[str] = None,
    db: Any = None
) -> Dict[str, Any]:
    """
    统一匹配交易，同时检测订阅和分期两个维度
    """
    sub_match = match_subscription(
        user_id=user_id,
        amount=amount,
        note=note,
        merchant=merchant,
        raw_text=raw_text,
        tx_date=tx_date,
        db=db
    )

    inst_match = match_installment(
        user_id=user_id,
        amount=amount,
        note=note,
        merchant=merchant,
        raw_text=raw_text,
        tx_date=tx_date,
        db=db
    )

    return {
        'matched': bool(sub_match or inst_match),
        'subscription': sub_match,
        'installment': inst_match
    }


def apply_subscription_match(
    user_id: Any,
    sub_id: int,
    tx_id: Optional[int] = None,
    auto_roll: bool = True,
    db: Any = None
) -> Dict[str, Any]:
    """
    确认匹配订阅：
    1. 自动将该订阅的 next_billing_date 顺延至下一计费周期（防时间衰退漂移）
    2. 若提供了 tx_id，则在交易备注中增加 [周期订阅: {name}] 追溯标签
    3. 同步推送 system_metadata 版本号，触发前端与伴侣端无缝刷新
    """
    if not db or not user_id or not sub_id:
        return {'ok': False, 'message': 'Invalid parameters'}

    sub_row = db.execute(
        "SELECT * FROM subscriptions WHERE id = ? AND user_id = ?",
        (sub_id, str(user_id))
    ).fetchone()

    if not sub_row:
        return {'ok': False, 'message': 'Subscription not found'}

    now_iso = datetime.now().isoformat()
    old_date_str = sub_row['next_billing_date']
    new_date_str = old_date_str

    if tx_id:
        from services.cashflow_analysis_service import record_source_payment
        try:
            fresh = record_source_payment(user_id, 'subscription', sub_row, old_date_str, tx_id, db)
        except ValueError as error:
            return {'ok': False, 'message': str(error)}
        if fresh in ('retry', 'partial'):
            return {'ok': True, 'subscription_id': sub_id, 'name': sub_row['name'],
                    'previous_billing_date': old_date_str, 'next_billing_date': old_date_str,
                    'new_next_billing_date': old_date_str, 'partial': fresh == 'partial',
                    'message': 'Partial payment recorded' if fresh == 'partial' else 'Payment already linked'}

    if auto_roll:
        try:
            curr_date = datetime.strptime(str(old_date_str)[:10], "%Y-%m-%d").date()
        except Exception:
            curr_date = date.today()

        cycle = BillingCycle(sub_row['billing_cycle'])
        anchor = sub_row['anchor_day'] or curr_date.day
        new_date = rollToNextBillingDate(curr_date, cycle, anchor)
        new_date_str = new_date.isoformat()

        db.execute(
            "UPDATE subscriptions SET next_billing_date = ?, updated_at = ? WHERE id = ? AND user_id = ?",
            (new_date_str, now_iso, sub_id, str(user_id))
        )
        db.commit()

    # 若绑定了交易记录，更新交易备注
    if tx_id:
        tx_row = db.execute(
            "SELECT note FROM transactions WHERE id = ? AND user_id = ?",
            (tx_id, str(user_id))
        ).fetchone()
        if tx_row:
            old_note = (tx_row['note'] or '').strip()
            sub_tag = f"[周期订阅: {sub_row['name']}]"
            if sub_tag not in old_note:
                new_note = f"{old_note} {sub_tag}".strip()
                db.execute(
                    "UPDATE transactions SET note = ? WHERE id = ? AND user_id = ?",
                    (new_note, tx_id, str(user_id))
                )
                db.commit()

    bump_data_version(
        event_type='subscription_matched',
        user_id=user_id,
        db=db
    )

    return {
        'ok': True,
        'message': f"已成功匹配订阅【{sub_row['name']}】并顺延至 {new_date_str}",
        'subscription_id': sub_id,
        'previous_billing_date': old_date_str,
        'next_billing_date': new_date_str,
        'new_next_billing_date': new_date_str,
        'name': sub_row['name']
    }


def apply_installment_match(
    user_id: Any,
    inst_id: int,
    tx_id: Optional[int] = None,
    db: Any = None
) -> Dict[str, Any]:
    """
    确认匹配分期：
    1. 递增 installments 表中该分期的 paid_periods (+1)
    2. 若 paid_periods 已达 tenure_months，自动将状态变更为 'completed'
    3. 更新 last_synced_month 为当月
    4. 若提供了 tx_id，在交易备注追加 [分期: {title} ({paid}/{tenure})]
    5. bump_data_version 触发全局同步
    """
    if not db or not user_id or not inst_id:
        return {'ok': False, 'message': 'Invalid parameters'}

    inst_row = db.execute(
        "SELECT * FROM installments WHERE id = ? AND user_id = ?",
        (inst_id, str(user_id))
    ).fetchone()

    if not inst_row:
        return {'ok': False, 'message': 'Installment not found'}

    current_paid = int(inst_row['paid_periods'] or 0)
    tenure = int(inst_row['tenure_months'] or 1)
    new_paid = min(tenure, current_paid + 1)
    new_status = 'completed' if new_paid >= tenure else 'active'
    now_month = date.today().strftime('%Y-%m')

    if tx_id:
        from services.cashflow_analysis_service import record_source_payment, _at_month
        first_due = date.fromisoformat(inst_row['first_due_date'][:10])
        due = _at_month(first_due, current_paid, first_due.day).isoformat()
        try:
            fresh = record_source_payment(user_id, 'installment', inst_row, due, tx_id, db)
        except ValueError as error:
            return {'ok': False, 'message': str(error)}
        if fresh in ('retry', 'partial'):
            return {'ok': True, 'installment_id': inst_id, 'paid_periods': current_paid,
                    'tenure_months': tenure, 'partial': fresh == 'partial',
                    'message': 'Partial payment recorded' if fresh == 'partial' else 'Payment already linked'}

    db.execute(
        """
        UPDATE installments
        SET paid_periods = ?, status = ?, last_synced_month = ?
        WHERE id = ? AND user_id = ?
        """,
        (new_paid, new_status, now_month, inst_id, str(user_id))
    )
    db.commit()

    if tx_id:
        tx_row = db.execute(
            "SELECT note FROM transactions WHERE id = ? AND user_id = ?",
            (tx_id, str(user_id))
        ).fetchone()
        if tx_row:
            old_note = (tx_row['note'] or '').strip()
            inst_tag = f"[分期: {inst_row['title']} ({new_paid}/{tenure})]"
            if inst_tag not in old_note:
                new_note = f"{old_note} {inst_tag}".strip()
                db.execute(
                    "UPDATE transactions SET note = ? WHERE id = ? AND user_id = ?",
                    (new_note, tx_id, str(user_id))
                )
                db.commit()

    bump_data_version(
        event_type='installment_matched',
        user_id=user_id,
        db=db
    )

    return {
        'ok': True,
        'message': f"已成功推进分期【{inst_row['title']}】至第 {new_paid}/{tenure} 期" + (" (已还清 🎉)" if new_status == 'completed' else ""),
        'installment_id': inst_id,
        'paid_periods': new_paid,
        'new_paid_periods': new_paid,
        'tenure_months': tenure,
        'status': new_status
    }


def scan_unmatched_transactions(
    user_id: Any,
    limit: int = 50,
    db: Any = None
) -> List[Dict[str, Any]]:
    """
    智能巡检扫描：检索用户近期支出流水，并与活跃订阅/分期进行候选匹配，便于批量核对或补齐关联
    """
    if not db or not user_id:
        return []

    recent_txs = db.execute(
        """
        SELECT id, date, amount, category, note, source, created_at
        FROM transactions
        WHERE user_id = ? AND (type = 'expense' OR type = '支出') AND deleted_at IS NULL
        ORDER BY date DESC, id DESC
        LIMIT ?
        """,
        (str(user_id), limit)
    ).fetchall()

    results: List[Dict[str, Any]] = []
    for tx in recent_txs:
        note_text = tx['note'] or ''
        # 如果已经标记了关联，跳过重复推荐
        if '[周期订阅:' in note_text or '[分期:' in note_text:
            continue

        match_res = match_transaction(
            user_id=user_id,
            amount=tx['amount'],
            note=note_text,
            tx_date=tx['date'],
            db=db
        )

        if match_res['matched']:
            results.append({
                'transaction': dict(tx),
                'subscription': match_res['subscription'],
                'installment': match_res['installment'],
                'matched_subscription': match_res['subscription'],
                'matched_installment': match_res['installment']
            })

    return results
