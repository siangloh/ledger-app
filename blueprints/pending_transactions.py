from datetime import datetime, date
import logging
from flask import Blueprint, request, jsonify

from core.db import get_db, get_current_user_id, bump_data_version
from core.utils import check_and_record_budget_alerts

logger = logging.getLogger(__name__)

pending_transactions_bp = Blueprint('pending_transactions', __name__)


@pending_transactions_bp.route('/api/pending-transactions', methods=['GET'], endpoint='get_pending_transactions')
def get_pending_transactions():
    """获取当前用户所有待确认 (on_hold) 或指定状态的转账暂存列表"""
    user_id = get_current_user_id()
    db = get_db()
    status = request.args.get('status', 'on_hold')

    rows = db.execute('''
        SELECT id, user_id, date, type, amount, group_name, category, merchant, note, channel, raw_text, status, on_hold_reason, created_at, updated_at
        FROM pending_transactions
        WHERE user_id = ? AND status = ?
        ORDER BY created_at DESC, id DESC
    ''', (str(user_id), status)).fetchall()

    items = [dict(r) for r in rows]
    return jsonify({
        'ok': True,
        'items': items,
        'count': len(items)
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/ignore', methods=['POST'], endpoint='ignore_pending_transaction')
def ignore_pending_transaction(pending_id: int):
    """用户决定将此笔转账判定为本人自转/充值，标记为忽略，不计入日常收支"""
    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()

    row = db.execute(
        'SELECT * FROM pending_transactions WHERE id = ? AND user_id = ?',
        (pending_id, str(user_id))
    ).fetchone()

    if not row:
        return jsonify({'ok': False, 'message': '未找到该待确认转账记录'}), 404

    if row['status'] == 'ignored':
        return jsonify({'ok': True, 'message': '已标记为本人自转/内部充值，已忽略不计入日常支出', 'pending_id': pending_id}), 200

    if row['status'] != 'on_hold':
        return jsonify({'ok': False, 'message': f"操作冲突：该转账当前状态为【{row['status']}】，无法重复操作"}), 409

    claim = db.execute(
        "UPDATE pending_transactions SET status = 'ignored', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'on_hold'",
        (now, pending_id, str(user_id))
    )
    if claim.rowcount == 0:
        return jsonify({'ok': False, 'message': '操作冲突：该转账状态已被修改'}), 409

    db.commit()

    bump_data_version('pending_transaction_resolved', {
        'action': 'ignored',
        'pending_id': pending_id,
        'user_id': user_id
    })

    return jsonify({
        'ok': True,
        'message': '已标记为本人自转/内部充值，已忽略不计入日常支出',
        'pending_id': pending_id
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/confirm', methods=['POST'], endpoint='confirm_pending_transaction')
def confirm_pending_transaction(pending_id: int):
    """用户确认此笔转账为真实外部支出/收入，转入正式账本（D2: 原子认领防重入）"""
    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()

    row = db.execute(
        'SELECT * FROM pending_transactions WHERE id = ? AND user_id = ?',
        (pending_id, str(user_id))
    ).fetchone()

    if not row:
        return jsonify({'ok': False, 'message': '未找到该待确认转账记录'}), 404

    # 幂等返回：已确认的记录直接返回成功，杜绝网络重试或并发重复入账
    if row['status'] == 'confirmed':
        return jsonify({
            'ok': True,
            'message': '该转账此前已确认入账，系统已自动防止重复记账',
            'pending_id': pending_id,
            'already_confirmed': True
        }), 200

    if row['status'] != 'on_hold':
        return jsonify({'ok': False, 'message': f"操作冲突：该转账当前状态为【{row['status']}】，无法重复确认"}), 409

    # 原子更新状态为 'confirmed'，通过 rowcount 判断是否抢占成功（杜绝并发竞争）
    claim = db.execute(
        "UPDATE pending_transactions SET status = 'confirmed', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'on_hold'",
        (now, pending_id, str(user_id))
    )
    if claim.rowcount == 0:
        return jsonify({'ok': False, 'message': '操作冲突：该转账正在被处理或状态已改变'}), 409

    data = request.get_json(silent=True) or request.form.to_dict() or {}

    tx_category = (data.get('category') or row['category'] or '其他').strip()
    tx_note = (data.get('note') or row['note'] or row['merchant'] or '银行转账').strip()
    tx_type = (data.get('type') or row['type'] or 'expense').strip()
    tx_date = (data.get('date') or row['date'] or date.today().isoformat()).strip()
    account_id = data.get('account_id') or None
    if account_id:
        try:
            account_id = int(account_id)
        except (ValueError, TypeError):
            account_id = None

    cur = db.execute('''
        INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at, account_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'auto_track_confirmed', ?, ?)
    ''', (
        str(user_id),
        tx_date,
        tx_type,
        row['group_name'],
        tx_category,
        row['amount'],
        tx_note,
        now,
        account_id
    ))
    new_tx_id = cur.lastrowid
    db.commit()

    bump_data_version('add', {
        'id': new_tx_id,
        'note': tx_note,
        'amount': row['amount'],
        'category': tx_category,
        'type': tx_type,
        'date': tx_date,
        'source': 'auto_track_confirmed',
        'user_id': user_id
    })
    bump_data_version('pending_transaction_resolved', {
        'action': 'confirmed',
        'pending_id': pending_id,
        'transaction_id': new_tx_id,
        'user_id': user_id
    })

    if tx_type == 'expense':
        try:
            check_and_record_budget_alerts(db, str(user_id), tx_category, tx_date[:7])
        except Exception as e:
            logger.debug("check_and_record_budget_alerts on confirm skipped: %s", e)

    return jsonify({
        'ok': True,
        'message': f"已成功记入账本：{tx_note} ({tx_category})",
        'transaction_id': new_tx_id,
        'pending_id': pending_id
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/transfer', methods=['POST'], endpoint='transfer_pending_transaction')
def transfer_pending_transaction(pending_id: int):
    """用户将此笔转账记录为多账户之间的内部资金调拨"""
    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()

    row = db.execute(
        'SELECT * FROM pending_transactions WHERE id = ? AND user_id = ?',
        (pending_id, str(user_id))
    ).fetchone()

    if not row:
        return jsonify({'ok': False, 'message': '未找到该待确认转账记录'}), 404

    if row['status'] == 'transferred':
        return jsonify({'ok': True, 'message': '已成功记录为账户互转，不计入外部支出', 'pending_id': pending_id}), 200

    if row['status'] != 'on_hold':
        return jsonify({'ok': False, 'message': f"操作冲突：该转账当前状态为【{row['status']}】，无法重复操作"}), 409

    claim = db.execute(
        "UPDATE pending_transactions SET status = 'transferred', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'on_hold'",
        (now, pending_id, str(user_id))
    )
    if claim.rowcount == 0:
        return jsonify({'ok': False, 'message': '操作冲突：该转账正在被处理或状态已改变'}), 409

    data = request.get_json(silent=True) or request.form.to_dict() or {}
    from_acc = data.get('from_account_name') or row['channel'] or '银行账户'
    to_acc = data.get('to_account_name') or row['merchant'] or '目标账户'

    db.commit()

    bump_data_version('pending_transaction_resolved', {
        'action': 'transferred',
        'pending_id': pending_id,
        'user_id': user_id
    })

    return jsonify({
        'ok': True,
        'message': f"已成功记录为账户互转 [{from_acc} ➡️ {to_acc}]，不计入外部支出",
        'pending_id': pending_id
    })
