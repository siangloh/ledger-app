import pytest

from core.db import get_db


HEADERS = {'X-API-KEY': 'test-auto-track-key'}


def test_undo_recoverable_idempotent_and_owned(flask_app, client, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        ids = []
        for owner in (admin_user_id, 'unrelated-user'):
            ids.append(db.execute("""INSERT INTO transactions
                (user_id, date, type, category, amount, note, source, created_at)
                VALUES (?, '2026-10-20', 'expense', '餐饮', 12.5, 'undo test', 'auto_track', datetime('now'))""",
                                  (owner,)).lastrowid)
        db.commit()
    before = client.get('/api/native/overview?month=2026-10', headers=HEADERS).json['expense_amount']
    endpoint = '/api/native/undo-transaction'
    assert client.post(endpoint, json={'transaction_id': ids[0]}).status_code == 401
    assert client.post(endpoint, json={'transaction_id': ids[1]}, headers=HEADERS).status_code == 404
    first = client.post(endpoint, json={'transaction_id': ids[0]}, headers=HEADERS)
    assert first.status_code == 200
    assert first.json['ok']
    again = client.post(endpoint, json={'transaction_id': ids[0]}, headers=HEADERS)
    assert again.status_code == 200
    assert again.json['already_undone']
    after = client.get('/api/native/overview?month=2026-10', headers=HEADERS).json['expense_amount']
    assert before - after == pytest.approx(12.5)
    with flask_app.app.app_context():
        db = get_db()
        assert db.execute('SELECT deleted_at FROM transactions WHERE id = ?', (ids[0],)).fetchone()['deleted_at']
        assert db.execute('SELECT deleted_at FROM transactions WHERE id = ?', (ids[1],)).fetchone()['deleted_at'] is None
        snapshot = db.execute('SELECT snapshot_data FROM operation_snapshots WHERE id = ?',
                              (first.json['snapshot_id'],)).fetchone()
        assert str(ids[0]) in snapshot['snapshot_data']


@pytest.mark.parametrize('payload', [[], {'transaction_id': True}, {'transaction_id': 0},
                                      {'transaction_id': '1'}, {'transaction_id': 1.5}, {}])
def test_undo_invalid_payload(client, payload):
    assert client.post('/api/native/undo-transaction', json=payload, headers=HEADERS).status_code == 400


def test_undo_cookie_requires_csrf(flask_app, logged_in_client):
    flask_app.app.config['WTF_CSRF_ENABLED'] = True
    try:
        assert logged_in_client.post('/api/native/undo-transaction', json={'transaction_id': 1}).status_code == 403
    finally:
        flask_app.app.config['WTF_CSRF_ENABLED'] = False

