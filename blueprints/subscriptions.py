from decimal import Decimal
from datetime import datetime, date
from flask import Blueprint, render_template, request, redirect, url_for, flash

from core.db import get_db, get_current_user_id, bump_data_version
from core.i18n import t
from subscription_tracker import (
    BillingCycle,
    SubscriptionStatus,
    Subscription,
    rollToNextBillingDate,
    buildSubscriptionDashboardSummary,
    default_mock_exchange_rate_provider
)

subscriptions_bp = Blueprint('subscriptions', __name__)


@subscriptions_bp.route('/subscriptions', endpoint='subscriptions_page')
def subscriptions_page():
    user_id = get_current_user_id()
    db = get_db()

    rows = db.execute("""
        SELECT s.*, a.name as payment_method_name 
        FROM subscriptions s
        LEFT JOIN accounts a ON s.payment_method_id = a.id
        WHERE s.user_id = ?
        ORDER BY s.status ASC, s.next_billing_date ASC
    """, (user_id,)).fetchall()

    subs = []
    for r in rows:
        d = dict(r)
        start_dt = datetime.strptime(d["start_date"], "%Y-%m-%d").date() if isinstance(d.get("start_date"), str) else (d.get("start_date") or date.today())
        next_dt = datetime.strptime(d["next_billing_date"], "%Y-%m-%d").date() if isinstance(d.get("next_billing_date"), str) else (d.get("next_billing_date") or date.today())
        trial_dt = datetime.strptime(d["trial_end_date"], "%Y-%m-%d").date() if d.get("trial_end_date") and isinstance(d.get("trial_end_date"), str) else None

        subs.append(Subscription(
            id=d["id"],
            name=d["name"],
            category=d["category"],
            billing_cycle=BillingCycle(d["billing_cycle"]),
            cost=Decimal(str(d["cost"])),
            currency=d.get("currency", "MYR"),
            auto_renew=bool(d.get("auto_renew", 1)),
            start_date=start_dt,
            next_billing_date=next_dt,
            payment_method_id=d.get("payment_method_id"),
            payment_method_name=d.get("payment_method_name"),
            status=SubscriptionStatus(d.get("status", "ACTIVE")),
            cancellation_reminder_days=int(d.get("cancellation_reminder_days") or 3),
            is_trial=bool(d.get("is_trial", 0)),
            trial_end_date=trial_dt,
            target_to_cancel=bool(d.get("target_to_cancel", 0)),
            anchor_day=d.get("anchor_day") or (start_dt.day if start_dt else next_dt.day),
            note=d.get("note")
        ))

    from flask import g
    user_curr_code = getattr(g, 'current_currency_code', 'MYR') or 'MYR'
    today = date.today()
    dashboard_summary = buildSubscriptionDashboardSummary(subs, currentDate=today, targetCurrency=user_curr_code)

    accounts = db.execute(
        "SELECT id, name, type, currency FROM accounts WHERE user_id = ? AND is_active = 1 ORDER BY name ASC",
        (user_id,)
    ).fetchall()

    category_breakdown = {}
    for s in subs:
        if s.status == SubscriptionStatus.ACTIVE:
            rate = default_mock_exchange_rate_provider(s.currency, user_curr_code)
            cost_target = s.cost * rate
            if s.billing_cycle == BillingCycle.MONTHLY:
                m_cost = cost_target
            elif s.billing_cycle == BillingCycle.WEEKLY:
                m_cost = (cost_target * Decimal("52")) / Decimal("12")
            elif s.billing_cycle == BillingCycle.QUARTERLY:
                m_cost = cost_target / Decimal("3")
            elif s.billing_cycle == BillingCycle.SEMI_ANNUAL:
                m_cost = cost_target / Decimal("6")
            else:
                m_cost = cost_target / Decimal("12")
            cat = s.category or "其他"
            category_breakdown[cat] = (category_breakdown.get(cat, Decimal("0")) + m_cost).quantize(Decimal("0.01"))

    return render_template(
        'subscriptions.html',
        summary=dashboard_summary,
        subscriptions=subs,
        accounts=accounts,
        category_breakdown=category_breakdown,
        today=today.isoformat(),
        today_date=today
    )


@subscriptions_bp.route('/api/subscriptions', methods=['POST'], endpoint='api_add_subscription')
def api_add_subscription():
    user_id = get_current_user_id()
    db = get_db()
    from flask import g
    user_curr_code = getattr(g, 'current_currency_code', 'MYR') or 'MYR'

    name = (request.form.get('name') or '').strip()
    category = (request.form.get('category') or '流媒体').strip()
    billing_cycle = (request.form.get('billing_cycle') or 'MONTHLY').upper()
    cost_str = (request.form.get('cost') or '0').strip()
    currency = (request.form.get('currency') or user_curr_code).upper()
    start_date = request.form.get('start_date') or date.today().isoformat()
    next_billing_date = request.form.get('next_billing_date') or start_date
    payment_method_id = request.form.get('payment_method_id') or None
    cancellation_reminder_days = int(request.form.get('cancellation_reminder_days') or 3)
    auto_renew = 1 if request.form.get('auto_renew') in ('1', 'on', 'true') else 0
    is_trial = 1 if request.form.get('is_trial') in ('1', 'on', 'true') else 0
    trial_end_date = request.form.get('trial_end_date') or None
    target_to_cancel = 1 if request.form.get('target_to_cancel') in ('1', 'on', 'true') else 0
    note = (request.form.get('note') or '').strip()

    if not name:
        flash(t('subscriptions.enter_name', '请输入订阅服务名称'), 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
        if cost <= 0:
            raise ValueError()
    except ValueError:
        flash(t('subscriptions.invalid_amount', '请输入有效的扣费金额'), 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        anchor_day = datetime.strptime(next_billing_date, "%Y-%m-%d").day
    except Exception:
        try:
            anchor_day = datetime.strptime(start_date, "%Y-%m-%d").day
        except Exception:
            anchor_day = date.today().day

    db.execute("""
        INSERT INTO subscriptions (
            user_id, name, category, billing_cycle, cost, currency,
            auto_renew, start_date, next_billing_date, payment_method_id,
            status, cancellation_reminder_days, is_trial, trial_end_date,
            target_to_cancel, anchor_day, note, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
    """, (
        user_id, name, category, billing_cycle, cost, currency,
        auto_renew, start_date, next_billing_date, payment_method_id,
        cancellation_reminder_days, is_trial, trial_end_date,
        target_to_cancel, anchor_day, note
    ))
    db.commit()
    bump_data_version('subscription_add', user_id=user_id)
    flash(t('subscriptions.added_fmt', '成功添加订阅服务：{name}', name=name), 'success')
    return redirect(url_for('subscriptions_page'))


@subscriptions_bp.route('/api/subscriptions/<int:sub_id>/edit', methods=['POST'], endpoint='api_edit_subscription')
def api_edit_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()

    name = (request.form.get('name') or '').strip()
    category = (request.form.get('category') or '流媒体').strip()
    billing_cycle = (request.form.get('billing_cycle') or 'MONTHLY').upper()
    cost_str = (request.form.get('cost') or '0').strip()
    currency = (request.form.get('currency') or 'MYR').upper()
    next_billing_date = request.form.get('next_billing_date') or date.today().isoformat()
    payment_method_id = request.form.get('payment_method_id') or None
    cancellation_reminder_days = int(request.form.get('cancellation_reminder_days') or 3)
    auto_renew = 1 if request.form.get('auto_renew') in ('1', 'on', 'true') else 0
    is_trial = 1 if request.form.get('is_trial') in ('1', 'on', 'true') else 0
    trial_end_date = request.form.get('trial_end_date') or None
    target_to_cancel = 1 if request.form.get('target_to_cancel') in ('1', 'on', 'true') else 0
    note = (request.form.get('note') or '').strip()

    if not name:
        flash(t('subscriptions.name_required', '订阅名称不能为空'), 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        cost = float(cost_str)
    except ValueError:
        flash(t('subscriptions.invalid_amount_format', '金额格式不正确'), 'error')
        return redirect(url_for('subscriptions_page'))

    try:
        anchor_day = datetime.strptime(next_billing_date, "%Y-%m-%d").day
    except Exception:
        anchor_day = date.today().day

    db.execute("""
        UPDATE subscriptions
        SET name = ?, category = ?, billing_cycle = ?, cost = ?, currency = ?,
            next_billing_date = ?, payment_method_id = ?, cancellation_reminder_days = ?,
            auto_renew = ?, is_trial = ?, trial_end_date = ?, target_to_cancel = ?,
            anchor_day = ?, note = ?, updated_at = datetime('now')
        WHERE id = ? AND user_id = ?
    """, (
        name, category, billing_cycle, cost, currency,
        next_billing_date, payment_method_id, cancellation_reminder_days,
        auto_renew, is_trial, trial_end_date, target_to_cancel,
        anchor_day, note, sub_id, user_id
    ))
    db.commit()
    bump_data_version('subscription_edit', user_id=user_id)
    flash(t('subscriptions.updated_fmt', '已更新订阅服务：{name}', name=name), 'success')
    return redirect(url_for('subscriptions_page'))


@subscriptions_bp.route('/api/subscriptions/<int:sub_id>/toggle-status', methods=['POST'], endpoint='api_toggle_subscription_status')
def api_toggle_subscription_status(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT status, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash(t('subscriptions.not_found', '找不到指定订阅记录'), 'error')
        return redirect(url_for('subscriptions_page'))

    new_status = 'PAUSED' if row['status'] == 'ACTIVE' else 'ACTIVE'
    db.execute("UPDATE subscriptions SET status = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_status, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_status', user_id=user_id)
    state_desc = t('subscriptions.resumed_active', '已恢复活跃计费') if new_status == 'ACTIVE' else t('subscriptions.paused_monitoring', '已暂停扣款监控')
    flash(t('subscriptions.state_updated_fmt', '已将【{name}】{state}', name=row['name'], state=state_desc), 'success')
    return redirect(url_for('subscriptions_page'))


@subscriptions_bp.route('/api/subscriptions/<int:sub_id>/toggle-cancel-target', methods=['POST'], endpoint='api_toggle_cancel_target')
def api_toggle_cancel_target(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT target_to_cancel, name FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash(t('subscriptions.not_found', '找不到指定订阅记录'), 'error')
        return redirect(url_for('subscriptions_page'))

    new_flag = 0 if row['target_to_cancel'] else 1
    db.execute("UPDATE subscriptions SET target_to_cancel = ?, updated_at = datetime('now') WHERE id = ? AND user_id = ?", (new_flag, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_cancel_target', user_id=user_id)
    tip = t('subscriptions.marked_to_cancel', '已标记为【打算退订】，将在扣款前高亮预警拦截！') if new_flag else t('subscriptions.unmarked_to_cancel', '已取消退订标记')
    flash(t('subscriptions.tip_fmt', '【{name}】{tip}', name=row['name'], tip=tip), 'success')
    return redirect(url_for('subscriptions_page'))


@subscriptions_bp.route('/api/subscriptions/<int:sub_id>/roll', methods=['POST'], endpoint='api_roll_subscription')
def api_roll_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute("SELECT * FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id)).fetchone()
    if not row:
        flash(t('subscriptions.not_found', '找不到指定订阅记录'), 'error')
        return redirect(url_for('subscriptions_page'))

    d = dict(row)
    curr_date = datetime.strptime(d['next_billing_date'], '%Y-%m-%d').date()
    cycle = BillingCycle(d['billing_cycle'])
    anchor_day = d.get('anchor_day') or curr_date.day

    new_date = rollToNextBillingDate(curr_date, cycle, anchor_day)

    record_expense = request.form.get('record_expense') in ('1', 'on', 'true')
    if record_expense:
        db.execute("""
            INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at)
            VALUES (?, ?, 'expense', '订阅与周期固定', ?, ?, ?, 'subscription', datetime('now'))
        """, (
            user_id, curr_date.isoformat(), d['category'], float(d['cost']),
            f"订阅续费: {d['name']} ({d['billing_cycle']})"
        ))

    db.execute("""
        UPDATE subscriptions
        SET next_billing_date = ?, anchor_day = ?, updated_at = datetime('now')
        WHERE id = ? AND user_id = ?
    """, (new_date.isoformat(), anchor_day, sub_id, user_id))
    db.commit()
    bump_data_version('subscription_roll', user_id=user_id)
    extra_msg = t('subscriptions.auto_expense_hint', '，并已自动生成当期记账支出') if record_expense else ''
    flash(t('subscriptions.renewed_fmt', '【{name}】已成功续期至 {date}{extra}！', name=d['name'], date=new_date.isoformat(), extra=extra_msg), 'success')
    return redirect(url_for('subscriptions_page'))


@subscriptions_bp.route('/api/subscriptions/<int:sub_id>/delete', methods=['POST'], endpoint='api_delete_subscription')
def api_delete_subscription(sub_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute("DELETE FROM subscriptions WHERE id = ? AND user_id = ?", (sub_id, user_id))
    db.commit()
    bump_data_version('subscription_delete', user_id=user_id)
    flash(t('subscriptions.deleted_success', '已删除该订阅服务记录'), 'success')
    return redirect(url_for('subscriptions_page'))
