import sqlite3


def test_transactions_sync_idempotency(client, flask_app, admin_user_id):
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
    tx_payload = {
        "username": "admin",
        "transactions": [
            {
                "local_id": 1001,
                "date": "2026-10-18",
                "type": "expense",
                "category": "餐饮",
                "amount": 45.00,
                "note": "星巴克咖啡",
                "source": "offline_manual"
            }
        ]
    }

    # First sync: should insert transaction
    r1 = client.post("/api/transactions/sync", json=tx_payload, headers=headers)
    assert r1.status_code == 200
    d1 = r1.get_json()
    assert d1["ok"] is True
    assert 1001 in d1["synced_ids"]

    # Verify transaction count in DB is 1
    db = sqlite3.connect(flask_app.DB_PATH)
    count = db.execute("SELECT COUNT(*) FROM transactions WHERE note = '星巴克咖啡'").fetchone()[0]
    assert count == 1
    db.close()

    # Second sync with exact same item: should be idempotent and NOT duplicate
    r2 = client.post("/api/transactions/sync", json=tx_payload, headers=headers)
    assert r2.status_code == 200
    d2 = r2.get_json()
    assert d2["ok"] is True
    assert 1001 in d2["synced_ids"]

    # Verify count remains 1
    db = sqlite3.connect(flask_app.DB_PATH)
    count2 = db.execute("SELECT COUNT(*) FROM transactions WHERE note = '星巴克咖啡'").fetchone()[0]
    assert count2 == 1
    db.close()


def test_transactions_sync_returns_failed_items(client, flask_app, admin_user_id):
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
    tx_payload = {
        "username": "admin",
        "transactions": [
            {
                "local_id": 9999,
                "date": "invalid-date",
                "type": "unknown_type",
                "amount": -50.0,
                "note": "坏数据",
                "source": "offline_manual"
            }
        ]
    }

    resp = client.post("/api/transactions/sync", json=tx_payload, headers=headers)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["ok"] is True
    # The bad item should either be safely fallback-handled or recorded in failed_items
    assert "synced_ids" in data
    assert "failed_items" in data
