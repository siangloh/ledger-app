# -*- coding: utf-8 -*-
import pytest
from core.config import is_valid_api_key, DEFAULT_AUTO_TRACK_KEY
from services.merchant_service import get_merchant_suggestions


@pytest.fixture()
def db(flask_app):
    with flask_app.app.app_context():
        yield flask_app.get_db()


def test_default_key_rejected_when_custom_key_configured(db, monkeypatch):
    monkeypatch.setattr('core.config.AUTO_TRACK_KEY', '')
    # 配置自定义 auto_track_key
    db.execute("INSERT OR REPLACE INTO system_settings (key, value) VALUES ('auto_track_key', 'my_secret_key_123')")
    db.commit()

    # 验证自定义 Key 有效
    assert is_valid_api_key('my_secret_key_123', db=db) is True

    # 验证默认内置 Key 被彻底拒绝（S1 后门消除）
    if DEFAULT_AUTO_TRACK_KEY:
        assert is_valid_api_key(DEFAULT_AUTO_TRACK_KEY, db=db) is False


def test_user_agent_bypass_blocked(client):
    # 模拟未提供 API Key 但伪造 User-Agent 为 Android 的请求（S3 漏洞防御验证）
    resp = client.post(
        '/api/auto-track',
        data={'text': "Touch 'n Go eWallet: You have successfully paid RM 15.00 to Test Shop"},
        headers={'User-Agent': 'Dalvik/2.1.0 (Linux; U; Android 12; Pixel 6 Build/SD2A.220105.001.A1)'}
    )
    assert resp.status_code == 401
    json_data = resp.get_json()
    assert json_data['ok'] is False
    assert 'API Key 无效' in json_data['message']


def test_otp_not_leaked_in_response(client, db, monkeypatch):
    # 确保测试用户存在
    db.execute(
        "INSERT OR IGNORE INTO users (id, username, password_hash, created_at, email) VALUES (?, ?, ?, ?, ?)",
        ('u_test_otp', 'audit_user', 'some_hash', '2026-01-01T00:00:00', 'audit_victim@test.com')
    )
    db.commit()

    monkeypatch.delenv('ALLOW_SIMULATED_EMAIL', raising=False)
    monkeypatch.delenv('PYTEST_CURRENT_TEST', raising=False)

    resp = client.post('/api/auth/send-otp', json={'identifier': 'audit_user'})
    # 线上未配 SMTP 应安全失败关闭
    assert resp.status_code == 400
    json_data = resp.get_json()
    assert json_data['ok'] is False
    # 绝不能返回 simulated_otp
    assert 'simulated_otp' not in json_data.get('data', {})


def test_merchant_overrides_user_isolation(db):
    user_a = 'user_audit_a'
    user_b = 'user_audit_b'

    # 用户 A 记录私密商户分类
    db.execute(
        "INSERT OR REPLACE INTO merchant_category_overrides (user_id, merchant_note, category, updated_at) "
        "VALUES (?, 'Secret Private Clinic', '医疗健康', '2026-10-17T00:00:00')",
        (user_a,)
    )
    db.commit()

    # 用户 A 检索能查到
    res_a = get_merchant_suggestions(user_id=user_a, query='Clinic', db=db)
    assert any(m['name'] == 'Secret Private Clinic' for m in res_a)

    # 用户 B 检索完全看不到用户 A 的私密商户（S5 隔离验证）
    res_b = get_merchant_suggestions(user_id=user_b, query='Clinic', db=db)
    assert not any(m['name'] == 'Secret Private Clinic' for m in res_b)


def test_pending_transaction_idempotent_confirmation(client, db):
    user_id = 'user_pending_audit'
    db.execute(
        "INSERT OR IGNORE INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
        (user_id, 'pending_user', 'hash', '2026-01-01T00:00:00')
    )
    # 插入一条 on_hold 记录
    cur = db.execute('''
        INSERT INTO pending_transactions (
            user_id, date, type, amount, group_name, category, merchant, note, channel, raw_text, status, on_hold_reason, created_at
        ) VALUES (
            ?, '2026-10-17', 'expense', 45.00, 'personal', '餐饮', 'Sushi King', 'Sushi Lunch', 'Maybank', 'text', 'on_hold', 'test', '2026-10-17T00:00:00'
        )
    ''', (user_id,))
    pending_id = cur.lastrowid
    db.commit()

    with client.session_transaction() as sess:
        sess['user_id'] = user_id
        sess['username'] = 'pending_user'
        sess['logged_in'] = True

    # 第一次确认
    resp1 = client.post(f'/api/pending-transactions/{pending_id}/confirm', json={'category': '餐饮'})
    assert resp1.status_code == 200
    assert resp1.get_json()['ok'] is True

    # 第二次重复确认（D2 防重验证）
    resp2 = client.post(f'/api/pending-transactions/{pending_id}/confirm', json={'category': '餐饮'})
    assert resp2.status_code == 200
    assert resp2.get_json()['ok'] is True
    assert resp2.get_json().get('already_confirmed') is True

    # 验证正式账本 transactions 表中只有唯独 1 笔记录，没有重复产生
    tx_count = db.execute(
        "SELECT COUNT(*) as cnt FROM transactions WHERE user_id = ? AND note = 'Sushi Lunch'",
        (user_id,)
    ).fetchone()['cnt']
    assert tx_count == 1
