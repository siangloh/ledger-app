"""Bound content-only deduplication: identical text is not a transaction ID."""
from datetime import datetime, timedelta


CONTENT_RETRY_WINDOW = timedelta(hours=2)


def find_recent_notification(db, user_id, content_hash, now=None):
    cutoff = ((now or datetime.now()) - CONTENT_RETRY_WINDOW).isoformat()
    return db.execute("""SELECT id, transaction_id, created_at FROM processed_notifications
        WHERE user_id = ? AND content_hash = ?
          AND julianday(created_at) >= julianday(?)
        ORDER BY id DESC LIMIT 1""", (str(user_id), content_hash, cutoff)).fetchone()
