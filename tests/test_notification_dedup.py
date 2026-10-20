import sqlite3
from datetime import datetime, timedelta

from services.notification_dedup_service import find_recent_notification


def test_content_retry_window_and_user_isolation():
    db = sqlite3.connect(':memory:')
    db.row_factory = sqlite3.Row
    db.execute("""CREATE TABLE processed_notifications
        (id INTEGER PRIMARY KEY, user_id TEXT, content_hash TEXT, transaction_id INTEGER, created_at TEXT)""")
    now = datetime(2026, 10, 20, 22, 19, 48)
    db.execute('INSERT INTO processed_notifications VALUES (1, ?, ?, 10, ?)',
               ('u', 'same', (now - timedelta(days=1)).isoformat()))
    assert find_recent_notification(db, 'u', 'same', now) is None
    db.execute('INSERT INTO processed_notifications VALUES (2, ?, ?, 11, ?)',
               ('u', 'same', (now - timedelta(seconds=29)).isoformat()))
    assert find_recent_notification(db, 'u', 'same', now)['transaction_id'] == 11
    assert find_recent_notification(db, 'other', 'same', now) is None
    assert find_recent_notification(db, 'u', 'different', now) is None
    assert find_recent_notification(db, 'u', 'same', now + timedelta(seconds=31)) is None
    db.close()


def test_identical_notification_on_next_day_is_new(client, flask_app, admin_user_id):
    from core.db import get_db
    text = "Touch 'n Go eWallet: You have paid RM 0.01 to Window Regression Shop."
    headers = {'X-API-KEY': 'test-auto-track-key'}
    first = client.post('/api/auto-track', json={'text': text}, headers=headers)
    assert first.status_code == 201
    tx_id = first.json['transaction_id']
    with flask_app.app.app_context():
        db = get_db()
        old = (datetime.now() - timedelta(days=1)).isoformat()
        db.execute('UPDATE processed_notifications SET created_at = ? WHERE user_id = ? AND transaction_id = ?',
                   (old, str(admin_user_id), tx_id))
        db.execute('UPDATE transactions SET created_at = ?, date = ? WHERE id = ?', (old, old[:10], tx_id))
        db.commit()
    second = client.post('/api/auto-track', json={'text': text}, headers=headers)
    assert second.status_code == 201
    assert second.json['transaction_id'] != tx_id
    retry = client.post('/api/auto-track', json={'text': text}, headers=headers)
    assert retry.json['verdict'] == 'duplicate_ignored'
    assert retry.json['transaction_id'] == second.json['transaction_id']


def test_event_replay_survives_window_and_new_event_is_new(client, flask_app, admin_user_id, monkeypatch):
    from core.db import get_db
    monkeypatch.setattr('blueprints.auto_track.classify_notification_with_llm', lambda text: (True, None))
    headers = {'X-API-KEY': 'test-auto-track-key'}
    payload = {'text': "Touch 'n Go eWallet: You have paid RM 0.09 to Event Identity Shop.",
               'notification_event_id': 'event-one'}
    first = client.post('/api/auto-track', json=payload, headers=headers)
    assert first.status_code == 201
    tx_id = first.json['transaction_id']
    with flask_app.app.app_context():
        db = get_db()
        old = (datetime.now() - timedelta(seconds=45)).isoformat()
        db.execute('UPDATE processed_notifications SET created_at = ? WHERE user_id = ? AND transaction_id = ?',
                   (old, str(admin_user_id), tx_id))
        db.execute('UPDATE transactions SET created_at = ? WHERE id = ?', (old, tx_id))
        db.commit()
    retry = client.post('/api/auto-track', json=payload, headers=headers)
    assert retry.json['verdict'] == 'duplicate_ignored'
    assert retry.json['transaction_id'] == tx_id
    conflict = client.post('/api/auto-track', json={**payload, 'text': payload['text'].replace('0.09', '0.08')}, headers=headers)
    assert conflict.status_code == 409
    second = client.post('/api/auto-track', json={**payload, 'notification_event_id': 'event-two'}, headers=headers)
    assert second.status_code == 201
    assert second.json['transaction_id'] != tx_id


def test_same_amount_different_merchants_are_not_discarded(client, monkeypatch):
    monkeypatch.setattr('blueprints.auto_track.classify_notification_with_llm', lambda text: (True, None))
    headers = {'X-API-KEY': 'test-auto-track-key'}
    for merchant in ('Unique North Shop', 'Unique South Cafe'):
        result = client.post('/api/auto-track', json={
            'text': f"Touch 'n Go eWallet: You have paid RM 0.07 to {merchant}."}, headers=headers)
        assert result.status_code == 201
