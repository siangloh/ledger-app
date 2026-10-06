import re
import uuid
from datetime import datetime
from flask import Blueprint, request, redirect, url_for, render_template, flash, jsonify, session
from werkzeug.security import generate_password_hash, check_password_hash

import logging

from core.db import get_db, init_user_default_categories, get_user_settings
from core.config import get_app_password
from core.extensions import csrf
from core.i18n import set_current_locale
from services.auth_service import request_password_reset_otp, verify_and_reset_password

logger = logging.getLogger(__name__)

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
        email = (request.form.get('email') or '').strip()
        password = request.form.get('password', '')
        confirm_password = request.form.get('confirm_password', '')

        if not username:
            flash('用户名不能为空', 'error')
            return render_template('register.html', username=username, email=email)
        if len(username) < 3 or len(username) > 30:
            flash('用户名长度需在 3 到 30 个字符之间', 'error')
            return render_template('register.html', username=username, email=email)
        if not re.match(r'^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$', username):
            flash('用户名仅支持中文、字母、数字及下划线', 'error')
            return render_template('register.html', username=username, email=email)
        if email:
            if not re.match(r'^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$', email):
                flash('邮箱格式不正确，请输入有效的电子邮箱地址', 'error')
                return render_template('register.html', username=username, email=email)
        if not password or len(password) < 6:
            flash('密码长度至少需要 6 个字符', 'error')
            return render_template('register.html', username=username, email=email)
        if not re.search(r'[A-Z]', password):
            flash('密码需包含至少一个大写字母 (A-Z)', 'error')
            return render_template('register.html', username=username, email=email)
        if not re.search(r'[a-z]', password):
            flash('密码需包含至少一个小写字母 (a-z)', 'error')
            return render_template('register.html', username=username, email=email)
        if not re.search(r'[0-9]', password):
            flash('密码需包含至少一个数字 (0-9)', 'error')
            return render_template('register.html', username=username, email=email)
        if not re.search(r'[^a-zA-Z0-9]', password):
            flash('密码需包含至少一个特殊符号（如 !@#$%^&* 等）', 'error')
            return render_template('register.html', username=username, email=email)
        if password != confirm_password:
            flash('两次输入的密码不一致', 'error')
            return render_template('register.html', username=username, email=email)

        db = get_db()
        existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
        if existing:
            flash('该用户名已被注册，请直接登录或换一个用户名', 'error')
            return render_template('register.html', username=username, email=email)

        if email:
            existing_email = db.execute('SELECT id FROM users WHERE LOWER(email) = LOWER(?)', (email,)).fetchone()
            if existing_email:
                flash('该邮箱已被注册绑定，请直接登录或使用其他邮箱', 'error')
                return render_template('register.html', username=username, email=email)

        user_id = str(uuid.uuid4())
        pw_hash = generate_password_hash(password)
        now_str = datetime.now().isoformat()
        db.execute(
            'INSERT INTO users (id, username, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)',
            (user_id, username, email or None, pw_hash, now_str)
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
            try:
                user_settings = get_user_settings(user['id'], db=db)
                if user_settings and user_settings.get('language'):
                    set_current_locale(user_settings['language'])
            except Exception as e:
                logger.debug("Failed to set user locale on login: %s", e)
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
            try:
                user_settings = get_user_settings(admin_id, db=db)
                if user_settings and user_settings.get('language'):
                    set_current_locale(user_settings['language'])
            except Exception as e:
                logger.debug("Failed to set admin locale on login: %s", e)
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


@auth_bp.route('/forgot-password', methods=['GET', 'POST'], endpoint='forgot_password')
@csrf.exempt
def forgot_password():
    """忘记密码页面与表单提交处理"""
    if session.get('logged_in') and session.get('user_id'):
        return redirect(url_for('index'))

    if request.method == 'POST':
        identifier = (request.form.get('identifier') or '').strip()
        otp_code = (request.form.get('otp_code') or '').strip()
        new_password = request.form.get('new_password', '')
        confirm_password = request.form.get('confirm_password', '')

        db = get_db()
        ok, msg = verify_and_reset_password(
            identifier, otp_code, new_password, confirm_password, db=db
        )
        if ok:
            flash(msg, 'success')
            return redirect(url_for('login'))
        else:
            flash(msg, 'error')
            return render_template(
                'forgot_password.html',
                identifier=identifier,
                otp_code=otp_code
            ), 400

    return render_template('forgot_password.html')


@auth_bp.route('/api/auth/send-otp', methods=['POST'], endpoint='api_send_otp')
@csrf.exempt
def api_send_otp():
    """发送邮箱 OTP 验证码 API"""
    data = request.get_json(silent=True) or request.form or {}
    identifier = (data.get('identifier') or '').strip()

    if not identifier:
        return jsonify({'ok': False, 'message': '请输入用户名或绑定的邮箱地址'}), 400

    db = get_db()
    ok, msg, payload = request_password_reset_otp(identifier, db=db)
    if not ok:
        return jsonify({'ok': False, 'message': msg}), 400

    return jsonify({
        'ok': True,
        'message': msg,
        'data': payload
    })


@auth_bp.route('/api/auth/verify-reset', methods=['POST'], endpoint='api_verify_reset')
@csrf.exempt
def api_verify_reset():
    """验证 OTP 并重置密码 API"""
    data = request.get_json(silent=True) or request.form or {}
    identifier = (data.get('identifier') or '').strip()
    otp_code = (data.get('otp_code') or '').strip()
    new_password = data.get('new_password', '')
    confirm_password = data.get('confirm_password', '')

    db = get_db()
    ok, msg = verify_and_reset_password(
        identifier, otp_code, new_password, confirm_password, db=db
    )
    if not ok:
        return jsonify({'ok': False, 'message': msg}), 400

    return jsonify({
        'ok': True,
        'message': msg
    })
