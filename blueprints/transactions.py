import os
import re
import uuid
import sqlite3
import logging
from calendar import monthrange
from datetime import datetime, date
import pandas as pd
from flask import Blueprint, request, redirect, url_for, render_template, flash, jsonify

logger = logging.getLogger(__name__)

from core.config import UPLOAD_DIR
from core.db import (
    get_db,
    get_current_user_id,
    bump_data_version,
    get_categories,
    LATEST_EVENT,
    DATA_VERSION
)
from core.auth import is_ajax_request
from core.utils import (
    money_filter,
    get_savings_breakdown,
    get_category_budget_status,
    check_and_record_budget_alerts,
    generate_due_recurring
)
from services.ai_service import parse_nlp_with_llm
from services.notification_service import parse_nlp_text

transactions_bp = Blueprint('transactions', __name__)


# ---------------------------------------------------------------------------
# 交易记录：新增 / 编辑 / 删除 / 列表
# ---------------------------------------------------------------------------

@transactions_bp.route('/transactions/add', methods=['POST'], endpoint='add_transaction')
def add_transaction():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    try:
        amount = float(f.get('amount') or 0)
    except ValueError:
        amount = 0
    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '金额必须是大于 0 的数字'}), 400
        flash('金额必须是大于 0 的数字', 'error')
        return redirect(url_for('index'))

    tx_type = f.get('type')
    group_name = f.get('group_name') or None
    if tx_type != 'income':
        group_name = None

    from_savings = 1 if (tx_type == 'expense' and f.get('from_savings') in ('1', 'true', 'on')) else 0
    from_savings_category = (f.get('from_savings_category') or '').strip() if from_savings else None
    if from_savings and not from_savings_category:
        from_savings_category = f.get('category') or '储蓄'

    tx_date = f.get('date') or date.today().isoformat()
    tags = (f.get('tags') or '').strip()
    cur = db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at, from_savings, from_savings_category, tags) '
        'VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',
        (user_id, tx_date, tx_type, group_name, f.get('category'), amount, f.get('note', ''),
         f.get('source', 'manual'), datetime.now().isoformat(), from_savings, from_savings_category, tags)
    )
    db.commit()
    bump_data_version('add', {
        'id': cur.lastrowid,
        'note': f.get('note', ''),
        'amount': amount,
        'category': f.get('category'),
        'type': tx_type,
        'date': tx_date,
        'from_savings': from_savings,
        'from_savings_category': from_savings_category,
        'source': f.get('source', 'manual'),
        'user_id': user_id
    })

    budget_alert = None
    if tx_type == 'expense':
        budget_alert = check_and_record_budget_alerts(db, user_id, f.get('category'), tx_date[:7])
        if budget_alert and not is_ajax_request():
            flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

    if is_ajax_request():
        savings_pool, total_pool = get_savings_breakdown(db, user_id)
        # 实时计算当月的最新收入构成与支出分类占比，供前端即时局部更新图表与图例
        tx_month = tx_date[:7]
        y, m = map(int, tx_month.split('-'))
        ld = monthrange(y, m)[1]
        m_start = f'{tx_month}-01'
        m_end = f'{tx_month}-{ld:02d}'
        m_rows = db.execute(
            'SELECT type, group_name, category, amount FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ?',
            (user_id, m_start, m_end)
        ).fetchall()
        m_inc = {'main': 0.0, 'side': 0.0}
        m_exp = {}
        for r in m_rows:
            if r['type'] == 'income':
                gn = r['group_name'] or 'main'
                m_inc[gn] = m_inc.get(gn, 0.0) + r['amount']
            elif r['type'] == 'expense':
                c = r['category'] or '其他'
                m_exp[c] = m_exp.get(c, 0.0) + r['amount']

        return jsonify({
            'ok': True,
            'message': '记录已添加',
            'version': DATA_VERSION,
            'transaction': {
                'id': cur.lastrowid,
                'date': tx_date,
                'type': tx_type,
                'group_name': group_name,
                'category': f.get('category'),
                'amount': amount,
                'note': f.get('note', ''),
                'from_savings': from_savings,
                'from_savings_category': from_savings_category
            },
            'savings_pool': savings_pool,
            'total_savings_pool': total_pool,
            'chart_data': {
                'month': tx_month,
                'income': {
                    'labels': ['主业收入', '副业收入'],
                    'values': [round(m_inc.get('main', 0.0), 2), round(m_inc.get('side', 0.0), 2)]
                },
                'expense': {
                    'labels': [k for k, _ in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)],
                    'values': [round(v, 2) for _, v in sorted(m_exp.items(), key=lambda x: x[1], reverse=True)]
                }
            },
            'budget_alert': budget_alert
        })

    flash('记录已添加', 'success')
    return redirect(url_for('index', month=tx_date[:7]))


@transactions_bp.route('/records', endpoint='records')
def records():
    user_id = get_current_user_id()
    db = get_db()
    start = request.args.get('start', '')
    end = request.args.get('end', '')
    type_ = request.args.get('type', '')
    category = request.args.get('category', '')

    query = 'SELECT * FROM transactions WHERE user_id = ?'
    params = [user_id]
    if start:
        query += ' AND date >= ?'
        params.append(start)
    if end:
        query += ' AND date <= ?'
        params.append(end)
    if type_:
        query += ' AND type = ?'
        params.append(type_)
    if category:
        query += ' AND category = ?'
        params.append(category)
    query += ' ORDER BY date DESC, id DESC'

    rows = db.execute(query, params).fetchall()
    all_categories = db.execute(
        'SELECT DISTINCT category FROM transactions WHERE user_id = ? AND category IS NOT NULL ORDER BY category',
        (user_id,)
    ).fetchall()

    total_income = sum(r['amount'] for r in rows if r['type'] == 'income')
    total_expense = sum(r['amount'] for r in rows if r['type'] == 'expense')
    total_savings = sum(r['amount'] for r in rows if r['type'] == 'savings')

    latest_id = LATEST_EVENT['data'].get('id') if LATEST_EVENT and LATEST_EVENT.get('data') else None

    if request.args.get('partial') == '1':
        return render_template(
            'partials/records_content.html',
            rows=rows,
            total_income=total_income,
            total_expense=total_expense,
            total_savings=total_savings,
            latest_id=latest_id
        )

    return render_template(
        'records.html',
        rows=rows, start=start, end=end, type=type_, category=category,
        categories=[c['category'] for c in all_categories],
        total_income=total_income, total_expense=total_expense, total_savings=total_savings,
        latest_id=latest_id
    )


@transactions_bp.route('/records/<int:tx_id>/edit', methods=['GET', 'POST'], endpoint='edit_record')
def edit_record(tx_id):
    user_id = get_current_user_id()
    db = get_db()
    if request.method == 'POST':
        f = request.form
        tx_type = f.get('type')
        group_name = f.get('group_name') or None
        if tx_type != 'income':
            group_name = None
        try:
            amount = float(f.get('amount') or 0)
        except ValueError:
            amount = 0
        new_category = f.get('category')

        # 检查是否为 auto_track 来源且修改了分类，若是则记忆商户-分类映射覆盖
        current_tx = db.execute('SELECT source, note, category FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id)).fetchone()
        if current_tx and current_tx['source'] == 'auto_track' and current_tx['note'] and new_category:
            merchant_note = current_tx['note'].strip()
            if merchant_note:
                now_str = datetime.now().isoformat()
                db.execute(
                    'INSERT OR REPLACE INTO merchant_category_overrides (merchant_note, category, updated_at) VALUES (?, ?, ?)',
                    (merchant_note, new_category, now_str)
                )

        from_savings = 1 if (tx_type == 'expense' and f.get('from_savings') in ('1', 'true', 'on')) else 0
        from_savings_category = (f.get('from_savings_category') or '').strip() if from_savings else None
        if from_savings and not from_savings_category:
            from_savings_category = f.get('category') or '储蓄'

        tags = (f.get('tags') or '').strip()
        db.execute(
            'UPDATE transactions SET date=?, type=?, group_name=?, category=?, amount=?, note=?, from_savings=?, from_savings_category=?, tags=? WHERE id=? AND user_id=?',
            (f.get('date'), tx_type, group_name, new_category, amount, f.get('note', ''), from_savings, from_savings_category, tags, tx_id, user_id)
        )
        db.commit()
        bump_data_version('edit', {'id': tx_id, 'from_savings': from_savings, 'from_savings_category': from_savings_category})

        budget_alert = None
        if tx_type == 'expense':
            budget_alert = check_and_record_budget_alerts(db, user_id, new_category, (f.get('date') or '')[:7])
            if budget_alert and not is_ajax_request():
                flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

        if is_ajax_request():
            return jsonify({'ok': True, 'message': '记录已更新', 'budget_alert': budget_alert})

        flash('记录已更新', 'success')
        return redirect(url_for('records'))

    row = db.execute('SELECT * FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id)).fetchone()
    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    savings_pool_by_category, total_savings_pool = get_savings_breakdown(db, user_id)
    return render_template(
        'edit_record.html', row=row,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        savings_pool_by_category=savings_pool_by_category,
    )


@transactions_bp.route('/records/<int:tx_id>/delete', methods=['POST'], endpoint='delete_record')
def delete_record(tx_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute('DELETE FROM transactions WHERE id=? AND user_id=?', (tx_id, user_id))
    db.commit()
    bump_data_version('delete', {'id': tx_id})

    if is_ajax_request():
        return jsonify({'ok': True, 'message': '记录已删除', 'id': tx_id})

    flash('记录已删除', 'success')
    return redirect(url_for('records'))


@transactions_bp.route('/records/batch-delete', methods=['POST'], endpoint='batch_delete_records')
def batch_delete_records():
    user_id = get_current_user_id()
    db = get_db()
    ids = request.form.getlist('ids')
    if not ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中任何记录'}), 400
        flash('未选中任何记录', 'error')
        return redirect(url_for('records'))

    # 安全地过滤数字 ID
    valid_ids = []
    for i in ids:
        try:
            valid_ids.append(int(i))
        except ValueError:
            pass

    if valid_ids:
        placeholders = ','.join('?' * len(valid_ids))
        db.execute(f'DELETE FROM transactions WHERE user_id = ? AND id IN ({placeholders})', [user_id] + valid_ids)
        db.commit()
        bump_data_version('batch_delete', {'count': len(valid_ids)})

        if is_ajax_request():
            return jsonify({'ok': True, 'message': f'成功批量删除 {len(valid_ids)} 条记录', 'deleted_ids': valid_ids})

        flash(f'成功批量删除 {len(valid_ids)} 条记录', 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中有效的记录'}), 400
        flash('未选中有效的记录', 'error')

    return redirect(url_for('records'))


@transactions_bp.route('/records/batch-edit', methods=['POST'], endpoint='batch_edit_records')
def batch_edit_records():
    """批量修改记录的分类与类型"""
    user_id = get_current_user_id()
    db = get_db()
    ids = request.form.getlist('ids')
    new_type = request.form.get('type')
    new_category = request.form.get('category')
    group_name = request.form.get('group_name') or None
    if new_type and new_type != 'income':
        group_name = None

    if not ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中任何记录'}), 400
        flash('未选中任何记录', 'error')
        return redirect(url_for('records'))

    valid_ids = []
    for i in ids:
        try:
            valid_ids.append(int(i))
        except ValueError:
            pass

    if not valid_ids:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未选中有效的记录'}), 400
        flash('未选中有效的记录', 'error')
        return redirect(url_for('records'))

    if not new_type and not new_category:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未指定需要修改的分类或类型'}), 400
        flash('未指定需要修改的分类或类型', 'warning')
        return redirect(url_for('records'))

    updates = []
    params = []
    if new_type:
        updates.append("type = ?")
        params.append(new_type)
        if new_type == 'income' and group_name:
            updates.append("group_name = ?")
            params.append(group_name)
        elif new_type != 'income':
            updates.append("group_name = NULL")

    if new_category:
        updates.append("category = ?")
        params.append(new_category)

    set_clause = ", ".join(updates)
    placeholders = ','.join('?' * len(valid_ids))
    sql = f"UPDATE transactions SET {set_clause} WHERE user_id = ? AND id IN ({placeholders})"
    db.execute(sql, params + [user_id] + valid_ids)
    db.commit()
    bump_data_version('batch_edit', {'count': len(valid_ids)})

    # 批量修改后，对涉及到的每个支出分类各检查一次是否需要发出超支提醒
    budget_alerts = []
    if new_type is None or new_type == 'expense':
        touched_cats = db.execute(
            f"SELECT DISTINCT category FROM transactions WHERE user_id = ? AND type = 'expense' AND id IN ({placeholders})",
            [user_id] + valid_ids
        ).fetchall()
        this_month = date.today().strftime('%Y-%m')
        for row in touched_cats:
            alert = check_and_record_budget_alerts(db, user_id, row['category'], this_month)
            if alert:
                budget_alerts.append(alert)
                if not is_ajax_request():
                    flash(alert['message'], 'warning' if alert['threshold'] < 100 else 'error')

    if is_ajax_request():
        return jsonify({'ok': True, 'message': f'成功批量修改 {len(valid_ids)} 条记录', 'edited_ids': valid_ids, 'budget_alerts': budget_alerts})

    flash(f'成功批量修改 {len(valid_ids)} 条记录', 'success')
    return redirect(url_for('records'))


# ---------------------------------------------------------------------------
# 自然语言快速记账
# ---------------------------------------------------------------------------

@transactions_bp.route('/nlp/parse', methods=['POST'], endpoint='nlp_parse')
def nlp_parse():
    text = request.form.get('text', '').strip()
    if not text:
        return {'ok': False, 'message': '请输入内容后再点智能解析'}

    # 1. 优先调用 LLM 深度智能解析
    llm_parsed = parse_nlp_with_llm(text)
    if llm_parsed and llm_parsed.get('amount'):
        return {
            'ok': True,
            'parsed': llm_parsed,
            'source': 'llm',
            'warnings': [],
            'original_text': text
        }

    # 2. 回退到本地规则解析器
    parsed, warnings = parse_nlp_text(text)
    if parsed is None:
        return {'ok': False, 'message': '解析失败：' + '；'.join(warnings) + '。请改用下方快速录入表单手动填写。'}

    return {'ok': True, 'parsed': parsed, 'source': 'rule', 'warnings': warnings, 'original_text': text}


# ---------------------------------------------------------------------------
# 还款记录冲减与近期支出
# ---------------------------------------------------------------------------

@transactions_bp.route('/api/transactions/recent-expenses', methods=['GET'], endpoint='api_recent_expenses')
def api_recent_expenses():
    """获取用户近期支出列表，供还款记录手动冲抵选择"""
    user_id = get_current_user_id()
    if not user_id:
        return jsonify({'ok': False, 'message': '未登录'}), 401
    db = get_db()
    rows = db.execute('''
        SELECT id, date, category, amount, note, source 
        FROM transactions 
        WHERE user_id = ? AND type = 'expense' 
        ORDER BY date DESC, created_at DESC, id DESC LIMIT 15
    ''', (user_id,)).fetchall()
    expenses = [
        {
            'id': r['id'],
            'date': r['date'],
            'category': r['category'],
            'amount': float(r['amount']),
            'note': r['note'] or '',
            'source': r['source']
        }
        for r in rows
    ]
    return jsonify({'ok': True, 'expenses': expenses})


@transactions_bp.route('/api/transactions/<int:tx_id>/offset', methods=['POST'], endpoint='api_offset_transaction')
def api_offset_transaction(tx_id):
    """手动将某笔收入/朋友还款记录冲抵指定的一笔历史支出"""
    user_id = get_current_user_id()
    if not user_id:
        return jsonify({'ok': False, 'message': '未登录'}), 401

    db = get_db()
    income_tx = db.execute('SELECT * FROM transactions WHERE id = ? AND user_id = ?', (tx_id, user_id)).fetchone()
    if not income_tx:
        return jsonify({'ok': False, 'message': '未找到该笔还款/收入记录'}), 404
    if income_tx['type'] != 'income':
        return jsonify({'ok': False, 'message': '只有收入记录可以冲抵支出'}), 400

    data = request.get_json(silent=True) or request.form
    target_expense_id = data.get('target_expense_id')
    if not target_expense_id:
        return jsonify({'ok': False, 'message': '请选择要冲抵的目标支出'}), 400

    target_expense = db.execute('SELECT * FROM transactions WHERE id = ? AND user_id = ?', (target_expense_id, user_id)).fetchone()
    if not target_expense:
        return jsonify({'ok': False, 'message': '未找到目标支出记录'}), 404
    if target_expense['type'] != 'expense':
        return jsonify({'ok': False, 'message': '目标记录必须是支出类型'}), 400

    offset_amt = float(income_tx['amount'])
    old_amt = float(target_expense['amount'])
    new_amt = max(0.0, round(old_amt - offset_amt, 2))

    tag = f"[收到还款冲抵 {money_filter(offset_amt)}]"
    old_note = (target_expense['note'] or '').strip()
    new_note = f"{old_note} {tag}".strip()

    # 1. 更新目标支出金额与备注
    db.execute('UPDATE transactions SET amount = ?, note = ? WHERE id = ?', (new_amt, new_note, target_expense_id))
    # 2. 删除当前已冲抵的还款记录，彻底避免 duplicate
    db.execute('DELETE FROM transactions WHERE id = ?', (tx_id,))
    db.commit()

    bump_data_version('transaction_offset', {
        'offset_expense_id': target_expense_id,
        'deleted_income_id': tx_id,
        'new_amount': new_amt,
        'user_id': user_id
    })

    return jsonify({
        'ok': True,
        'message': f"成功冲抵！已从【{target_expense['category']}】支出中扣除 {money_filter(offset_amt)}（现为 {money_filter(new_amt)}）",
        'target_id': target_expense_id,
        'new_amount': new_amt,
        'deleted_id': tx_id
    })


# ---------------------------------------------------------------------------
# 分类与预算管理
# ---------------------------------------------------------------------------

@transactions_bp.route('/categories', endpoint='categories_page')
def categories_page():
    user_id = get_current_user_id()
    db = get_db()
    income_main = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='income' AND group_name='main' ORDER BY id", (user_id,)).fetchall()
    income_side = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='income' AND group_name='side' ORDER BY id", (user_id,)).fetchall()
    expense = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='expense' ORDER BY id", (user_id,)).fetchall()
    savings = db.execute("SELECT * FROM categories WHERE user_id = ? AND type='savings' ORDER BY id", (user_id,)).fetchall()
    budget_status = get_category_budget_status(db, user_id)
    budget_map = {b['category']: b for b in budget_status}
    return render_template(
        'categories.html', income_main=income_main, income_side=income_side, expense=expense, savings=savings,
        budget_map=budget_map,
    )


@transactions_bp.route('/categories/add', methods=['POST'], endpoint='add_category')
def add_category():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    type_ = f.get('type')
    group_name = f.get('group_name') or None
    if type_ != 'income':
        group_name = None
    name = (f.get('name') or '').strip()
    if not name:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '分类名称不能为空'}), 400
        flash('分类名称不能为空', 'error')
        return redirect(url_for('categories_page'))
    color = (f.get('color') or '').strip()
    if color and not re.match(r'^#[0-9a-fA-F]{3,8}$', color):
        color = None
    try:
        db.execute('INSERT INTO categories (user_id, type, group_name, name, color) VALUES (?,?,?,?,?)', (user_id, type_, group_name, name, color))
        db.commit()
        if is_ajax_request():
            return jsonify({'ok': True, 'message': '分类已添加'})
        flash('分类已添加', 'success')
    except sqlite3.IntegrityError:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '该分类已存在'}), 400
        flash('该分类已存在', 'error')
    return redirect(url_for('categories_page'))


@transactions_bp.route('/categories/<int:cat_id>/edit', methods=['POST'], endpoint='edit_category')
def edit_category(cat_id):
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    new_name = (f.get('name') or '').strip()
    new_color = (f.get('color') or '').strip()

    cat = db.execute('SELECT * FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id)).fetchone()
    if not cat:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '分类不存在'}), 404
        flash('分类不存在', 'error')
        return redirect(url_for('categories_page'))

    old_name = cat['name']
    if not new_name:
        new_name = old_name

    if new_color and not re.match(r'^#[0-9a-fA-F]{3,8}$', new_color):
        new_color = None

    try:
        db.execute(
            'UPDATE categories SET name = ?, color = ? WHERE id = ? AND user_id = ?',
            (new_name, new_color, cat_id, user_id)
        )
        if new_name != old_name:
            db.execute('UPDATE transactions SET category = ? WHERE user_id = ? AND category = ?', (new_name, user_id, old_name))
            db.execute('UPDATE category_budgets SET category = ? WHERE user_id = ? AND category = ?', (new_name, user_id, old_name))
            db.execute('UPDATE recurring_rules SET category = ? WHERE user_id = ? AND category = ?', (new_name, user_id, old_name))

        db.commit()
        bump_data_version('category_edit', {'category': new_name, 'user_id': user_id})
        if is_ajax_request():
            return jsonify({'ok': True, 'message': '分类已更新', 'id': cat_id, 'name': new_name, 'color': new_color})
        flash('分类已更新', 'success')
    except sqlite3.IntegrityError:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '已存在同名分类'}), 400
        flash('已存在同名分类', 'error')
    except Exception as e:
        logger.error('Failed to edit category %s: %s', cat_id, e, exc_info=True)
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '更新分类失败'}), 500
        flash('更新分类失败', 'error')
    return redirect(url_for('categories_page'))


@transactions_bp.route('/categories/<int:cat_id>/delete', methods=['POST'], endpoint='delete_category')
def delete_category(cat_id):
    user_id = get_current_user_id()
    db = get_db()
    cat = db.execute('SELECT name FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id)).fetchone()
    db.execute('DELETE FROM categories WHERE id = ? AND user_id = ?', (cat_id, user_id))
    if cat:
        db.execute('DELETE FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name']))
    db.commit()
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '分类已删除（历史记录中的旧数据不受影响）', 'id': cat_id})
    flash('分类已删除（历史记录中的旧数据不受影响）', 'success')
    return redirect(url_for('categories_page'))


@transactions_bp.route('/categories/<int:cat_id>/budget', methods=['POST'], endpoint='set_category_budget')
def set_category_budget(cat_id):
    """设置或取消某个支出分类的月度预算上限"""
    user_id = get_current_user_id()
    db = get_db()
    cat = db.execute("SELECT name FROM categories WHERE id = ? AND user_id = ? AND type = 'expense'", (cat_id, user_id)).fetchone()
    if not cat:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '分类不存在'}), 404
        flash('分类不存在', 'error')
        return redirect(url_for('categories_page'))

    raw_limit = (request.form.get('monthly_limit') or '').strip()
    now = datetime.now().isoformat()

    if not raw_limit:
        db.execute('DELETE FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name']))
        db.commit()
        bump_data_version('budget', {'category': cat['name'], 'user_id': user_id})
        msg = f'已取消「{cat["name"]}」的月度预算'
    else:
        try:
            limit = float(raw_limit)
        except ValueError:
            limit = -1
        if limit <= 0:
            if is_ajax_request():
                return jsonify({'ok': False, 'message': '预算金额必须是大于 0 的数字'}), 400
            flash('预算金额必须是大于 0 的数字', 'error')
            return redirect(url_for('categories_page'))

        existing = db.execute('SELECT id FROM category_budgets WHERE user_id = ? AND category = ?', (user_id, cat['name'])).fetchone()
        if existing:
            db.execute('UPDATE category_budgets SET monthly_limit = ?, updated_at = ? WHERE id = ?', (limit, now, existing['id']))
        else:
            db.execute(
                'INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at) VALUES (?,?,?,?,?)',
                (user_id, cat['name'], limit, now, now)
            )
        db.commit()
        bump_data_version('budget', {'category': cat['name'], 'limit': limit, 'user_id': user_id})
        msg = f'已设置「{cat["name"]}」的月度预算为 RM{limit:.2f}'

    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg})
    flash(msg, 'success')
    return redirect(url_for('categories_page'))


# ---------------------------------------------------------------------------
# 固定 / 重复收支
# ---------------------------------------------------------------------------

@transactions_bp.route('/recurring', endpoint='recurring_page')
def recurring_page():
    user_id = get_current_user_id()
    db = get_db()
    rules = db.execute('SELECT * FROM recurring_rules WHERE user_id = ? ORDER BY id DESC', (user_id,)).fetchall()
    income_categories = {
        'main': get_categories(db, 'income', 'main', user_id),
        'side': get_categories(db, 'income', 'side', user_id),
    }
    expense_categories = get_categories(db, 'expense', None, user_id)
    savings_categories = get_categories(db, 'savings', None, user_id)
    return render_template(
        'recurring.html', rules=rules,
        income_categories=income_categories,
        expense_categories=expense_categories,
        savings_categories=savings_categories,
        current_month=date.today().strftime('%Y-%m'),
    )


@transactions_bp.route('/recurring/add', methods=['POST'], endpoint='add_recurring')
def add_recurring():
    user_id = get_current_user_id()
    db = get_db()
    f = request.form
    tx_type = f.get('type')
    group_name = f.get('group_name') or None
    if tx_type != 'income':
        group_name = None
    try:
        day = int(f.get('day_of_month') or 1)
    except ValueError:
        day = 1
    day = max(1, min(day, 31))
    try:
        amount = float(f.get('amount') or 0)
    except ValueError:
        amount = 0

    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '金额必须是大于 0 的数字'}), 400
        flash('金额必须是大于 0 的数字', 'error')
        return redirect(url_for('recurring_page'))

    db.execute(
        'INSERT INTO recurring_rules '
        '(user_id, type, group_name, category, amount, note, day_of_month, is_active, last_generated_month, created_at) '
        'VALUES (?,?,?,?,?,?,?,1,NULL,?)',
        (user_id, tx_type, group_name, f.get('category'), amount, f.get('note', ''), day, datetime.now().isoformat())
    )
    db.commit()
    bump_data_version('recurring_add', {'type': tx_type, 'amount': amount, 'day_of_month': day, 'category': f.get('category'), 'user_id': user_id})
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '固定收支规则已添加'})
    flash('固定收支规则已添加', 'success')
    return redirect(url_for('recurring_page'))


@transactions_bp.route('/recurring/<int:rule_id>/delete', methods=['POST'], endpoint='delete_recurring')
def delete_recurring(rule_id):
    user_id = get_current_user_id()
    db = get_db()
    db.execute('DELETE FROM recurring_rules WHERE id = ? AND user_id = ?', (rule_id, user_id))
    db.commit()
    bump_data_version('recurring_delete', {'id': rule_id, 'user_id': user_id})
    if is_ajax_request():
        return jsonify({'ok': True, 'message': '规则已删除', 'id': rule_id})
    flash('规则已删除', 'success')
    return redirect(url_for('recurring_page'))


@transactions_bp.route('/recurring/<int:rule_id>/toggle', methods=['POST'], endpoint='toggle_recurring')
def toggle_recurring(rule_id):
    user_id = get_current_user_id()
    db = get_db()
    row = db.execute('SELECT is_active FROM recurring_rules WHERE id = ? AND user_id = ?', (rule_id, user_id)).fetchone()
    if row:
        new_active = 0 if row['is_active'] else 1
        db.execute('UPDATE recurring_rules SET is_active = ? WHERE id = ? AND user_id = ?', (new_active, rule_id, user_id))
        db.commit()
        bump_data_version('recurring_toggle', {'id': rule_id, 'user_id': user_id})
        msg = '规则已停用' if row['is_active'] else '规则已启用'
        if is_ajax_request():
            return jsonify({'ok': True, 'message': msg, 'id': rule_id, 'is_active': new_active})
        flash(msg, 'success')
    else:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '未找到对应规则'}), 404
    return redirect(url_for('recurring_page'))


@transactions_bp.route('/recurring/generate', methods=['POST'], endpoint='manual_generate_recurring')
def manual_generate_recurring():
    user_id = get_current_user_id()
    count = generate_due_recurring(user_id)
    if count:
        bump_data_version('recurring_generate', {'count': count, 'user_id': user_id})
        msg = f'已生成 {count} 条本月固定收支记录'
    else:
        msg = '本月固定收支已全部生成，无需重复生成'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'count': count})
    flash(msg, 'success')
    return redirect(url_for('recurring_page'))


# ---------------------------------------------------------------------------
# 批量导入 (CSV / Excel)
# ---------------------------------------------------------------------------

def read_import_file(path):
    if path.lower().endswith('.csv'):
        return pd.read_csv(path, dtype=str)
    return pd.read_excel(path, dtype=str)


@transactions_bp.route('/import', endpoint='import_page')
def import_page():
    return render_template('import.html')


@transactions_bp.route('/import/upload', methods=['POST'], endpoint='import_upload')
def import_upload():
    file = request.files.get('file')
    if not file or file.filename == '':
        flash('请选择要导入的文件', 'error')
        return redirect(url_for('import_page'))

    ext = os.path.splitext(file.filename)[1].lower()
    if ext not in ('.csv', '.xlsx', '.xls'):
        flash('仅支持 .csv / .xlsx / .xls 文件', 'error')
        return redirect(url_for('import_page'))

    token = uuid.uuid4().hex
    saved_path = os.path.join(UPLOAD_DIR, token + ext)
    file.save(saved_path)

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        os.remove(saved_path)
        flash(f'文件读取失败：{e}', 'error')
        return redirect(url_for('import_page'))

    if df.empty:
        os.remove(saved_path)
        flash('文件中没有数据', 'error')
        return redirect(url_for('import_page'))

    columns = df.columns.tolist()
    preview_rows = df.head(10).fillna('').values.tolist()

    db = get_db()
    user_id = get_current_user_id()
    expense_categories = get_categories(db, 'expense', None, user_id)
    income_categories_flat = get_categories(db, 'income', 'main', user_id) + get_categories(db, 'income', 'side', user_id)

    return render_template(
        'import_preview.html',
        token=token, ext=ext, columns=columns, preview_rows=preview_rows, row_count=len(df),
        expense_categories=expense_categories, income_categories_flat=income_categories_flat,
    )


@transactions_bp.route('/import/confirm', methods=['POST'], endpoint='import_confirm')
def import_confirm():
    f = request.form
    token = f.get('token') or ''
    ext = f.get('ext') or ''
    is_valid_token = bool(re.fullmatch(r'[0-9a-f]{32}', token))
    is_valid_ext = ext in ('.csv', '.xlsx', '.xls')
    saved_path = os.path.join(UPLOAD_DIR, token + ext) if is_valid_token and is_valid_ext else None

    if not saved_path or not os.path.exists(saved_path):
        flash('导入会话已过期，请重新上传文件', 'error')
        return redirect(url_for('import_page'))

    try:
        df = read_import_file(saved_path)
    except Exception as e:
        flash(f'文件读取失败：{e}', 'error')
        return redirect(url_for('import_page'))

    date_col = f.get('date_col')
    amount_col = f.get('amount_col')
    type_mode = f.get('type_mode')  # sign / fixed_expense / fixed_income / column
    type_col = f.get('type_col')
    category_col = f.get('category_col')
    note_col = f.get('note_col')
    default_category = (f.get('default_category') or '未分类').strip()
    default_group = f.get('default_group') or 'main'
    date_format = f.get('date_format') or None

    user_id = get_current_user_id()
    db = get_db()
    inserted = 0
    skipped = 0
    now = datetime.now().isoformat()
    touched_expense_cat_months = set()

    for _, row in df.iterrows():
        try:
            raw_date = str(row[date_col]).strip()
            if date_format:
                dt = datetime.strptime(raw_date, date_format)
            else:
                dt = pd.to_datetime(raw_date)
            tx_date = dt.strftime('%Y-%m-%d')

            raw_amount = str(row[amount_col])
            cleaned = re.sub(r'[^\d.\-]', '', raw_amount)
            amount = float(cleaned)

            if type_mode == 'fixed_expense':
                tx_type = 'expense'
            elif type_mode == 'fixed_income':
                tx_type = 'income'
            elif type_mode == 'column' and type_col:
                raw_type = str(row[type_col]).strip()
                tx_type = 'income' if ('收入' in raw_type or raw_type.lower() == 'income') else 'expense'
            else:  # sign
                tx_type = 'income' if amount >= 0 else 'expense'

            amount = abs(amount)
            if amount == 0:
                skipped += 1
                continue

            group_name = default_group if tx_type == 'income' else None
            category = default_category
            if category_col:
                raw_cat = str(row[category_col]).strip()
                if raw_cat:
                    category = raw_cat
            note = str(row[note_col]).strip() if note_col else ''

            db.execute(
                'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
                'VALUES (?,?,?,?,?,?,?,?,?)',
                (user_id, tx_date, tx_type, group_name, category, amount, note, 'import', now)
            )
            inserted += 1
            if tx_type == 'expense':
                touched_expense_cat_months.add((category, tx_date[:7]))
        except Exception:
            skipped += 1
            continue

    db.commit()
    os.remove(saved_path)

    for cat, cat_month in touched_expense_cat_months:
        alert = check_and_record_budget_alerts(db, user_id, cat, cat_month)
        if alert:
            flash(alert['message'], 'warning' if alert['threshold'] < 100 else 'error')

    flash(f'导入完成：成功 {inserted} 条，跳过 {skipped} 条', 'success')
    return redirect(url_for('records'))
