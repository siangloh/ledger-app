from functools import wraps
from flask import request, session, redirect, url_for, jsonify


def is_ajax_request():
    return (
        request.is_json
        or request.headers.get('X-Requested-With') == 'XMLHttpRequest'
        or 'application/json' in request.headers.get('Accept', '')
    )


def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get('logged_in') or not session.get('user_id'):
            if is_ajax_request() or request.headers.get('X-Requested-With') == 'InstantNav':
                return jsonify({'error': 'unauthorized', 'redirect': url_for('auth.login')}), 401
            target_next = request.full_path if request.full_path and request.full_path != '/?' else '/'
            return redirect(url_for('auth.login', next=target_next))
        return f(*args, **kwargs)
    return decorated_function
