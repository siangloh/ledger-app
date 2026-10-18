"""
services/reconciliation_service.py
账户对账与余额校准核心服务层 (Account Balance Calibration & Reconciliation Service)

职责：
1. 计算账户当前实时账面余额 (calculate_account_system_balance)
2. 获取账户对账详情与未对账交易清单 (get_account_reconciliation_details)
3. 勾选/切换单笔交易对账状态 (toggle_transaction_reconciled)
4. 执行对账校准并记录对账审计日志，支持一键自动生成平账调整交易 (reconcile_account)
"""

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from core.db import bump_data_version

logger = logging.getLogger(__name__)


def calculate_account_system_balance(account_id: int, user_id: Any, db: Any) -> Optional[Dict[str, Any]]:
    """
    计算账户当前的实时账面余额。
    显式依赖 (account_id, user_id, db)，杜绝上下文隐式依赖。
    """
    if not db or not user_id or not account_id:
        return None

    acc = db.execute(
        "SELECT id, name, type, credit_limit, currency, last_reconciled_at, reconciled_balance FROM accounts WHERE id = ? AND user_id = ?",
        (account_id, str(user_id))
    ).fetchone()
    if not acc:
        return None

    flow = db.execute('''
        SELECT
            COALESCE(SUM(CASE WHEN type = 'income' THEN amount ELSE 0.0 END), 0.0) AS income_sum,
            COALESCE(SUM(CASE WHEN type = 'expense' THEN amount ELSE 0.0 END), 0.0) AS expense_sum
        FROM transactions
        WHERE user_id = ? AND account_id = ? AND deleted_at IS NULL
    ''', (str(user_id), account_id)).fetchone()

    income_sum = round(float(flow['income_sum']) if flow else 0.0, 2)
    expense_sum = round(float(flow['expense_sum']) if flow else 0.0, 2)
    acc_type = acc['type']

    if acc_type == 'credit_card':
        # 信用卡：账面欠款 = 支出 - 还款收入
        system_balance = round(max(0.0, expense_sum - income_sum), 2)
    else:
        # 普通资产账户：账面结余 = 收入 - 支出
        system_balance = round(income_sum - expense_sum, 2)

    return {
        'account_id': account_id,
        'account_name': acc['name'],
        'account_type': acc_type,
        'currency': acc['currency'] or 'MYR',
        'income_sum': income_sum,
        'expense_sum': expense_sum,
        'system_balance': system_balance,
        'last_reconciled_at': acc['last_reconciled_at'],
        'reconciled_balance': acc['reconciled_balance']
    }


def get_account_reconciliation_details(
    account_id: int,
    user_id: Any,
    db: Any,
    limit: int = 50
) -> Dict[str, Any]:
    """
    获取指定账户的完整对账数据面板：
    包含：账面余额、未对账笔数、交易清单（可勾选对账）、历史对账审计日志。
    """
    calc = calculate_account_system_balance(account_id, user_id, db)
    if not calc:
        return {'ok': False, 'message': '账户不存在或无权访问'}

    # 查询交易明细
    tx_rows = db.execute('''
        SELECT id, date, type, category, amount, note, tags, is_reconciled, reconciled_at
        FROM transactions
        WHERE user_id = ? AND account_id = ? AND deleted_at IS NULL
        ORDER BY is_reconciled ASC, date DESC, id DESC
        LIMIT ?
    ''', (str(user_id), account_id, limit)).fetchall()

    transactions = []
    for r in tx_rows:
        transactions.append({
            'id': r['id'],
            'date': r['date'],
            'type': r['type'],
            'category': r['category'],
            'amount': round(float(r['amount']), 2),
            'note': r['note'] or '',
            'tags': r['tags'] or '',
            'is_reconciled': 1 if r['is_reconciled'] else 0,
            'reconciled_at': r['reconciled_at']
        })

    # 统计未对账数量
    unrec_row = db.execute('''
        SELECT COUNT(*) as cnt
        FROM transactions
        WHERE user_id = ? AND account_id = ? AND (is_reconciled = 0 OR is_reconciled IS NULL) AND deleted_at IS NULL
    ''', (str(user_id), account_id)).fetchone()
    unreconciled_count = unrec_row['cnt'] if unrec_row else 0

    # 查询最近对账审计历史
    hist_rows = db.execute('''
        SELECT id, statement_balance, system_balance, discrepancy, adjustment_tx_id, reconciled_at, note, created_at
        FROM account_reconciliations
        WHERE user_id = ? AND account_id = ?
        ORDER BY reconciled_at DESC, id DESC
        LIMIT 10
    ''', (str(user_id), account_id)).fetchall()

    history = []
    for h in hist_rows:
        history.append({
            'id': h['id'],
            'statement_balance': round(float(h['statement_balance']), 2),
            'system_balance': round(float(h['system_balance']), 2),
            'discrepancy': round(float(h['discrepancy']), 2),
            'adjustment_tx_id': h['adjustment_tx_id'],
            'reconciled_at': h['reconciled_at'],
            'note': h['note'] or '',
            'created_at': h['created_at']
        })

    return {
        'ok': True,
        'account': calc,
        'unreconciled_count': unreconciled_count,
        'transactions': transactions,
        'history': history
    }


def toggle_transaction_reconciled(
    tx_id: int,
    user_id: Any,
    db: Any,
    is_reconciled: Optional[bool] = None
) -> Dict[str, Any]:
    """
    切换单笔交易的对账状态（已对账 / 待对账）。
    """
    if not db or not user_id or not tx_id:
        return {'ok': False, 'message': '参数缺失'}

    tx = db.execute(
        "SELECT id, is_reconciled, account_id FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
        (tx_id, str(user_id))
    ).fetchone()
    if not tx:
        return {'ok': False, 'message': '交易不存在或已被删除'}

    if is_reconciled is None:
        target_state = 0 if tx['is_reconciled'] else 1
    else:
        target_state = 1 if is_reconciled else 0

    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S') if target_state == 1 else None

    db.execute(
        "UPDATE transactions SET is_reconciled = ?, reconciled_at = ? WHERE id = ? AND user_id = ?",
        (target_state, now_iso, tx_id, str(user_id))
    )
    db.commit()

    bump_data_version('update', {
        'id': tx_id,
        'field': 'is_reconciled',
        'is_reconciled': target_state
    }, user_id=str(user_id), db=db)

    return {
        'ok': True,
        'tx_id': tx_id,
        'account_id': tx['account_id'],
        'is_reconciled': target_state,
        'reconciled_at': now_iso
    }


def batch_set_transactions_reconciled(
    tx_ids: List[int],
    user_id: Any,
    db: Any,
    is_reconciled: bool = True
) -> Dict[str, Any]:
    """
    批量更新多笔交易的对账状态。
    """
    if not db or not user_id or not tx_ids:
        return {'ok': False, 'message': '参数缺失'}

    target_state = 1 if is_reconciled else 0
    now_iso = datetime.now().strftime('%Y-%m-%d %H:%M:%S') if target_state == 1 else None

    placeholders = ','.join(['?'] * len(tx_ids))
    params = [target_state, now_iso, str(user_id)] + [int(tid) for tid in tx_ids]

    db.execute(f'''
        UPDATE transactions
        SET is_reconciled = ?, reconciled_at = ?
        WHERE user_id = ? AND id IN ({placeholders}) AND deleted_at IS NULL
    ''', params)
    db.commit()

    bump_data_version('batch_update', {
        'count': len(tx_ids),
        'field': 'is_reconciled'
    }, user_id=str(user_id), db=db)

    return {'ok': True, 'count': len(tx_ids), 'is_reconciled': target_state}


def reconcile_account(
    account_id: int,
    user_id: Any,
    statement_balance: float,
    auto_adjust: bool,
    note: str,
    db: Any
) -> Dict[str, Any]:
    """
    执行账户对账校准：
    1. 校验账户存在性并获取实时账面余额
    2. 计算差额 discrepancy = statement_balance - system_balance
    3. 若选择 auto_adjust 且差额不为 0，自动创建带有 'reconciliation_adjustment' 标签的平账调整交易
    4. 将本账户所有未对账交易标记为已对账
    5. 更新 accounts 表的 last_reconciled_at 与 reconciled_balance
    6. 向 account_reconciliations 审计表写入快照记录
    7. bump_data_version 触发多端实时同步
    """
    if not db or not user_id or not account_id:
        return {'ok': False, 'message': '参数缺失'}

    calc = calculate_account_system_balance(account_id, user_id, db)
    if not calc:
        return {'ok': False, 'message': '账户不存在或无权访问'}

    system_balance = calc['system_balance']
    acc_type = calc['account_type']
    target_balance = round(float(statement_balance), 2)
    discrepancy = round(target_balance - system_balance, 2)

    adjustment_tx_id = None
    now_dt = datetime.now()
    now_iso = now_dt.strftime('%Y-%m-%d %H:%M:%S')
    today_str = now_dt.strftime('%Y-%m-%d')

    if abs(discrepancy) > 0.005 and auto_adjust:
        # 计算平账调整交易的类型与金额
        if acc_type == 'credit_card':
            # 信用卡欠款：账单欠款高于系统账面欠款 -> 产生未记账支出
            if discrepancy > 0:
                adj_type = 'expense'
                adj_amount = round(discrepancy, 2)
            else:
                adj_type = 'income'
                adj_amount = round(abs(discrepancy), 2)
        else:
            # 普通资产：对账单余额高于系统账面结余 -> 产生未记账收入/资金盘盈
            if discrepancy > 0:
                adj_type = 'income'
                adj_amount = round(discrepancy, 2)
            else:
                adj_type = 'expense'
                adj_amount = round(abs(discrepancy), 2)

        adj_note = f"对账平账校准 (校准前账面:{system_balance}, 对账单:{target_balance})"
        if note and note.strip():
            adj_note += f" - {note.strip()}"

        cur = db.execute('''
            INSERT INTO transactions (
                user_id, date, type, category, amount, note, source,
                created_at, tags, account_id, is_reconciled, reconciled_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        ''', (
            str(user_id), today_str, adj_type, '余额校准平账', adj_amount,
            adj_note, 'reconciliation', now_iso, 'reconciliation_adjustment',
            account_id, now_iso
        ))
        adjustment_tx_id = cur.lastrowid
        logger.info(
            "Created reconciliation adjustment transaction id=%s for account=%s (discrepancy=%s)",
            adjustment_tx_id, account_id, discrepancy
        )

    # 将该账户所有尚未对账的有效交易统一打上已对账标记
    db.execute('''
        UPDATE transactions
        SET is_reconciled = 1, reconciled_at = ?
        WHERE user_id = ? AND account_id = ? AND (is_reconciled = 0 OR is_reconciled IS NULL) AND deleted_at IS NULL
    ''', (now_iso, str(user_id), account_id))

    # 更新账户对账元数据
    db.execute('''
        UPDATE accounts
        SET last_reconciled_at = ?, reconciled_balance = ?
        WHERE id = ? AND user_id = ?
    ''', (now_iso, target_balance, account_id, str(user_id)))

    # 写入对账审计历史表
    audit_note = (note or '').strip()
    db.execute('''
        INSERT INTO account_reconciliations (
            user_id, account_id, statement_balance, system_balance,
            discrepancy, adjustment_tx_id, reconciled_at, note, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        str(user_id), account_id, target_balance, system_balance,
        discrepancy, adjustment_tx_id, now_iso, audit_note, now_iso
    ))

    db.commit()

    bump_data_version('reconcile_account', {
        'account_id': account_id,
        'statement_balance': target_balance,
        'discrepancy': discrepancy,
        'adjustment_tx_id': adjustment_tx_id
    }, user_id=str(user_id), db=db)

    # 重新核算最终余额
    updated_calc = calculate_account_system_balance(account_id, user_id, db)
    final_balance = updated_calc['system_balance'] if updated_calc else target_balance

    return {
        'ok': True,
        'account_id': account_id,
        'system_balance': system_balance,
        'statement_balance': target_balance,
        'discrepancy': discrepancy,
        'final_balance': final_balance,
        'adjustment_tx_id': adjustment_tx_id,
        'reconciled_at': now_iso,
        'message': '对账成功'
    }
