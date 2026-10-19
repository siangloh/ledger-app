import json
from datetime import datetime, date
import logging
from flask import Blueprint, request, jsonify, session

from core.extensions import csrf
from core.config import is_valid_api_key, get_user_id_from_api_key
from core.db import get_db, get_current_user_id, bump_data_version, get_categories
from core.utils import check_and_record_budget_alerts
from services.subscription_matching_service import (
    match_transaction,
    apply_subscription_match,
    apply_installment_match
)

logger = logging.getLogger(__name__)

pending_transactions_bp = Blueprint('pending_transactions', __name__)


def resolve_pending_request_user(db):
    user_id = session.get('user_id')
    if user_id:
        return user_id
    req_key = request.headers.get('X-API-KEY')
    data = request.get_json(silent=True) or request.form
    if not req_key and data:
        req_key = data.get('key')
    if not req_key:
        req_key = request.args.get('key')
    if req_key and is_valid_api_key(req_key, db=db):
        target_username = (data.get('username') if data else None) or request.args.get('username')
        if target_username:
            u_row = db.execute("SELECT id FROM users WHERE LOWER(username) = LOWER(?)", (target_username,)).fetchone()
            if u_row:
                return u_row['id']
        key_uid = get_user_id_from_api_key(req_key, db=db)
        if key_uid:
            return key_uid
    return get_current_user_id()


@pending_transactions_bp.route('/api/pending-transactions/summary', methods=['GET'], endpoint='get_pending_summary')
@csrf.exempt
def get_pending_summary():
    """轻量获取待处理数量与原因细分，供首页徽标与轮询使用"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
    rows = db.execute('''
        SELECT id, amount, review_reason, on_hold_reason, suspected_duplicate_id
        FROM pending_transactions
        WHERE user_id = ? AND status = 'on_hold'
    ''', (str(user_id),)).fetchall()

    breakdown = {
        'suspected_duplicate': 0,
        'unclear_category': 0,
        'unmatched_account': 0,
        'on_hold_transfer': 0
    }
    total_amt = 0.0
    for r in rows:
        total_amt += float(r['amount'] or 0.0)
        rr = r['review_reason'] or ''
        reason_text = (r['on_hold_reason'] or '').lower()
        if rr == 'suspected_duplicate' or r['suspected_duplicate_id'] or '重复' in reason_text:
            breakdown['suspected_duplicate'] += 1
        elif rr == 'unmatched_account' or '账户' in reason_text:
            breakdown['unmatched_account'] += 1
        elif rr == 'on_hold_transfer' or '转账' in reason_text or '充值' in reason_text:
            breakdown['on_hold_transfer'] += 1
        else:
            breakdown['unclear_category'] += 1

    return jsonify({
        'ok': True,
        'count': len(rows),
        'total': len(rows),
        'total_amount': round(total_amt, 2),
        'breakdown': breakdown
    })


@pending_transactions_bp.route('/api/pending-transactions', methods=['GET'], endpoint='get_pending_transactions')
@csrf.exempt
def get_pending_transactions():
    """获取当前用户所有待确认 (on_hold) 或指定状态的转账暂存列表，并附带疑似重复对比信息与可选账户"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
    status = request.args.get('status', 'on_hold')

    rows = db.execute('''
        SELECT id, user_id, date, type, amount, group_name, category, merchant, note, channel, raw_text,
               status, on_hold_reason, review_reason, suspected_duplicate_id, merged_into_id, account_id,
               created_at, updated_at
        FROM pending_transactions
        WHERE user_id = ? AND status = ?
        ORDER BY created_at DESC, id DESC
    ''', (str(user_id), status)).fetchall()

    items = []
    summary_breakdown = {
        'suspected_duplicate': 0,
        'unclear_category': 0,
        'unmatched_account': 0,
        'on_hold_transfer': 0
    }

    for r in rows:
        it = dict(r)
        # 规整并细化原因分类
        rr = it.get('review_reason') or ''
        reason_text = (it.get('on_hold_reason') or '').lower()
        if rr == 'suspected_duplicate' or it.get('suspected_duplicate_id') or '重复' in reason_text:
            it['effective_reason'] = 'suspected_duplicate'
            summary_breakdown['suspected_duplicate'] += 1
        elif rr == 'unmatched_account' or '账户' in reason_text:
            it['effective_reason'] = 'unmatched_account'
            summary_breakdown['unmatched_account'] += 1
        elif rr == 'on_hold_transfer' or '转账' in reason_text or '充值' in reason_text:
            it['effective_reason'] = 'on_hold_transfer'
            summary_breakdown['on_hold_transfer'] += 1
        else:
            it['effective_reason'] = 'unclear_category'
            summary_breakdown['unclear_category'] += 1

        # 若包含疑似重复 ID，查询已有主交易的详细信息进行并排对比
        dup_id = it.get('suspected_duplicate_id')
        if dup_id:
            dup_row = db.execute('''
                SELECT t.id, t.date, t.type, t.category, t.amount, t.note, t.account_id,
                       a.name as account_name
                FROM transactions t
                LEFT JOIN accounts a ON t.account_id = a.id
                WHERE t.id = ? AND t.user_id = ? AND t.deleted_at IS NULL
            ''', (dup_id, str(user_id))).fetchone()
            if dup_row:
                it['suspected_duplicate'] = dict(dup_row)
            else:
                it['suspected_duplicate'] = None
        else:
            it['suspected_duplicate'] = None

        # 智能匹配周期订阅与分期计划
        match_info = match_transaction(
            user_id=user_id,
            amount=float(it.get('amount') or 0.0),
            note=it.get('note') or '',
            merchant=it.get('merchant') or '',
            raw_text=it.get('raw_text') or '',
            tx_date=it.get('date'),
            db=db
        )
        it['matched_subscription'] = match_info.get('subscription')
        it['matched_installment'] = match_info.get('installment')

        items.append(it)

    # 附带当前活跃账户与可选分类，方便用户在待确认弹窗中直接挑选
    accounts_rows = db.execute('''
        SELECT id, name, type, currency FROM accounts
        WHERE user_id = ? AND is_active = 1
        ORDER BY id ASC
    ''', (str(user_id),)).fetchall()
    accounts = [dict(a) for a in accounts_rows]

    categories = {
        'expense': get_categories(db, 'expense', None, user_id),
        'income': get_categories(db, 'income', 'main', user_id)
    }

    return jsonify({
        'ok': True,
        'items': items,
        'count': len(items),
        'accounts': accounts,
        'categories': categories,
        'summary': {
            'total': len(items),
            'breakdown': summary_breakdown
        }
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/ignore', methods=['POST'], endpoint='ignore_pending_transaction')
@csrf.exempt
def ignore_pending_transaction(pending_id: int):
    """用户决定将此笔转账判定为本人自转/充值，标记为忽略，不计入日常收支"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
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
@csrf.exempt
def confirm_pending_transaction(pending_id: int):
    """用户确认此笔待办交易为真实外部收支，转入正式账本（D2: 原子认领防重入与重复计入）"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
    now = datetime.now().isoformat()

    row = db.execute(
        'SELECT * FROM pending_transactions WHERE id = ? AND user_id = ?',
        (pending_id, str(user_id))
    ).fetchone()

    if not row:
        return jsonify({'ok': False, 'message': '未找到该待处理交易记录'}), 404

    # 幂等返回：已确认的记录直接返回成功，杜绝网络重试或并发重复入账
    if row['status'] == 'confirmed':
        return jsonify({
            'ok': True,
            'message': '该交易此前已确认入账，系统已自动防止重复记账',
            'pending_id': pending_id,
            'already_confirmed': True
        }), 200

    if row['status'] != 'on_hold':
        return jsonify({'ok': False, 'message': f"操作冲突：该交易当前状态为【{row['status']}】，无法重复确认"}), 409

    # 原子更新状态为 'confirmed'，通过 rowcount 判断是否抢占成功（杜绝并发竞争）
    claim = db.execute(
        "UPDATE pending_transactions SET status = 'confirmed', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'on_hold'",
        (now, pending_id, str(user_id))
    )
    if claim.rowcount == 0:
        return jsonify({'ok': False, 'message': '操作冲突：该记录正在被处理或状态已改变'}), 409

    data = request.get_json(silent=True) or request.form.to_dict() or {}

    tx_category = (data.get('category') or row['category'] or '其他').strip()
    tx_note = (data.get('note') or row['note'] or row['merchant'] or '待确认交易').strip()
    tx_type = (data.get('type') or row['type'] or 'expense').strip()
    tx_date = (data.get('date') or row['date'] or date.today().isoformat()).strip()
    account_id = data.get('account_id') or row['account_id'] or None
    if account_id:
        try:
            account_id = int(account_id)
            # 校验账户归属当前用户
            acc_check = db.execute("SELECT id FROM accounts WHERE id = ? AND user_id = ?", (account_id, str(user_id))).fetchone()
            if not acc_check:
                account_id = None
        except (ValueError, TypeError):
            account_id = None

    tags = (data.get('tags') or '').strip()
    if row['suspected_duplicate_id']:
        tags = f"{tags} non_duplicate_verified".strip()

    chan = row['channel'] or 'generic'
    ref = (row['raw_text'] or '')[:16] if row['raw_text'] else None

    cur = db.execute('''
        INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, channel, source_ref, created_at, account_id, tags)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'notification', ?, ?, ?, ?, ?)
    ''', (
        str(user_id),
        tx_date,
        tx_type,
        row['group_name'],
        tx_category,
        row['amount'],
        tx_note,
        chan,
        ref,
        now,
        account_id,
        tags
    ))
    new_tx_id = cur.lastrowid
    db.commit()

    # 检查是否匹配订阅或分期，并自动递增期数/更新下期扣款日
    sub_id = data.get('sub_id')
    installment_id = data.get('installment_id')
    row_dict = dict(row)
    if not sub_id and not installment_id:
        match_info = match_transaction(
            user_id=user_id,
            amount=float(row_dict.get('amount') or 0.0),
            note=tx_note,
            merchant=row_dict.get('merchant') or '',
            raw_text=row_dict.get('raw_text') or '',
            tx_date=tx_date,
            db=db
        )
        if match_info.get('subscription') and match_info['subscription'].get('confidence') in ('exact', 'high'):
            sub_id = match_info['subscription']['id']
        elif match_info.get('installment') and match_info['installment'].get('confidence') in ('exact', 'high'):
            installment_id = match_info['installment']['id']

    if sub_id:
        try:
            apply_subscription_match(user_id=user_id, sub_id=int(sub_id), tx_id=new_tx_id, db=db)
        except Exception as e:
            logger.debug("Failed to apply subscription match on confirm: %s", e)
    elif installment_id:
        try:
            apply_installment_match(user_id=user_id, inst_id=int(installment_id), tx_id=new_tx_id, db=db)
        except Exception as e:
            logger.debug("Failed to apply installment match on confirm: %s", e)

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
        'message': f"已成功记入正式账本：{tx_note} ({tx_category})",
        'transaction_id': new_tx_id,
        'pending_id': pending_id
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/mark-not-duplicate', methods=['POST'], endpoint='mark_not_duplicate_pending_transaction')
@csrf.exempt
def mark_not_duplicate_pending_transaction(pending_id: int):
    """用户确认此疑似重复交易实为独立发生（非重复入账），转入正式账本"""
    return confirm_pending_transaction(pending_id)


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/merge', methods=['POST'], endpoint='merge_pending_transaction')
@csrf.exempt
def merge_pending_transaction(pending_id: int):
    """用户确认此待办记录确为重复交易，合并至主交易中，保留完整原始报文/渠道追溯，绝不重复计入支出"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
    now = datetime.now().isoformat()

    row = db.execute(
        'SELECT * FROM pending_transactions WHERE id = ? AND user_id = ?',
        (pending_id, str(user_id))
    ).fetchone()

    if not row:
        return jsonify({'ok': False, 'message': '未找到该待处理交易记录'}), 404

    # 幂等返回：已合并的记录直接返回成功
    if row['status'] == 'merged':
        return jsonify({
            'ok': True,
            'message': '该交易此前已完成合并，系统已自动防止重复记账',
            'pending_id': pending_id,
            'merged_into_id': row['merged_into_id'],
            'already_merged': True
        }), 200

    if row['status'] != 'on_hold':
        return jsonify({'ok': False, 'message': f"操作冲突：该交易当前状态为【{row['status']}】，无法重复合并"}), 409

    data = request.get_json(silent=True) or request.form.to_dict() or {}
    target_id = data.get('target_tx_id') or row['suspected_duplicate_id']
    if not target_id:
        return jsonify({'ok': False, 'message': '未指定要合并的目标交易 ID'}), 400

    try:
        target_id = int(target_id)
    except (ValueError, TypeError):
        return jsonify({'ok': False, 'message': '目标交易 ID 无效'}), 400

    target_tx = db.execute(
        'SELECT id, note, merged_info, account_id FROM transactions WHERE id = ? AND user_id = ?',
        (target_id, str(user_id))
    ).fetchone()

    if not target_tx:
        return jsonify({'ok': False, 'message': f'未找到要合并的目标交易 #{target_id}'}), 404

    # 原子更新状态为 'merged'，避免并发竞争
    claim = db.execute(
        "UPDATE pending_transactions SET status = 'merged', merged_into_id = ?, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'on_hold'",
        (target_id, now, pending_id, str(user_id))
    )
    if claim.rowcount == 0:
        return jsonify({'ok': False, 'message': '操作冲突：该记录正在被处理或状态已改变'}), 409

    # 将合并的源记录报文、渠道等追溯信息写入目标交易的 merged_info 列表
    existing_merged_info = []
    if target_tx['merged_info']:
        try:
            existing_merged_info = json.loads(target_tx['merged_info'])
            if not isinstance(existing_merged_info, list):
                existing_merged_info = [existing_merged_info]
        except Exception:
            existing_merged_info = []

    merged_entry = {
        'merged_at': now,
        'pending_id': pending_id,
        'amount': float(row['amount']),
        'channel': row['channel'] or 'generic',
        'source': 'notification',
        'source_ref': (row['raw_text'] or '')[:16] if row['raw_text'] else None,
        'raw_text': row['raw_text'],
        'note': row['note'] or row['merchant'],
        'reason': row['on_hold_reason'] or row['review_reason']
    }
    existing_merged_info.append(merged_entry)

    # 如果主交易未指定账户，而待处理记录选定了账户，补充账户信息
    account_update_sql = ""
    account_param = []
    new_account_id = data.get('account_id') or row['account_id']
    if new_account_id and not target_tx['account_id']:
        try:
            acc_id_int = int(new_account_id)
            acc_check = db.execute("SELECT id FROM accounts WHERE id = ? AND user_id = ?", (acc_id_int, str(user_id))).fetchone()
            if acc_check:
                account_update_sql = ", account_id = ?"
                account_param = [acc_id_int]
        except (ValueError, TypeError):
            pass

    db.execute(
        f"UPDATE transactions SET merged_info = ?, updated_at = ?, last_edit_source = 'merge_pending' {account_update_sql} WHERE id = ? AND user_id = ?",
        [json.dumps(existing_merged_info, ensure_ascii=False), now] + account_param + [target_id, str(user_id)]
    )
    db.commit()

    bump_data_version('pending_transaction_resolved', {
        'action': 'merged',
        'pending_id': pending_id,
        'merged_into_id': target_id,
        'user_id': user_id
    })

    return jsonify({
        'ok': True,
        'message': f'已成功合并重复记录至交易 #{target_id}，完整保留原始报文追溯，未重复产生支出',
        'pending_id': pending_id,
        'merged_into_id': target_id
    })


@pending_transactions_bp.route('/api/pending-transactions/<int:pending_id>/transfer', methods=['POST'], endpoint='transfer_pending_transaction')
@csrf.exempt
def transfer_pending_transaction(pending_id: int):
    """用户将此笔转账记录为多账户之间的内部资金调拨"""
    db = get_db()
    user_id = resolve_pending_request_user(db)
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
