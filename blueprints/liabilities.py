import re
import logging
from datetime import datetime, date
from flask import Blueprint, render_template, request, redirect, url_for, flash, jsonify

from core.db import get_db, get_current_user_id
from core.auth import is_ajax_request
from core.i18n import t
from liabilities_tracker import (
    get_monthly_cashflow_events,
    sync_installments_to_monthly_statement,
    add_months_clamped,
    generate_amortization_schedule
)

logger = logging.getLogger(__name__)

liabilities_bp = Blueprint('liabilities', __name__)


@liabilities_bp.route('/liabilities', endpoint='liabilities_page')
def liabilities_page():
    user_id = get_current_user_id()
    db = get_db()

    target_month_str = request.args.get('month', '').strip()
    if not target_month_str or not re.match(r'^\d{4}-\d{2}$', target_month_str):
        target_month_str = date.today().strftime('%Y-%m')

    try:
        t_year, t_month = map(int, target_month_str.split('-'))
    except Exception:
        today = date.today()
        t_year, t_month = today.year, today.month
        target_month_str = f"{t_year:04d}-{t_month:02d}"

    cashflow_data = get_monthly_cashflow_events(db, user_id, t_year, t_month)

    installments = db.execute('''
        SELECT i.*, a.name as card_name, a.due_day as card_due_day
        FROM installments i
        LEFT JOIN accounts a ON i.account_id = a.id
        WHERE i.user_id = ?
        ORDER BY CASE WHEN i.status='active' THEN 0 ELSE 1 END, i.id DESC
    ''', (user_id,)).fetchall()

    inst_list = []
    total_inst_debt = 0.0
    for inst in installments:
        item = dict(inst)
        tenure = item.get('tenure_months') or 1
        paid = item.get('paid_periods') or 0
        rem_periods = max(0, tenure - paid)
        total_amt = item.get('total_amount') or 0.0
        monthly_amt = item.get('monthly_amount') or 0.0
        rem_bal = max(0.0, round(total_amt - (monthly_amt * paid), 2))

        try:
            f_dt = datetime.strptime(item.get('first_due_date'), "%Y-%m-%d").date()
            settle_dt = add_months_clamped(f_dt, tenure - 1)
            item['settle_month'] = settle_dt.strftime('%Y-%m')
        except Exception:
            item['settle_month'] = '—'

        item['remaining_periods'] = rem_periods
        item['remaining_balance'] = rem_bal
        item['progress_pct'] = min(100, int((paid / tenure) * 100)) if tenure > 0 else 100
        if item.get('status') == 'active':
            total_inst_debt += rem_bal
        inst_list.append(item)

    loans = db.execute('''
        SELECT l.*, a.name as account_name
        FROM loans l
        LEFT JOIN accounts a ON l.debit_account_id = a.id
        WHERE l.user_id = ?
        ORDER BY CASE WHEN l.status='active' THEN 0 ELSE 1 END, l.id DESC
    ''', (user_id,)).fetchall()

    loan_list = []
    total_loan_debt = 0.0
    for loan in loans:
        item = dict(loan)
        tenure = item.get('tenure_months') or 1
        paid = item.get('paid_periods') or 0
        item['remaining_periods'] = max(0, tenure - paid)
        item['progress_pct'] = min(100, int((paid / tenure) * 100)) if tenure > 0 else 100
        if item.get('status') == 'active':
            total_loan_debt += (item.get('remaining_balance') or 0.0)
        loan_list.append(item)

    accounts = db.execute('''
        SELECT * FROM accounts
        WHERE user_id = ? AND is_active = 1
        ORDER BY type, name
    ''', (user_id,)).fetchall()

    total_debt = total_inst_debt + total_loan_debt

    return render_template(
        'liabilities.html',
        target_month=target_month_str,
        cashflow=cashflow_data,
        installments=inst_list,
        loans=loan_list,
        accounts=accounts,
        total_debt=total_debt,
        total_inst_debt=total_inst_debt,
        total_loan_debt=total_loan_debt
    )


@liabilities_bp.route('/api/liabilities/installment', methods=['POST'], endpoint='api_add_installment')
def api_add_installment():
    user_id = get_current_user_id()
    db = get_db()

    title = (request.form.get('title') or '').strip()
    if not title:
        flash(t('liabilities.enter_installment_name', '请输入分期项目名称'), 'error')
        return redirect(url_for('liabilities_page'))

    try:
        total_amount = float(request.form.get('total_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
    except (ValueError, TypeError):
        flash(t('liabilities.invalid_installment_format', '分期金额或期数格式不正确'), 'error')
        return redirect(url_for('liabilities_page'))

    first_due_date = request.form.get('first_due_date') or date.today().isoformat()
    account_id = request.form.get('account_id') or None
    note = (request.form.get('note') or '').strip()

    monthly_amount = round(total_amount / tenure_months, 2) if tenure_months > 0 else total_amount
    status = 'completed' if paid_periods >= tenure_months else 'active'

    db.execute('''
        INSERT INTO installments (
            user_id, account_id, title, total_amount, tenure_months, paid_periods,
            monthly_amount, first_due_date, status, note, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        user_id, account_id, title, total_amount, tenure_months, paid_periods,
        monthly_amount, first_due_date, status, note, datetime.now().isoformat()
    ))
    db.commit()
    flash(t('liabilities.added_installment_fmt', '已成功添加免息分期项目：{title}', title=title), 'success')
    return redirect(url_for('liabilities_page'))


@liabilities_bp.route('/api/liabilities/loan', methods=['POST'], endpoint='api_add_loan')
def api_add_loan():
    user_id = get_current_user_id()
    db = get_db()

    title = (request.form.get('title') or '').strip()
    if not title:
        flash(t('liabilities.enter_loan_name', '请输入贷款项目名称'), 'error')
        return redirect(url_for('liabilities_page'))

    try:
        loan_amount = float(request.form.get('loan_amount', 0))
        tenure_months = int(request.form.get('tenure_months', 1))
        paid_periods = int(request.form.get('paid_periods', 0))
        interest_rate = float(request.form.get('annual_interest_rate', 0.0))
        due_day = int(request.form.get('due_day', 5))
    except (ValueError, TypeError):
        flash(t('liabilities.invalid_loan_format', '贷款金额、利率或期数格式不正确'), 'error')
        return redirect(url_for('liabilities_page'))

    method = request.form.get('method') or 'reducing_balance'
    debit_account_id = request.form.get('debit_account_id') or None
    start_date = request.form.get('start_date') or date.today().isoformat()
    note = (request.form.get('note') or '').strip()

    custom_monthly = request.form.get('monthly_payment')
    try:
        if custom_monthly and float(custom_monthly) > 0:
            monthly_payment = float(custom_monthly)
            remaining_balance = max(0.0, round(loan_amount - (loan_amount / tenure_months * paid_periods), 2))
        else:
            sched = generate_amortization_schedule(loan_amount, tenure_months, start_date, interest_rate, method)
            monthly_payment = sched[0]['total_amount']
            idx = min(paid_periods, len(sched) - 1)
            remaining_balance = sched[idx]['remaining_balance'] if paid_periods < len(sched) else 0.0
    except Exception as e:
        logger.warning("Calculate loan schedule failed: %s", e)
        monthly_payment = round(loan_amount / tenure_months, 2)
        remaining_balance = loan_amount

    status = 'completed' if paid_periods >= tenure_months else 'active'

    db.execute('''
        INSERT INTO loans (
            user_id, debit_account_id, title, loan_amount, remaining_balance,
            tenure_months, paid_periods, annual_interest_rate, method,
            monthly_payment, due_day, start_date, status, note, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        user_id, debit_account_id, title, loan_amount, remaining_balance,
        tenure_months, paid_periods, interest_rate, method,
        monthly_payment, due_day, start_date, status, note, datetime.now().isoformat()
    ))
    db.commit()
    flash(t('liabilities.added_loan_fmt', '已成功添加贷款记录：{title}', title=title), 'success')
    return redirect(url_for('liabilities_page'))


@liabilities_bp.route('/api/liabilities/account', methods=['POST'], endpoint='api_add_account')
def api_add_account():
    user_id = get_current_user_id()
    db = get_db()

    name = (request.form.get('name') or '').strip()
    acc_type = request.form.get('type') or 'credit_card'
    if not name:
        flash(t('liabilities.enter_account_name', '请输入账户/卡片名称'), 'error')
        return redirect(url_for('liabilities_page'))

    credit_limit = float(request.form.get('credit_limit') or 0.0)
    statement_day = int(request.form.get('statement_day') or 15)
    due_day = int(request.form.get('due_day') or 5)

    db.execute('''
        INSERT INTO accounts (
            user_id, name, type, credit_limit, statement_day, due_day, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    ''', (user_id, name, acc_type, credit_limit, statement_day, due_day, datetime.now().isoformat()))
    db.commit()
    flash(t('liabilities.added_card_fmt', '已成功添加卡片/账户：{name}', name=name), 'success')
    return redirect(url_for('liabilities_page'))


@liabilities_bp.route('/api/liabilities/sync', methods=['POST'], endpoint='api_sync_liabilities')
def api_sync_liabilities():
    db = get_db()
    today_str = date.today().isoformat()
    res = sync_installments_to_monthly_statement(db, today_str)
    msg = f"同步完成：已自动挂账 {res['syncedRecords']} 笔流水，已结清归档 {res['completedInstallments']} 笔分期。"
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'data': res})
    flash(msg, 'success')
@liabilities_bp.route('/api/liabilities/<string:item_type>/<int:item_id>/delete', methods=['POST'], endpoint='api_delete_liability')
def api_delete_liability(item_type, item_id):
    user_id = get_current_user_id()
    db = get_db()

    if item_type == 'installment':
        db.execute('DELETE FROM installments WHERE id = ? AND user_id = ?', (item_id, user_id))
    elif item_type == 'loan':
        db.execute('DELETE FROM loans WHERE id = ? AND user_id = ?', (item_id, user_id))
    elif item_type == 'account':
        db.execute('DELETE FROM accounts WHERE id = ? AND user_id = ?', (item_id, user_id))
    db.commit()
    flash(t('liabilities.deleted_liability', '已删除该负债记录'), 'success')
    return redirect(url_for('liabilities_page'))


ACCOUNT_TYPES = [
    ('savings',      '储蓄账户 (Savings)'),
    ('current',      '活期账户 (Current)'),
    ('credit_card',  '信用卡 (Credit Card)'),
    ('e_wallet',     '电子钱包 (E-Wallet)'),
    ('investment',   '投资账户 (Investment)'),
    ('cash',         '现金 (Cash)'),
    ('other',        '其他 (Other)'),
]

ACCOUNT_CURRENCIES = ['MYR', 'USD', 'SGD', 'CNY', 'EUR', 'GBP', 'AUD', 'JPY']


@liabilities_bp.route('/accounts', endpoint='accounts_page')
def accounts_page():
    user_id = get_current_user_id()
    db = get_db()
    rows = db.execute(
        """SELECT a.*,
                  (SELECT COUNT(*) FROM subscriptions s WHERE s.payment_method_id = a.id AND s.status = 'ACTIVE') as sub_count,
                  (SELECT COUNT(*) FROM installments i WHERE i.account_id = a.id AND i.status = 'active') as install_count
           FROM accounts a
           WHERE a.user_id = ?
           ORDER BY a.is_active DESC, a.type ASC, a.name ASC""",
        (user_id,)
    ).fetchall()
    accounts = [dict(r) for r in rows]
    return render_template(
        'accounts.html',
        accounts=accounts,
        account_types=ACCOUNT_TYPES,
        currencies=ACCOUNT_CURRENCIES,
    )


@liabilities_bp.route('/api/accounts', methods=['POST'], endpoint='manage_add_account')
def manage_add_account():
    user_id = get_current_user_id()
    db = get_db()
    name = request.form.get('name', '').strip()
    acc_type = request.form.get('type', 'savings')
    currency = request.form.get('currency', 'MYR')
    credit_limit = request.form.get('credit_limit', '0') or '0'
    statement_day = request.form.get('statement_day', '') or None
    due_day = request.form.get('due_day', '') or None
    grace_period_days = request.form.get('grace_period_days', '20') or '20'

    if not name:
        flash(t('accounts.name_required', '账户名称不能为空'), 'error')
        return redirect(url_for('accounts_page'))

    try:
        credit_limit_val = float(credit_limit)
    except ValueError:
        credit_limit_val = 0.0

    db.execute(
        """INSERT INTO accounts (user_id, name, type, credit_limit, statement_day, due_day,
                                  grace_period_days, currency, is_active, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)""",
        (user_id, name, acc_type, credit_limit_val,
         int(statement_day) if statement_day else None,
         int(due_day) if due_day else None,
         int(grace_period_days),
         currency,
         datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    )
    db.commit()
    flash(t('accounts.added_fmt', '账户「{name}」已成功添加', name=name), 'success')
    return redirect(url_for('accounts_page'))


@liabilities_bp.route('/api/accounts/<int:acc_id>/edit', methods=['POST'], endpoint='manage_edit_account')
def manage_edit_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    name = request.form.get('name', '').strip()
    acc_type = request.form.get('type', 'savings')
    currency = request.form.get('currency', 'MYR')
    credit_limit = request.form.get('credit_limit', '0') or '0'
    statement_day = request.form.get('statement_day', '') or None
    due_day = request.form.get('due_day', '') or None
    grace_period_days = request.form.get('grace_period_days', '20') or '20'

    if not name:
        flash(t('accounts.name_required', '账户名称不能为空'), 'error')
        return redirect(url_for('accounts_page'))

    try:
        credit_limit_val = float(credit_limit)
    except ValueError:
        credit_limit_val = 0.0

    db.execute(
        """UPDATE accounts
           SET name=?, type=?, credit_limit=?, statement_day=?, due_day=?,
               grace_period_days=?, currency=?
           WHERE id=? AND user_id=?""",
        (name, acc_type, credit_limit_val,
         int(statement_day) if statement_day else None,
         int(due_day) if due_day else None,
         int(grace_period_days), currency,
         acc_id, user_id)
    )
    db.commit()
    flash(t('accounts.updated_fmt', '账户「{name}」已更新', name=name), 'success')
    return redirect(url_for('accounts_page'))


@liabilities_bp.route('/api/accounts/<int:acc_id>/toggle', methods=['POST'], endpoint='manage_toggle_account')
def manage_toggle_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT is_active FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash(t('accounts.not_found', '账户不存在'), 'error')
        return redirect(url_for('accounts_page'))
    new_state = 0 if row['is_active'] else 1
    db.execute('UPDATE accounts SET is_active=? WHERE id=? AND user_id=?', (new_state, acc_id, user_id))
    db.commit()
    flash(t('accounts.status_updated', '账户状态已更新'), 'success')
    return redirect(url_for('accounts_page'))


@liabilities_bp.route('/api/accounts/<int:acc_id>/delete', methods=['POST'], endpoint='manage_delete_account')
def manage_delete_account(acc_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT name FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id)).fetchone()
    if not row:
        flash(t('accounts.not_found', '账户不存在'), 'error')
        return redirect(url_for('accounts_page'))
    # Unlink subscriptions and installments before deletion
    db.execute('UPDATE subscriptions SET payment_method_id=NULL WHERE payment_method_id=? AND user_id=?', (acc_id, user_id))
    db.execute('UPDATE installments SET account_id=NULL WHERE account_id=? AND user_id=?', (acc_id, user_id))
    db.execute('DELETE FROM accounts WHERE id=? AND user_id=?', (acc_id, user_id))
    db.commit()
    flash(t('accounts.account_deleted_fmt', '账户「{name}」已删除', name=row["name"]), 'success')
    return redirect(url_for('accounts_page'))

