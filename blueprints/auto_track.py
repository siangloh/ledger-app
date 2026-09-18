import os
import re
import hashlib
import logging
from datetime import datetime, date
from urllib.parse import unquote
from flask import Blueprint, request, jsonify, render_template, session, send_from_directory, current_app

logger = logging.getLogger(__name__)

from core.db import get_db, bump_data_version, get_current_user_id, seed_learning_samples, get_user_settings
from core.config import (
    is_valid_api_key,
    get_auto_track_key,
    get_active_llm_provider,
    AUTO_TRACK_DEBUG_LOG
)
from services.notification_service import parse_auto_track_notification
from services.ai_service import classify_notification_with_llm
from core.utils import money_filter, check_and_record_budget_alerts
from core.extensions import csrf
from core.i18n import t

auto_track_bp = Blueprint('auto_track', __name__)


@auto_track_bp.route('/api/auto-track', methods=['GET', 'POST'], endpoint='api_auto_track')
@csrf.exempt
def api_auto_track():
    req_key = request.headers.get('X-API-KEY')
    data = {}
    if request.is_json:
        data = request.get_json(silent=True) or {}
        if not req_key:
            req_key = data.get('key')
    else:
        req_key = req_key or request.form.get('key')

    if not req_key:
        req_key = request.args.get('key')

    raw_payload = request.get_data(as_text=True)
    text = request.args.get('text') or ""

    if not text and request.is_json:
        try:
            data = request.get_json(silent=True) or {}
            if isinstance(data, dict):
                text = data.get('text') or data.get('body') or data.get('message') or ""
        except Exception:
            pass

    if not text:
        text = request.form.get('text') or request.form.get('body') or request.form.get('message') or ""

    if not text and raw_payload and len(raw_payload.strip()) > 3:
        if raw_payload.strip().startswith('{'):
            try:
                m = re.search(r'"(?:text|body|message)"\s*:\s*"(.*?)"(?:\s*,\s*"|\s*})', raw_payload, re.DOTALL)
                if m:
                    text = m.group(1).replace('\\"', '"').replace('\\n', '\n')
            except Exception:
                pass
        if not text:
            text = raw_payload

    text = (text or "").strip()
    if text.startswith('text='):
        text = unquote(text[5:]).strip()

    print(f"[AUTO_TRACK] Request from {request.remote_addr}, Method={request.method}, KeyProvided={'YES' if req_key else 'NO'}, ContentType={request.content_type}")
    print(f"[AUTO_TRACK] Extracted text: {repr(text[:120])}")

    if not is_valid_api_key(req_key):
        is_legacy_companion = False
        if not req_key and text:
            parsed_preview = parse_auto_track_notification(text)
            if parsed_preview:
                if parsed_preview.get('is_internal_transfer'):
                    return jsonify({
                        'ok': True,
                        'verdict': 'ignored_internal_transfer',
                        'message': parsed_preview.get('reason'),
                        'raw_text': text
                    }), 200
                if parsed_preview.get('is_promo'):
                    return jsonify({
                        'ok': False,
                        'verdict': 'rejected_promo',
                        'message': '通知被识别为营销推广活动或非动账通知，已自动忽略入账',
                        'raw_text': text
                    }), 200
                if parsed_preview.get('amount'):
                    ua = request.headers.get('User-Agent', '')
                    if 'Dalvik' in ua or 'Android' in ua or 'Ledger' in ua or not ua:
                        is_legacy_companion = True
                        print(f"[AUTO_TRACK] Allowing legacy companion notification without key (Verified transaction: RM {parsed_preview.get('amount')})")

        if not is_legacy_companion:
            print("[AUTO_TRACK] Rejected: Invalid API Key")
            return jsonify({'ok': False, 'message': 'API Key 无效或未在服务器配置，拒绝访问'}), 401

    if not text or text == "None" or text == "null":
        return jsonify({
            'ok': False,
            'message': '未收到有效的通知文本内容（若为手动测试，请确保当前通知栏存在真实的扣款通知）'
        }), 400

    parsed = parse_auto_track_notification(text)
    if AUTO_TRACK_DEBUG_LOG:
        print(f"[AUTO_TRACK DEBUG] Parsed result: {parsed}")

    if parsed and parsed.get('is_promo'):
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Rejected as promo by blacklist: {parsed.get('reason')}")
        return jsonify({
            'ok': False,
            'verdict': 'rejected_promo',
            'message': '通知被识别为营销推广活动或非动账通知，已自动忽略入账',
            'reason': parsed.get('reason'),
            'raw_text': text
        }), 200

    if parsed and parsed.get('is_internal_transfer'):
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Ignored internal transfer: {parsed.get('reason')}")
        return jsonify({
            'ok': True,
            'verdict': 'ignored_internal_transfer',
            'message': parsed.get('reason') or '钱包内部资金划转/充值，已自动忽略',
            'raw_text': text
        }), 200

    if not parsed or not parsed.get('amount'):
        if AUTO_TRACK_DEBUG_LOG:
            print("[AUTO_TRACK DEBUG] Failed to parse amount! Returning 422")
        return jsonify({
            'ok': False,
            'message': '未能从通知中提取出有效金额或商户信息',
            'raw_text': text
        }), 422

    merchant_note = (parsed.get('note') or '').strip()
    if merchant_note:
        db = get_db()
        override = db.execute(
            'SELECT category FROM merchant_category_overrides WHERE merchant_note = ?',
            (merchant_note,)
        ).fetchone()
        if override and override['category']:
            parsed['category'] = override['category']
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[AUTO_TRACK DEBUG] Applied remembered merchant override: '{merchant_note}' -> '{override['category']}'")

    is_real, llm_data = classify_notification_with_llm(text)

    if not is_real:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Notification rejected by Phase-2 LLM as promotional: {repr(text)}")
        return jsonify({
            'ok': False,
            'verdict': 'rejected_promo',
            'message': '通知被识别为营销推广或非真实交易，已忽略入账',
            'parsed': parsed,
            'raw_text': text
        }), 200

    if llm_data and isinstance(llm_data, dict):
        label_type = llm_data.get('label_type')
        if label_type == 'income_transfer':
            parsed['type'] = 'income'
            if not parsed.get('group_name'):
                parsed['group_name'] = 'main' if any(k in text.lower() for k in ['salary', 'payroll', '工资', '薪资', '薪水']) else 'side'
        elif label_type in ('expense', 'expense_transfer'):
            parsed['type'] = 'expense'
            parsed['group_name'] = None

        if parsed.get('category') == '其他' and llm_data.get('category') and llm_data['category'] != '其他':
            parsed['category'] = str(llm_data['category']).strip()
        if parsed.get('note') in ('自动追踪消费', '自动追踪入账') and llm_data.get('merchant'):
            parsed['note'] = str(llm_data['merchant']).strip()

    db = get_db()
    now = datetime.now().isoformat()
    target_user_id = data.get('user_id') or request.args.get('user_id')
    raw_target_username = str(data.get('username') or request.args.get('username') or '').strip()
    if raw_target_username and not target_user_id:
        u_row = db.execute("SELECT id, username FROM users WHERE LOWER(username) = LOWER(?)", (raw_target_username,)).fetchone()
        if u_row:
            target_user_id = u_row['id']
            logger.info("[AUTO_TRACK] Request bound to username '%s' (user_id: %s)", u_row['username'], target_user_id)
        else:
            logger.warning("[AUTO_TRACK] Username '%s' not found in database! Falling back to admin.", raw_target_username)

    if not target_user_id:
        logger.info("[AUTO_TRACK] No user specified in notification request. Falling back to admin/primary user.")
        admin_row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
        if admin_row:
            target_user_id = admin_row['id']
        else:
            first_row = db.execute("SELECT id FROM users ORDER BY created_at ASC LIMIT 1").fetchone()
            target_user_id = first_row['id'] if first_row else None

    # ---------------------------------------------------------
    # 幂等防重门禁 (Idempotency & Deduplication Guard)
    # ---------------------------------------------------------
    content_hash = hashlib.sha256(text.strip().encode('utf-8')).hexdigest()

    try:
        db.execute('''
            CREATE TABLE IF NOT EXISTS processed_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT,
                content_hash TEXT NOT NULL,
                raw_text TEXT,
                amount REAL,
                transaction_id INTEGER,
                created_at TEXT NOT NULL
            )
        ''')
    except Exception as e:
        logger.debug("Table check processed_notifications: %s", e)

    # 1. 检查 7 天内是否已成功处理过完全相同的通知原文 (应对网络重试或离线队列重放)
    existing_notif = db.execute('''
        SELECT id, transaction_id, created_at FROM processed_notifications
        WHERE user_id = ? AND content_hash = ?
        ORDER BY id DESC LIMIT 1
    ''', (target_user_id, content_hash)).fetchone()

    if existing_notif:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[AUTO_TRACK DEBUG] Duplicate notification rejected via content_hash: {content_hash}")
        return jsonify({
            'ok': True,
            'verdict': 'duplicate_ignored',
            'message': '检测到完全相同的通知此前已成功处理，已自动忽略重复入账',
            'transaction_id': existing_notif['transaction_id'],
            'notification_title': '重复通知已忽略 ℹ️',
            'notification_body': f"通知【{parsed.get('note') or '交易'}】此前已入账，系统已自动防止重复记账",
            'raw_text': text,
            'parsed': parsed
        }), 200

    # 2. 检查同用户在 2 小时内由 auto_track 创建的相同金额、分类与备注的交易
    recent_dup_tx = db.execute('''
        SELECT id, created_at FROM transactions
        WHERE user_id = ? AND type = ? AND amount = ? AND note = ? AND date = ? AND source = 'auto_track'
        ORDER BY id DESC LIMIT 1
    ''', (target_user_id, parsed['type'], parsed['amount'], parsed['note'], parsed['date'])).fetchone()

    if recent_dup_tx:
        is_recent_dup = False
        try:
            created_dt = datetime.fromisoformat(recent_dup_tx['created_at'])
            if abs((datetime.now() - created_dt).total_seconds()) < 7200:
                is_recent_dup = True
        except Exception:
            is_recent_dup = True

        if is_recent_dup:
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[AUTO_TRACK DEBUG] Duplicate transaction rejected within 2h: {recent_dup_tx['id']}")
            try:
                db.execute('''
                    INSERT INTO processed_notifications (user_id, content_hash, raw_text, amount, transaction_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (target_user_id, content_hash, text, parsed['amount'], recent_dup_tx['id'], now))
                db.commit()
            except Exception as e:
                logger.debug("Failed to record processed_notification on recent dup: %s", e)

            return jsonify({
                'ok': True,
                'verdict': 'duplicate_ignored',
                'message': '短时间内检测到相同交易已入账，已自动忽略重复记账',
                'transaction_id': recent_dup_tx['id'],
                'notification_title': '重复通知已忽略 ℹ️',
                'notification_body': f"短时间内检测到相同的【{parsed['note']} {money_filter(parsed['amount'])}】已入账，已自动忽略",
                'raw_text': text,
                'parsed': parsed
            }), 200

    # 0. 智能退款冲减原预扣支出
    if parsed.get('is_refund'):
        matched_expense = None
        merchant_search = (parsed.get('note') or '').strip()
        if merchant_search and merchant_search not in ('自动追踪消费', '自动追踪入账'):
            matched_expense = db.execute('''
                SELECT id, date, category, amount, note 
                FROM transactions 
                WHERE user_id = ? AND type = 'expense' 
                  AND (note LIKE ? OR ? LIKE '%' || note || '%' OR (category = '交通' AND ? = '交通'))
                ORDER BY date DESC, created_at DESC, id DESC LIMIT 1
            ''', (target_user_id, f"%{merchant_search}%", merchant_search, parsed.get('category'))).fetchone()

        if not matched_expense:
            matched_expense = db.execute('''
                SELECT id, date, category, amount, note 
                FROM transactions 
                WHERE user_id = ? AND type = 'expense' 
                ORDER BY date DESC, created_at DESC, id DESC LIMIT 1
            ''', (target_user_id,)).fetchone()

        if matched_expense:
            old_amount = float(matched_expense['amount'])
            refund_amount = float(parsed['amount'])
            new_amount = max(0.0, round(old_amount - refund_amount, 2))

            tag = f"[已扣减退款 {money_filter(refund_amount)}]"
            old_note = (matched_expense['note'] or '').strip()
            new_note = f"{old_note} {tag}".strip()

            db.execute('UPDATE transactions SET amount = ?, note = ? WHERE id = ?', (new_amount, new_note, matched_expense['id']))
            db.commit()

            bump_data_version('transaction_offset', {
                'offset_expense_id': matched_expense['id'],
                'original_amount': old_amount,
                'new_amount': new_amount,
                'offset_amount': refund_amount,
                'note': new_note,
                'category': matched_expense['category'],
                'user_id': target_user_id
            })

            try:
                db.execute('''
                    INSERT INTO processed_notifications (user_id, content_hash, raw_text, amount, transaction_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (target_user_id, content_hash, text, refund_amount, matched_expense['id'], now))
                db.commit()
            except Exception as e:
                logger.debug("Failed to record processed_notification on refund: %s", e)

            notif_title = "加油/消费退款已冲减 ⛽"
            notif_body = f"收到退款 {money_filter(refund_amount)}，已自动从原【{matched_expense['category']}】支出中扣除（由 {money_filter(old_amount)} 变更为 {money_filter(new_amount)}）"
            return jsonify({
                'ok': True,
                'verdict': 'refund_offset_success',
                'message': f"收到退款 {money_filter(refund_amount)}，已自动冲减原支出【{matched_expense['category']}】（现为 {money_filter(new_amount)}）！",
                'offset_expense_id': matched_expense['id'],
                'original_amount': old_amount,
                'new_amount': new_amount,
                'offset_amount': refund_amount,
                'notification_title': notif_title,
                'notification_body': notif_body,
                'parsed': parsed
            }), 200

    # 1. 智能朋友还款冲抵支出 (支持时间窗口限定、自己转账自转排除与品类安全门禁)
    user_settings = get_user_settings(target_user_id, db=db)
    offset_window_minutes = int(user_settings.get('repayment_offset_window_minutes', 120))

    # 排除自己转账给自己的关键词 (Self-Transfer / Own Account)
    SELF_TRANSFER_KEYWORDS = [
        'own account', 'transfer to own', 'self transfer', 'to own', 'from own',
        'myself', 'transfer to self', '本人', '自己账户', '自己', '本人账户',
        '自转', '互转', '内部转账', '自己名下', '自有账户', '同一人'
    ]
    raw_text_lower = text.lower()
    parsed_note_lower = (parsed.get('note') or '').lower()
    parsed_merchant_lower = (parsed.get('merchant') or '').lower()

    is_self_transfer = any(
        k in raw_text_lower or k in parsed_note_lower or k in parsed_merchant_lower
        for k in SELF_TRANSFER_KEYWORDS
    )

    # 进一步核对用户已注册的自有银行/账户名称 (如 Maybank, CIMB, Touch 'n Go 等)
    if not is_self_transfer and target_user_id:
        try:
            acc_rows = db.execute("SELECT name FROM accounts WHERE user_id = ?", (target_user_id,)).fetchall()
            user_acc_names = [r['name'].strip().lower() for r in acc_rows if r['name'] and len(r['name'].strip()) >= 2]
            u_row = db.execute("SELECT username FROM users WHERE id = ?", (target_user_id,)).fetchone()
            if u_row and u_row['username'] and len(u_row['username'].strip()) >= 2:
                user_acc_names.append(u_row['username'].strip().lower())

            for aname in user_acc_names:
                if aname in raw_text_lower or aname in parsed_note_lower or aname in parsed_merchant_lower:
                    if any(prefix in raw_text_lower for prefix in [f"from {aname}", f"to {aname}", f"via {aname}", f"dari {aname}"]) or aname == parsed_merchant_lower:
                        is_self_transfer = True
                        break
        except Exception as e:
            logger.debug("Failed to check user accounts for self-transfer: %s", e)

    is_repayment = (
        offset_window_minutes > 0
        and not is_self_transfer
        and parsed['type'] == 'income'
        and not parsed.get('is_internal_transfer')
        and not parsed.get('is_refund')
        and any(k in raw_text_lower for k in [
            'duitnow transfer', 'transfer from', 'transferred from', 'received from', 'received',
            '转入', '收到转账', '转账给您', '付款给您', '还款', '还钱'
        ])
        and not any(k in raw_text_lower for k in ['salary', 'payroll', '工资', '薪资', '薪水'])
    )

    if is_repayment:
        # 非日常分摊消费分类（如房租、房贷、车贷、分期、贷款、理财、投资、储蓄等）严格不予自动冲减
        NON_SPLITTABLE_CATEGORIES = {
            '房租', '房贷', '车贷', '分期', '贷款', '保险', '理财', '投资', '储蓄', '定期存款',
            '应急基金', '心愿基金', '信用卡还款', '学费', '税务', '罚单',
            'rent', 'mortgage', 'loan', 'installment', 'insurance', 'investment',
            'savings', 'tax', 'credit card'
        }

        last_expense = db.execute('''
            SELECT id, date, category, amount, note, created_at 
            FROM transactions 
            WHERE user_id = ? AND type = 'expense' 
            ORDER BY date DESC, created_at DESC, id DESC LIMIT 1
        ''', (target_user_id,)).fetchone()

        if last_expense:
            old_amount = float(last_expense['amount'])
            offset_amount = float(parsed['amount'])
            exp_cat = (last_expense['category'] or '').strip().lower()

            # 1. 品类校验：固定大额非分摊支出跳过冲抵
            if any(nc in exp_cat for nc in NON_SPLITTABLE_CATEGORIES):
                last_expense = None
            # 2. 金额校验：还款金额不能超过原始支出整单金额 (允许 0.05 元计算误差)
            elif offset_amount > (old_amount + 0.05):
                last_expense = None
            # 3. 时间窗口限制 ("within 那个时间点")
            else:
                is_within_window = False
                exp_date = str(last_expense['date'] or '')
                current_date = str(parsed.get('date') or date.today().isoformat())

                created_at_val = last_expense['created_at']
                if created_at_val:
                    try:
                        exp_created_str = str(created_at_val).replace('T', ' ')
                        if '.' in exp_created_str:
                            exp_created_str = exp_created_str.split('.')[0]
                        exp_dt = datetime.strptime(exp_created_str, '%Y-%m-%d %H:%M:%S')
                        now_dt = datetime.now()
                        diff_minutes = (now_dt - exp_dt).total_seconds() / 60.0
                        if -5 <= diff_minutes <= offset_window_minutes:
                            is_within_window = True
                    except Exception as e:
                        logger.debug("Failed to parse created_at for window check: %s", e)

                if not is_within_window and exp_date == current_date and offset_window_minutes >= 720:
                    is_within_window = True

                if not is_within_window:
                    last_expense = None

        if last_expense:
            old_amount = float(last_expense['amount'])
            offset_amount = float(parsed['amount'])
            new_amount = max(0.0, round(old_amount - offset_amount, 2))

            tag = f"[收到还款冲减 {money_filter(offset_amount)}]"
            old_note = (last_expense['note'] or '').strip()
            new_note = f"{old_note} {tag}".strip()

            db.execute('UPDATE transactions SET amount = ?, note = ? WHERE id = ?', (new_amount, new_note, last_expense['id']))
            db.commit()

            bump_data_version('transaction_offset', {
                'offset_expense_id': last_expense['id'],
                'original_amount': old_amount,
                'new_amount': new_amount,
                'offset_amount': offset_amount,
                'note': new_note,
                'category': last_expense['category'],
                'user_id': target_user_id
            })

            try:
                db.execute('''
                    INSERT INTO processed_notifications (user_id, content_hash, raw_text, amount, transaction_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (target_user_id, content_hash, text, offset_amount, last_expense['id'], now))
                db.commit()
            except Exception as e:
                logger.debug("Failed to record processed_notification on repayment: %s", e)

            notif_title = "已自动冲抵支出 💸"
            notif_body = f"收到还款 {money_filter(offset_amount)}，已自动冲减上一笔【{last_expense['category']}】支出（由 {money_filter(old_amount)} 扣减为 {money_filter(new_amount)}）"
            return jsonify({
                'ok': True,
                'verdict': 'offset_success',
                'message': f"收到朋友还款 {money_filter(offset_amount)}，已自动从上一笔支出【{last_expense['category']}】中扣除（现为 {money_filter(new_amount)}）！",
                'offset_expense_id': last_expense['id'],
                'original_amount': old_amount,
                'new_amount': new_amount,
                'offset_amount': offset_amount,
                'notification_title': notif_title,
                'notification_body': notif_body,
                'parsed': parsed
            }), 200

    cur = db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        (
            target_user_id,
            parsed['date'],
            parsed['type'],
            parsed['group_name'],
            parsed['category'],
            parsed['amount'],
            parsed['note'],
            'auto_track',
            now
        )
    )
    try:
        db.execute('''
            INSERT INTO processed_notifications (user_id, content_hash, raw_text, amount, transaction_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (target_user_id, content_hash, text, parsed['amount'], cur.lastrowid, now))
    except Exception as e:
        logger.debug("Failed to record processed_notification on insert: %s", e)

    db.commit()
    bump_data_version('auto_track', {
        'id': cur.lastrowid,
        'note': parsed['note'],
        'amount': parsed['amount'],
        'category': parsed['category'],
        'type': parsed['type'],
        'date': parsed['date'],
        'source': 'auto_track',
        'user_id': target_user_id
    })

    type_text = '支出' if parsed['type'] == 'expense' else '收入' if parsed['type'] == 'income' else '储蓄'
    note_str = f" ({parsed['note']})" if parsed['note'] else ""
    notification_title = "自动记账成功 💸"
    notification_body = f"已自动记入【{type_text} · {parsed['category']}】{money_filter(parsed['amount'])}{note_str}"

    budget_alert = None
    if parsed['type'] == 'expense':
        budget_alert = check_and_record_budget_alerts(db, target_user_id, parsed['category'], parsed['date'][:7])

    return jsonify({
        'ok': True,
        'verdict': 'accepted',
        'message': f"成功自动记账：{parsed['note']} {money_filter(parsed['amount'])} ({parsed['category']})",
        'transaction_id': cur.lastrowid,
        'parsed': parsed,
        'notification_title': notification_title,
        'notification_body': notification_body,
        'budget_alert': budget_alert
    }), 201


@auto_track_bp.route('/api/categories', methods=['GET'], endpoint='api_get_categories')
@csrf.exempt
def api_get_categories():
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key 无效'}), 401

    db = get_db()
    user_id = get_current_user_id()
    target_username = request.args.get('username')
    if target_username:
        u_row = db.execute("SELECT id FROM users WHERE username = ?", (target_username,)).fetchone()
        if u_row:
            user_id = u_row['id']

    rows = db.execute('SELECT id, name, type, group_name FROM categories WHERE user_id = ? ORDER BY type, id', (user_id,)).fetchall()
    categories = [{'id': r['id'], 'name': r['name'], 'type': r['type'], 'group_name': r['group_name']} for r in rows]
    return jsonify({'ok': True, 'categories': categories})


@auto_track_bp.route('/api/transactions/sync', methods=['POST'], endpoint='api_sync_transactions')
@csrf.exempt
def api_sync_transactions():
    req_key = request.headers.get('X-API-KEY')
    if not is_valid_api_key(req_key):
        return jsonify({'ok': False, 'message': 'API Key 无效'}), 401

    payload = request.get_json(silent=True) or {}
    txs = payload.get('transactions', [])
    if not txs:
        return jsonify({'ok': True, 'synced_count': 0, 'synced_ids': []})

    db = get_db()
    user_id = get_current_user_id()
    target_username = payload.get('username') or request.args.get('username')
    if target_username:
        u_row = db.execute("SELECT id FROM users WHERE username = ?", (target_username,)).fetchone()
        if u_row:
            user_id = u_row['id']

    now = datetime.now().isoformat()
    synced_ids = []
    for item in txs:
        try:
            db.execute(
                'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
                (
                    user_id,
                    item.get('date') or date.today().isoformat(),
                    item.get('type') or 'expense',
                    item.get('group_name') or 'personal',
                    item.get('category') or '其他',
                    float(item.get('amount') or 0.0),
                    item.get('note') or '离线录入',
                    item.get('source') or 'offline_sync',
                    now
                )
            )
            local_id = item.get('local_id') or item.get('id')
            if local_id is not None:
                synced_ids.append(local_id)
        except Exception as e:
            print(f"[SYNC ERROR] Failed to insert offline transaction: {e}")

    db.commit()
    return jsonify({'ok': True, 'synced_count': len(synced_ids), 'synced_ids': synced_ids})


@auto_track_bp.route('/download/apk', endpoint='download_apk')
def download_apk():
    root = current_app.root_path
    download_dir = os.path.join(root, 'static', 'download')
    return send_from_directory(download_dir, 'ledger-app.apk', as_attachment=True, download_name='我的账本.apk')


@auto_track_bp.route('/auto-track', endpoint='auto_track_page')
def auto_track_page():
    scheme = request.headers.get('X-Forwarded-Proto', request.scheme)
    if 'onrender.com' in request.host:
        scheme = 'https'
    base_url = f"{scheme}://{request.host}".rstrip('/')
    api_key = get_auto_track_key()
    current_username = session.get('username') or 'admin'
    webhook_url = f"{base_url}/api/auto-track?username={current_username}"
    webhook_url_with_key = f"{base_url}/api/auto-track?key={api_key}&username={current_username}"
    db = get_db()
    samples = db.execute("SELECT * FROM llm_learning_samples ORDER BY id ASC").fetchall()
    return render_template(
        'auto_track.html',
        api_key=api_key,
        webhook_url=webhook_url,
        webhook_url_with_key=webhook_url_with_key,
        current_username=current_username,
        llm_info=get_active_llm_provider(),
        samples=samples
    )


@auto_track_bp.route('/api/llm-samples', methods=['GET'], endpoint='api_llm_samples_list')
def api_llm_samples_list():
    db = get_db()
    rows = db.execute("SELECT * FROM llm_learning_samples ORDER BY id ASC").fetchall()
    return jsonify({'ok': True, 'samples': [dict(r) for r in rows]})


@auto_track_bp.route('/api/llm-samples/add', methods=['POST'], endpoint='api_llm_samples_add')
def api_llm_samples_add():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': '请先登录后再添加样本'}), 401
    db = get_db()
    data = request.get_json(silent=True) or request.form
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'ok': False, 'message': '样本通知文本不能为空'}), 400

    label_type = (data.get('label_type') or 'expense').strip()
    is_real = 1 if data.get('is_real_transaction') in (True, 1, '1', 'true', 'True') else 0
    if label_type in ('promo', 'otp_notice'):
        is_real = 0

    amount = data.get('sample_amount')
    try:
        amount = float(amount) if amount not in (None, '', 'null') else None
    except Exception:
        amount = None

    merchant = (data.get('sample_merchant') or '').strip() or None
    category = (data.get('sample_category') or '').strip() or None
    notes = (data.get('notes') or '').strip() or None
    now = datetime.now().isoformat()
    user_id = session.get('user_id')

    db.execute('''
        INSERT INTO llm_learning_samples (user_id, text, label_type, is_real_transaction, sample_amount, sample_merchant, sample_category, notes, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (user_id, text, label_type, is_real, amount, merchant, category, notes, now))
    db.commit()

    return jsonify({'ok': True, 'message': t('auto_track.sample_added_success', '成功录入学习样本库！大模型下次遇到类似通知将照此学习。')})


@auto_track_bp.route('/api/llm-samples/delete/<int:sample_id>', methods=['POST'], endpoint='api_llm_samples_delete')
def api_llm_samples_delete(sample_id):
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': t('common.please_login', '请先登录')}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples WHERE id = ?", (sample_id,))
    db.commit()
    return jsonify({'ok': True, 'message': t('auto_track.sample_deleted_success', '样本已成功删除')})


@auto_track_bp.route('/api/llm-samples/reset', methods=['POST'], endpoint='api_llm_samples_reset')
def api_llm_samples_reset():
    if not session.get('logged_in'):
        return jsonify({'ok': False, 'message': t('common.please_login', '请先登录')}), 401
    db = get_db()
    db.execute("DELETE FROM llm_learning_samples")
    db.commit()
    seed_learning_samples(db)
    return jsonify({'ok': True, 'message': t('auto_track.sample_reset_success', '已成功将学习样本库恢复为官方预设语料库！')})
