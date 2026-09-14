import os
import tempfile

import pytest

# app.py must find these before it is ever imported (module-level code reads them,
# and now fails fast if FLASK_SECRET_KEY is missing) and must NOT find TURSO_URL /
# TURSO_AUTH_TOKEN so get_db() falls back to a throwaway local sqlite file instead
# of trying to reach a real Turso cloud database during tests.
_TEST_DATA_DIR = tempfile.mkdtemp(prefix="ledger-app-tests-")
os.environ["FLASK_SECRET_KEY"] = "test-only-secret-key-not-for-production"
os.environ["DATA_DIR"] = _TEST_DATA_DIR
os.environ["AUTO_TRACK_KEY"] = "test-auto-track-key"
os.environ.pop("TURSO_URL", None)
os.environ.pop("TURSO_AUTH_TOKEN", None)
os.environ["WTF_CSRF_ENABLED"] = "0"


@pytest.fixture(scope="session")
def flask_app():
    import app as app_module

    app_module.app.config["TESTING"] = True
    app_module.app.config["WTF_CSRF_ENABLED"] = False
    return app_module


@pytest.fixture()
def client(flask_app):
    return flask_app.app.test_client()


@pytest.fixture()
def admin_user_id(flask_app):
    db = __import__("sqlite3").connect(flask_app.DB_PATH)
    db.row_factory = __import__("sqlite3").Row
    row = db.execute("SELECT id FROM users WHERE username = 'admin'").fetchone()
    db.close()
    assert row is not None, "init_db() should always create a default admin user"
    return row["id"]


@pytest.fixture()
def logged_in_client(client, admin_user_id):
    with client.session_transaction() as sess:
        sess["logged_in"] = True
        sess["user_id"] = admin_user_id
    return client
