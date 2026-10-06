# -*- coding: utf-8 -*-
import sqlite3
import pytest
from werkzeug.security import check_password_hash

from services.auth_service import (
    mask_email,
    validate_password_complexity,
    request_password_reset_otp,
    verify_and_reset_password
)


@pytest.fixture
def test_db(tmp_path):
    db_file = tmp_path / "test_auth_otp.db"
    conn = sqlite3.connect(str(db_file))
    conn.row_factory = sqlite3.Row

    # 创建必要表结构
    conn.execute('''
    CREATE TABLE users (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL,
        email TEXT
    );
    ''')
    conn.execute('''
    CREATE TABLE password_resets (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        email TEXT NOT NULL,
        otp_code TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        attempts INTEGER DEFAULT 0,
        used INTEGER DEFAULT 0,
        created_at TEXT NOT NULL
    );
    ''')
    conn.execute('''
    CREATE TABLE system_settings (
        key TEXT PRIMARY KEY,
        value TEXT
    );
    ''')
    conn.commit()
    yield conn
    conn.close()


def test_mask_email():
    assert mask_email("a@b.com") == "a*@b.com"
    assert mask_email("alex@gmail.com") == "a**x@gmail.com"
    assert mask_email("alexander.finance@gmail.com") == "al***ce@gmail.com"
    assert mask_email("") == ""


def test_password_complexity():
    ok, msg = validate_password_complexity("12345")
    assert not ok
    assert "6 个字符" in msg

    ok, msg = validate_password_complexity("alllowercase1!")
    assert not ok
    assert "大写字母" in msg

    ok, msg = validate_password_complexity("ALLUPPERCASE1!")
    assert not ok
    assert "小写字母" in msg

    ok, msg = validate_password_complexity("NoNumberHere!!")
    assert not ok
    assert "数字" in msg

    ok, msg = validate_password_complexity("NoSymbols1234")
    assert not ok
    assert "特殊符号" in msg

    ok, msg = validate_password_complexity("ValidPass123!@#")
    assert ok


def test_request_otp_user_not_found(test_db):
    ok, msg, payload = request_password_reset_otp("nonexistent_user", test_db)
    assert not ok
    assert "未找到" in msg


def test_request_otp_user_no_email(test_db):
    test_db.execute(
        "INSERT INTO users (id, username, password_hash, created_at, email) VALUES (?, ?, ?, ?, ?)",
        ("u1", "alice", "hash1", "2026-01-01T00:00:00", None)
    )
    test_db.commit()

    ok, msg, payload = request_password_reset_otp("alice", test_db)
    assert not ok
    assert "尚未绑定安全邮箱" in msg


def test_request_otp_and_reset_success(test_db):
    test_db.execute(
        "INSERT INTO users (id, username, password_hash, created_at, email) VALUES (?, ?, ?, ?, ?)",
        ("u2", "bob", "old_hash", "2026-01-01T00:00:00", "bob.investor@gmail.com")
    )
    test_db.commit()

    # 1. 申请 OTP 验证码
    ok, msg, payload = request_password_reset_otp("bob", test_db, is_dev=True)
    assert ok
    assert "验证码已成功发送" in msg
    otp_code = payload.get('simulated_otp')
    assert otp_code is not None and len(otp_code) == 6

    # 2. 60秒限流校验
    ok_rate, msg_rate, _ = request_password_reset_otp("bob", test_db, is_dev=True)
    assert not ok_rate
    assert "过于频繁" in msg_rate

    # 3. 错误验证码重试与失败计数
    ok_fail, msg_fail = verify_and_reset_password(
        "bob", "000000", "NewPass123!@#", "NewPass123!@#", test_db
    )
    assert not ok_fail
    assert "验证码不正确" in msg_fail

    # 4. 正确验证码重置成功
    ok_success, msg_success = verify_and_reset_password(
        "bob", otp_code, "NewPass123!@#", "NewPass123!@#", test_db
    )
    assert ok_success
    assert "成功重置" in msg_success

    # 5. 校验数据库新密码 Hash
    updated_user = test_db.execute("SELECT password_hash FROM users WHERE id = 'u2'").fetchone()
    assert check_password_hash(updated_user['password_hash'], "NewPass123!@#")

    # 6. 该 OTP 已被消耗，不可二次重放
    ok_reuse, msg_reuse = verify_and_reset_password(
        "bob", otp_code, "AnotherPass123!@#", "AnotherPass123!@#", test_db
    )
    assert not ok_reuse


def test_auth_forgot_password_routes(client):
    # GET forgot password page
    resp = client.get('/forgot-password')
    assert resp.status_code == 200
    assert "重置密码" in resp.get_data(as_text=True)

    # API send-otp missing identifier
    resp_empty = client.post('/api/auth/send-otp', json={'identifier': ''})
    assert resp_empty.status_code == 400
