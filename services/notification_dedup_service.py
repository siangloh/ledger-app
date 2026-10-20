"""Bound content-only deduplication: identical text is not a transaction ID."""
from datetime import datetime, timedelta


CONTENT_RETRY_WINDOW = timedelta(seconds=30)


def find_recent_notification(db, user_id, content_hash, now=None):
    cutoff = ((now or datetime.now()) - CONTENT_RETRY_WINDOW).isoformat()
    return db.execute("""SELECT id, transaction_id, created_at FROM processed_notifications
        WHERE user_id = ? AND content_hash = ?
          AND julianday(created_at) >= julianday(?)
        ORDER BY id DESC LIMIT 1""", (str(user_id), content_hash, cutoff)).fetchone()


def init_notification_receipts(db):
    db.execute("""CREATE TABLE IF NOT EXISTS notification_receipts (
        user_id TEXT NOT NULL, event_id TEXT NOT NULL, content_hash TEXT NOT NULL,
        response_json TEXT NOT NULL, PRIMARY KEY (user_id, event_id))""")


def find_notification_receipt(db, user_id, event_id):
    return db.execute('SELECT content_hash, response_json FROM notification_receipts WHERE user_id = ? AND event_id = ?',
                      (str(user_id), event_id)).fetchone()


def save_notification_receipt(db, user_id, event_id, content_hash, response_json):
    db.execute('INSERT OR IGNORE INTO notification_receipts VALUES (?, ?, ?, ?)',
               (str(user_id), event_id, content_hash, response_json))
    db.commit()
