"""Cashflow reporting. A schedule is never evidence that money was paid.

Amounts are grouped by currency (no invented exchange rates). Only explicitly
linked, live expense transactions settle a bill. Saved bill snapshots preserve
the due date after the original subscription advances to its next cycle.
"""
import calendar
from datetime import date, timedelta, datetime, timezone
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
import re
import logging

from core.db import bump_data_version, get_user_settings
from services.saved_filters_service import INTERNAL_TRANSFER_CATS
from subscription_tracker import BillingCycle, rollToNextBillingDate

logger = logging.getLogger(__name__)


def init_cashflow_schema(db):
    db.execute("""CREATE TABLE IF NOT EXISTS cashflow_bills (
        user_id TEXT NOT NULL, bill_key TEXT NOT NULL, title TEXT NOT NULL,
        due_date TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL,
        source_kind TEXT NOT NULL, source_id INTEGER NOT NULL,
        cancelled INTEGER NOT NULL DEFAULT 0, duplicate_of TEXT,
        PRIMARY KEY (user_id, bill_key))""")
    db.execute("""CREATE TABLE IF NOT EXISTS cashflow_payment_links (
        user_id TEXT NOT NULL, transaction_id INTEGER NOT NULL,
        bill_key TEXT NOT NULL, is_refund INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (user_id, transaction_id))""")
    db.execute("""CREATE TABLE IF NOT EXISTS cashflow_transaction_confirmations (
        user_id TEXT NOT NULL, transaction_id INTEGER NOT NULL,
        PRIMARY KEY (user_id, transaction_id))""")


def money(value):
    try:
        result = Decimal(str(0 if value is None else value))
    except InvalidOperation as error:
        raise ValueError('Invalid amount') from error
    if not result.is_finite() or abs(result) >= Decimal('1e18'):
        raise ValueError('Invalid amount')
    return result.quantize(Decimal('.01'), rounding=ROUND_HALF_UP)


def month_bounds(month):
    if not re.fullmatch(r'20\d{2}-\d{2}', month or ''):
        raise ValueError('Expected month YYYY-MM (2000–2099)')
    start = date.fromisoformat(month + '-01')
    return start, start.replace(day=calendar.monthrange(start.year, start.month)[1])


def currency_code(value):
    value = str(value or 'MYR').upper()
    return 'MYR' if value == 'RM' else value


def _at_month(start, offset, day):
    index = start.year * 12 + start.month - 1 + offset
    year, month = divmod(index, 12)
    return date(year, month + 1, min(day, calendar.monthrange(year, month + 1)[1]))


def _bill(kind, row, due, amount, currency):
    return dict(bill_key=f"{kind}:{row['id']}:{due.isoformat()}",
                title=row.get('title') or row.get('name') or row.get('note') or row.get('category') or kind,
                due_date=due.isoformat(), amount=str(money(amount)),
                currency=currency_code(currency), source_kind=kind, source_id=row['id'],
                cancelled=0, duplicate_of=None, estimated=True)


def _scheduled_bills(user_id, start, end, db, base_currency):
    """Reconstruct unconfirmed dates, not payment history. No writes on GET."""
    accounts = {r['id']: currency_code(r['currency']) for r in db.execute(
        'SELECT id, currency FROM accounts WHERE user_id = ?', (str(user_id),)).fetchall()}
    result = {}
    for kind, table in [('recurring', 'recurring_rules'), ('installment', 'installments'),
                        ('loan', 'loans'), ('subscription', 'subscriptions')]:
        # Table names come only from this fixed list, never request data.
        for raw in db.execute(f'SELECT * FROM {table} WHERE user_id = ?', (str(user_id),)).fetchall():
            row = dict(raw)
            if kind == 'recurring':
                if not row['is_active'] or row['type'] != 'expense':
                    continue
                created = date.fromisoformat(row['created_at'][:10])
                due = _at_month(start, 0, row['day_of_month'])
                dates = [due] if due >= created else []
                amount, currency = row['amount'], base_currency
            elif kind in ('installment', 'loan'):
                if row['status'].lower() != 'active':
                    continue
                first = date.fromisoformat(row['first_due_date' if kind == 'installment' else 'start_date'][:10])
                offset = (start.year - first.year) * 12 + start.month - first.month
                day = first.day if kind == 'installment' else row['due_day']
                due = _at_month(start, 0, day)
                dates = [due] if 0 <= offset < row['tenure_months'] and due >= first else []
                amount = row['monthly_amount' if kind == 'installment' else 'monthly_payment']
                currency = accounts.get(row.get('account_id' if kind == 'installment' else 'debit_account_id'), base_currency)
            else:
                if row['status'].upper() != 'ACTIVE':
                    continue
                first = date.fromisoformat(row['start_date'][:10])
                anchor = row.get('anchor_day') or first.day
                cycle = BillingCycle(row['billing_cycle'])
                # The current next date is the schedule anchor, not proof of payment.
                current = date.fromisoformat(row['next_billing_date'][:10])
                dates = []
                if cycle == BillingCycle.WEEKLY:
                    offset = (start - current).days // 7
                    current += timedelta(days=offset * 7)
                else:
                    step = {BillingCycle.MONTHLY: 1, BillingCycle.QUARTERLY: 3,
                            BillingCycle.SEMI_ANNUAL: 6, BillingCycle.YEARLY: 12}[cycle]
                    offset = ((start.year - current.year) * 12 + start.month - current.month) // step
                    current = _at_month(current, offset * step, anchor)
                while current <= end:
                    if current >= max(start, first) and (row['auto_renew'] or current == date.fromisoformat(row['next_billing_date'][:10])):
                        if not row['is_trial'] or not row.get('trial_end_date') or current >= date.fromisoformat(row['trial_end_date'][:10]):
                            dates.append(current)
                    current = rollToNextBillingDate(current, cycle, anchor)
                amount, currency = row['cost'], row['currency']
            for due in dates:
                item = _bill(kind, row, due, amount, currency)
                result[item['bill_key']] = item
    return result


def _transactions(user_id, db, base_currency, start=None, end=None, transaction_id=None):
    query = """SELECT t.*, a.name AS platform, a.currency AS account_currency
        FROM transactions t LEFT JOIN accounts a ON a.id = t.account_id AND a.user_id = t.user_id
        WHERE t.user_id = ? AND t.deleted_at IS NULL"""
    params = [str(user_id)]
    if transaction_id is not None:
        query += ' AND t.id = ?'
        params.append(transaction_id)
    elif start is not None:
        query += ''' AND (t.date BETWEEN ? AND ? OR t.id IN
            (SELECT transaction_id FROM cashflow_payment_links WHERE user_id = ?))'''
        params.extend([start.isoformat(), end.isoformat(), str(user_id)])
    rows = db.execute(query, params).fetchall()
    return [dict(r, currency=currency_code(r['account_currency'] or base_currency)) for r in rows]


def _expense(tx):
    return tx['type'] == 'expense' and tx['category'] not in INTERNAL_TRANSFER_CATS


def _percent(amount, denominator):
    return float((amount / denominator * 100).quantize(Decimal('.1'))) if denominator > 0 else None


def get_monthly_cashflow_summary(user_id, month, db, currency=None, today=None):
    if not user_id:
        raise ValueError('User required')
    start, end = month_bounds(month)
    today = today or date.today()
    base = currency_code(get_user_settings(user_id, db=db).get('default_currency'))
    selected = currency_code(currency or base)
    transactions = _transactions(user_id, db, base, start, end)
    bills = _scheduled_bills(user_id, start, end, db, base)
    # Snapshots survive renewal, plan edits and deletion. Unmatched history is
    # explicitly an estimate; never infer paid from source counters or notes.
    for raw in db.execute('SELECT * FROM cashflow_bills WHERE user_id = ? AND due_date <= ?',
                          (str(user_id), end.isoformat())).fetchall():
        row = dict(raw)
        row['estimated'] = False
        bills[row['bill_key']] = row
    tx_by_id = {tx['id']: tx for tx in transactions}
    paid = {}
    refunds = set()
    for link in db.execute('SELECT * FROM cashflow_payment_links WHERE user_id = ?', (str(user_id),)).fetchall():
        tx = tx_by_id.get(link['transaction_id'])
        bill = bills.get(link['bill_key'])
        refund = bool(link['is_refund'])
        if (tx and bill and tx['category'] not in INTERNAL_TRANSFER_CATS
                and (_expense(tx) or (refund and tx['type'] == 'income'))
                and tx['currency'] == bill['currency'] and tx['date'] <= end.isoformat()):
            value = money(tx['amount']) * (-1 if refund and tx['type'] == 'income' else 1)
            paid[link['bill_key']] = paid.get(link['bill_key'], Decimal(0)) + value
            if refund and tx['type'] == 'income':
                refunds.add(tx['id'])
    current, overdue, inactive = [], [], []
    for bill in bills.values():
        if bill['currency'] != selected:
            continue
        if bill['cancelled'] or bill['duplicate_of']:
            if start.isoformat() <= bill['due_date'] <= end.isoformat():
                inactive.append(dict(bill))
            continue
        due = date.fromisoformat(bill['due_date'])
        expected = max(Decimal(0), money(bill['amount']))
        settled = max(Decimal(0), min(expected, paid.get(bill['bill_key'], Decimal(0))))
        pending = expected - settled
        item = dict(bill, amount=float(expected), paid=float(settled), pending=float(pending),
                    status='paid' if pending == 0 else ('overdue' if due < today else ('partial' if settled else 'pending')))
        if start <= due <= end:
            current.append(item)
        elif due < start and pending > 0:
            overdue.append(item)
    explicitly_confirmed = {r['transaction_id'] for r in db.execute(
        'SELECT transaction_id FROM cashflow_transaction_confirmations WHERE user_id = ?', (str(user_id),)).fetchall()}
    confirmed = set(explicitly_confirmed)
    confirmed.update(r['transaction_id'] for r in db.execute(
        'SELECT transaction_id FROM cashflow_payment_links WHERE user_id = ?', (str(user_id),)).fetchall())
    scheduled = [t for t in transactions if t.get('source') == 'recurring' and t['id'] not in confirmed
                 and start.isoformat() <= t['date'] <= end.isoformat() and t['currency'] == selected]
    report_transactions = [dict(t, type='expense', amount=-float(money(t['amount']))) if t['id'] in refunds else t
                           for t in transactions]
    month_txs = [t for t in report_transactions if start.isoformat() <= t['date'] <= end.isoformat()
                 and t['currency'] == selected and t['category'] not in INTERNAL_TRANSFER_CATS
                 and t['type'] in ('income', 'expense')
                 and (t.get('source') != 'recurring' or t['id'] in confirmed)]
    income = sum((money(t['amount']) for t in month_txs if t['type'] == 'income'), Decimal(0))
    expense = sum((money(t['amount']) for t in month_txs if _expense(t)), Decimal(0))
    reserve = sum((money(b['pending']) for b in current), Decimal(0))
    planned = sum((money(b['amount']) for b in current), Decimal(0))
    paid_total = sum((money(b['paid']) for b in current), Decimal(0))
    def groups(kind, key):
        grouped = {}
        for tx in month_txs:
            if tx['type'] != kind:
                continue
            identity = tx.get(key) or ''
            group = grouped.setdefault(str(identity), dict(key=identity, label=tx.get('platform') if key == 'account_id' else identity,
                                                           amount=Decimal(0), transaction_ids=[]))
            group['amount'] += money(tx['amount'])
            group['transaction_ids'].append(tx['id'])
        total = income if kind == 'income' else expense
        return [dict(g, amount=float(g['amount']), income_percent=_percent(g['amount'], income),
                     total_percent=_percent(g['amount'], total))
                for g in sorted(grouped.values(), key=lambda g: g['amount'], reverse=True)]
    available = income - expense - reserve
    return dict(ok=True, month=month, start_date=start.isoformat(), end_date=end.isoformat(), currency=selected,
                currencies=sorted({base, selected} | {t['currency'] for t in transactions} | {b['currency'] for b in bills.values()}),
                income=float(income), expense=float(expense), net=float(income-expense), reserved=float(reserve),
                available=float(available), deficit=float(max(Decimal(0), -available)),
                expense_percent=_percent(expense, income), reserve_percent=_percent(reserve, income),
                available_percent=_percent(available, income), planned=float(planned), paid=float(paid_total),
                payment_percent=_percent(paid_total, planned), bills=sorted(current, key=lambda b: b['due_date']),
                inactive_bills=inactive,
                prior_overdue=sorted(overdue, key=lambda b: b['due_date']),
                prior_overdue_amount=float(sum((money(b['pending']) for b in overdue), Decimal(0))),
                income_platforms=groups('income', 'account_id'), expense_platforms=groups('expense', 'account_id'),
                expense_categories=groups('expense', 'category'),
                transactions=[{k: t.get(k) for k in ('id', 'date', 'type', 'category', 'amount', 'note', 'account_id', 'platform')} for t in month_txs],
                scheduled_transactions=[{k: t.get(k) for k in ('id', 'date', 'type', 'category', 'amount', 'note')} for t in scheduled],
                confirmed_transactions=[{k: t.get(k) for k in ('id', 'date', 'type', 'amount', 'note')}
                                        for t in month_txs if t['id'] in explicitly_confirmed],
                schedule_estimated=any(b['estimated'] for b in current),
                payment_links=[dict(r) for r in db.execute('SELECT * FROM cashflow_payment_links WHERE user_id = ?', (str(user_id),)).fetchall()],
                updated_at=datetime.now(timezone.utc).isoformat())


def update_bill_payment(user_id, month, bill_key, tx_id, db, unlink=False, refund=False):
    """One entire transaction can settle only one bill. Retry-safe unique key.

    Partial payments use multiple transactions. No fuzzy matching or markers.
    Deleting a transaction or changing its amount immediately affects reports.
    """
    if not user_id:
        raise ValueError('User required')
    month_bounds(month)
    if unlink:
        db.execute('DELETE FROM cashflow_payment_links WHERE user_id = ? AND transaction_id = ? AND bill_key = ?',
                   (str(user_id), tx_id, bill_key))
    else:
        base = currency_code(get_user_settings(user_id, db=db).get('default_currency'))
        tx = next(iter(_transactions(user_id, db, base, transaction_id=tx_id)), None)
        if (not tx or tx['category'] in INTERNAL_TRANSFER_CATS
                or not (_expense(tx) or (refund and tx['type'] == 'income')) or money(tx['amount']) == 0):
            raise ValueError('Select a live expense (or explicitly mark an income as a refund)')
        report = get_monthly_cashflow_summary(user_id, month, db, currency=tx['currency'])
        bill = next((b for b in report['bills'] + report['prior_overdue'] if b['bill_key'] == bill_key), None)
        if not bill:
            raise ValueError('Bill not found in this currency/month')
        existing = db.execute('SELECT bill_key, is_refund FROM cashflow_payment_links WHERE user_id = ? AND transaction_id = ?',
                              (str(user_id), tx_id)).fetchone()
        if existing and existing['bill_key'] != bill_key:
            raise ValueError('Transaction is already linked to another bill')
        if existing and bool(existing['is_refund']) != refund:
            raise ValueError('Unlink the transaction before changing its payment/refund role')
        db.execute('''INSERT OR IGNORE INTO cashflow_bills
            (user_id, bill_key, title, due_date, amount, currency, source_kind, source_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)''',
                   (str(user_id), bill_key, bill['title'], bill['due_date'], str(bill['amount']),
                    bill['currency'], bill['source_kind'], bill['source_id']))
        db.execute('INSERT OR IGNORE INTO cashflow_payment_links (user_id, transaction_id, bill_key, is_refund) VALUES (?, ?, ?, ?)',
                   (str(user_id), tx_id, bill_key, int(refund)))
        # Recheck after INSERT: a concurrent request must not silently steal a payment.
        actual = db.execute('SELECT bill_key FROM cashflow_payment_links WHERE user_id = ? AND transaction_id = ?',
                            (str(user_id), tx_id)).fetchone()
        if actual['bill_key'] != bill_key:
            raise ValueError('Transaction is already linked to another bill')
    db.commit()
    bump_data_version('cashflow_payment_updated', user_id=str(user_id), db=db)
    return {'ok': True}


def confirm_scheduled_transaction(user_id, tx_id, db, undo=False):
    base = currency_code(get_user_settings(user_id, db=db).get('default_currency'))
    tx = next(iter(_transactions(user_id, db, base, transaction_id=tx_id)), None)
    if not tx or tx.get('source') != 'recurring' or tx['type'] not in ('income', 'expense'):
        raise ValueError('Scheduled transaction not found')
    if undo:
        db.execute('DELETE FROM cashflow_transaction_confirmations WHERE user_id = ? AND transaction_id = ?', (str(user_id), tx_id))
    else:
        db.execute('INSERT OR IGNORE INTO cashflow_transaction_confirmations VALUES (?, ?)', (str(user_id), tx_id))
    db.commit()
    bump_data_version('cashflow_transaction_confirmed', {'transaction_id': tx_id, 'undo': undo}, user_id=str(user_id), db=db)
    return {'ok': True}


def record_source_payment(user_id, kind, source, due_date, tx_id, db):
    """Bridge an existing explicit match into the bill ledger before renewal.

    Returns 'retry' on a repeated transaction so legacy counters do not
    advance twice. Counter-only operations deliberately never call this helper.
    """
    base = currency_code(get_user_settings(user_id, db=db).get('default_currency'))
    source = dict(source)
    tx = next(iter(_transactions(user_id, db, base, transaction_id=tx_id)), None)
    if not tx or not _expense(tx) or money(tx['amount']) <= 0:
        raise ValueError('A live payment transaction is required')
    if kind == 'subscription':
        expected_currency = currency_code(source['currency'])
        value = source['cost']
    else:
        account = db.execute('SELECT currency FROM accounts WHERE user_id = ? AND id = ?',
                             (str(user_id), source.get('account_id'))).fetchone()
        expected_currency = currency_code(account['currency'] if account else base)
        value = source['monthly_amount']
    if tx['currency'] != expected_currency:
        # Legacy matching supports unpriced foreign subscriptions. Preserve its
        # schedule operation, but do NOT turn a guessed FX amount into payment.
        logger.warning('Cashflow payment not linked: currencies differ for %s source %s', kind, source['id'])
        return 'unpriced'
    existing = db.execute('SELECT bill_key FROM cashflow_payment_links WHERE user_id = ? AND transaction_id = ?',
                          (str(user_id), tx_id)).fetchone()
    if existing:
        saved = db.execute('SELECT source_kind, source_id FROM cashflow_bills WHERE user_id = ? AND bill_key = ?',
                           (str(user_id), existing['bill_key'])).fetchone()
        if saved and saved['source_kind'] == kind and saved['source_id'] == source['id']:
            return 'retry'
        raise ValueError('Payment already linked to another bill')
    bill = _bill(kind, source, date.fromisoformat(due_date[:10]), value, expected_currency)
    db.execute('''INSERT OR IGNORE INTO cashflow_bills
        (user_id,bill_key,title,due_date,amount,currency,source_kind,source_id) VALUES (?,?,?,?,?,?,?,?)''',
               (str(user_id), bill['bill_key'], bill['title'], bill['due_date'], bill['amount'],
                bill['currency'], kind, source['id']))
    update_bill_payment(user_id, due_date[:7], bill['bill_key'], tx_id, db)
    total = sum((money(row['amount']) * (-1 if row['is_refund'] and row['type'] == 'income' else 1)
                 for row in db.execute('''SELECT t.amount, t.type, p.is_refund
                     FROM cashflow_payment_links p JOIN transactions t ON t.id=p.transaction_id AND t.user_id=p.user_id
                     WHERE p.user_id=? AND p.bill_key=? AND t.deleted_at IS NULL''',
                                       (str(user_id), bill['bill_key'])).fetchall()), Decimal(0))
    return 'paid' if total >= money(value) else 'partial'


def update_bill(user_id, month, bill_key, db, cancelled=False, duplicate_of=None, amount=None):
    """Explicit correction, never fuzzy cross-module deduplication."""
    month_bounds(month)
    base = currency_code(get_user_settings(user_id, db=db).get('default_currency'))
    start, end = month_bounds(month)
    bills = _scheduled_bills(user_id, start, end, db, base)
    for raw in db.execute('SELECT * FROM cashflow_bills WHERE user_id = ?', (str(user_id),)).fetchall():
        bills[raw['bill_key']] = dict(raw)
    bill = bills.get(bill_key)
    if not bill:
        raise ValueError('Bill not found')
    if duplicate_of:
        target = bills.get(duplicate_of)
        if (not target or target['cancelled'] or target['duplicate_of'] or target['currency'] != bill['currency']
                or target['due_date'][:7] != bill['due_date'][:7] or duplicate_of == bill_key):
            raise ValueError('Select a different active bill in the same currency')
        if db.execute('SELECT 1 FROM cashflow_payment_links WHERE user_id = ? AND bill_key = ?',
                      (str(user_id), bill_key)).fetchone():
            raise ValueError('Unlink payments before marking a duplicate')
    new_amount = money(amount) if amount is not None else money(bill['amount'])
    if new_amount < 0:
        raise ValueError('Bill amount cannot be negative')
    db.execute('''INSERT INTO cashflow_bills
        (user_id, bill_key, title, due_date, amount, currency, source_kind, source_id, cancelled, duplicate_of)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, bill_key) DO UPDATE SET amount=excluded.amount,
        cancelled=excluded.cancelled, duplicate_of=excluded.duplicate_of''',
               (str(user_id), bill_key, bill['title'], bill['due_date'], str(new_amount), bill['currency'],
                bill['source_kind'], bill['source_id'], int(cancelled), duplicate_of))
    db.commit()
    bump_data_version('cashflow_bill_updated', {'bill_key': bill_key, 'cancelled': cancelled,
                                             'duplicate_of': duplicate_of}, user_id=str(user_id), db=db)
    return {'ok': True}
