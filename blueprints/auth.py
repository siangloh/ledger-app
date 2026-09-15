import re
import uuid
from datetime import datetime
from flask import Blueprint, request, redirect, url_for, render_template, flash, jsonify, session
from werkzeug.security import generate_password_hash, check_password_hash

from core.db import get_db, init_user_default_categories
from core.config import get_app_password
from core.extensions import csrf

auth_bp = Blueprint('auth', __name__)


@auth_bp.route('/api/check-username', endpoint='api_check_username')
def api_check_username():
    username = (request.args.get('username') or '').strip()
    if not username:
        return jsonify({'ok': False, 'available': False, 'message': '请输入用户名'})
    if len(username) < 3:
        return jsonify({'ok': False, 'available': False, 'message': f'用户名太短，至少需 3 个字符（当前 {len(username)} 个）'})
    if len(username) > 30:
        return jsonify({'ok': False, 'available': False, 'message': '用户名不能超过 30 个字符'})
    if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
        return jsonify({'ok': False, 'available': False, 'message': '仅支持中文、英文字母、数字、下划线及连字符'})
    db = get_db()
    existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
    if existing:
        return jsonify({'ok': True, 'available': False, 'message': '该用户名已被占用，请直接登录或更换'})
    return jsonify({'ok': True, 'available': True, 'message': '该用户名可用 ✓'})


@auth_bp.route('/register', methods=['GET', 'POST'], endpoint='register')
@csrf.exempt
def register():
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    if request.method == 'POST':
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password', '')
        confirm_password = request.form.get('confirm_password', '')

        if not username:
            flash('用户名不能为空', 'error')
            return render_template('register.html')
        if len(username) < 3 or len(username) > 30:
            flash('用户名长度需在 3 到 30 个字符之间', 'error')
            return render_template('register.html')
        if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
            flash('用户名仅支持中文、字母、数字及下划线', 'error')
            return render_template('register.html')
        if not password or len(password) < 6:
            flash('密码长度至少需要 6 个字符', 'error')
            return render_template('register.html')
        if not re.search(r'[A-Z]', password):
            flash('密码需包含至少一个大写字母 (A-Z)', 'error')
            return render_template('register.html')
        if not re.search(r'[a-z]', password):
            flash('密码需包含至少一个小写字母 (a-z)', 'error')
            return render_template('register.html')
        if not re.search(r'[0-9]', password):
            flash('密码需包含至少一个数字 (0-9)', 'error')
            return render_template('register.html')
        if not re.search(r'[^a-zA-Z0-9]', password):
            flash('密码需包含至少一个特殊符号（如 !@#$%^&* 等）', 'error')
            return render_template('register.html')
        if password != confirm_password:
            flash('两次输入的密码不一致', 'error')
            return render_template('register.html')

        db = get_db()
        existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
        if existing:
            flash('该用户名已被注册，请直接登录或换一个用户名', 'error')
            return render_template('register.html')

        user_id = str(uuid.uuid4())
        pw_hash = generate_password_hash(password)
        now_str = datetime.now().isoformat()
        db.execute(
            'INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)',
            (user_id, username, pw_hash, now_str)
        )
        db.commit()

        init_user_default_categories(db, user_id)

        session.permanent = True
        session['logged_in'] = True
        session['user_id'] = user_id
        session['username'] = username
        flash(f'注册成功，欢迎使用多账本个人财务系统，{username}！', 'success')
        return redirect(url_for('index'))

    return render_template('register.html')


@auth_bp.route('/login', methods=['GET', 'POST'], endpoint='login')
@csrf.exempt
def login():
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    next_url = request.args.get('next') or request.form.get('next') or url_for('index')
    if not next_url.startswith('/') or next_url.startswith('//'):
        next_url = url_for('index')

    if request.method == 'POST':
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password', '')

        if not username or not password:
            flash('请输入用户名和密码', 'error')
            return render_template('login.html', next=next_url, username=username), 400

        db = get_db()
        user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()

        if user and check_password_hash(user['password_hash'], password):
            session.permanent = True
            session['logged_in'] = True
            session['user_id'] = user['id']
            session['username'] = user['username']
            flash(f'欢迎回来，{user["username"]}！', 'success')
            return redirect(next_url)
        elif username == 'admin' and password == get_app_password(db):
            if user:
                admin_id = user['id']
            else:
                admin_id = str(uuid.uuid4())
                db.execute(
                    'INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)',
                    (admin_id, 'admin', generate_password_hash(password), datetime.now().isoformat())
                )
                db.commit()
            session.permanent = True
            session['logged_in'] = True
            session['user_id'] = admin_id
            session['username'] = 'admin'
            flash('登录成功！', 'success')
            return redirect(next_url)
        else:
            flash('用户名或密码错误，请重试', 'error')
            return render_template('login.html', next=next_url, username=username), 401

    return render_template('login.html', next=next_url)


@auth_bp.route('/logout', methods=['GET', 'POST'], endpoint='logout')
def logout():
    session.clear()
    flash('您已成功退出登录。', 'success')
    return redirect(url_for('login'))
