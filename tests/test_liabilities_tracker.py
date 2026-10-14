import sqlite3

import pytest

from liabilities_tracker import sync_installments_to_monthly_statement


@pytest.fixture
def db_conn():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE installments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            total_amount REAL NOT NULL,
            tenure_months INTEGER NOT NULL,
            paid_periods INTEGER DEFAULT 0,
            monthly_amount REAL NOT NULL,
            first_due_date TEXT NOT NULL,
            status TEXT DEFAULT 'active',
            last_synced_month TEXT,
            note TEXT,
            created_at TEXT NOT NULL
        );

        CREATE TABLE transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT,
            date TEXT NOT NULL,
            type TEXT NOT NULL,
            group_name TEXT,
            category TEXT,
            amount REAL NOT NULL,
            note TEXT,
            source TEXT DEFAULT 'manual',
            created_at TEXT NOT NULL
        );
        """
    )
    conn.commit()
    yield conn
    conn.close()


def _insert_installment(conn, **overrides):
    row = {
        "user_id": "u1",
        "title": "iPhone 分期",
        "total_amount": 3600.0,
        "tenure_months": 12,
        "paid_periods": 0,
        "monthly_amount": 300.0,
        "first_due_date": "2026-01-15",
        "status": "active",
        "last_synced_month": None,
        "note": None,
        "created_at": "2026-01-01T00:00:00",
    }
    row.update(overrides)
    cur = conn.execute(
        """
        INSERT INTO installments (
            user_id, title, total_amount, tenure_months, paid_periods, monthly_amount,
            first_due_date, status, last_synced_month, note, created_at
        ) VALUES (:user_id, :title, :total_amount, :tenure_months, :paid_periods, :monthly_amount,
                  :first_due_date, :status, :last_synced_month, :note, :created_at)
        """,
        row,
    )
    conn.commit()
    return cur.lastrowid


def test_sync_inserts_transaction_and_advances_paid_periods(db_conn):
    _insert_installment(db_conn)

    result = sync_installments_to_monthly_statement(db_conn, "2026-02-15")

    assert result["syncedRecords"] == 1
    assert result["completedInstallments"] == 0

    row = db_conn.execute("SELECT * FROM installments").fetchone()
    assert row["paid_periods"] == 1
    assert row["status"] == "active"
    assert row["last_synced_month"] == "2026-02"

    txs = db_conn.execute("SELECT * FROM transactions").fetchall()
    assert len(txs) == 1
    assert txs[0]["amount"] == 300.0
    assert txs[0]["source"] == "installment_auto"


def test_sync_marks_completed_on_final_period(db_conn):
    _insert_installment(db_conn, tenure_months=3, paid_periods=2)

    result = sync_installments_to_monthly_statement(db_conn, "2026-03-15")

    assert result["completedInstallments"] == 1
    row = db_conn.execute("SELECT * FROM installments").fetchone()
    assert row["status"] == "completed"
    assert row["paid_periods"] == 3


def test_sync_skips_installment_not_yet_due(db_conn):
    _insert_installment(db_conn, first_due_date="2026-01-20")

    result = sync_installments_to_monthly_statement(db_conn, "2026-02-10")

    assert result["syncedRecords"] == 0
    row = db_conn.execute("SELECT * FROM installments").fetchone()
    assert row["paid_periods"] == 0
    assert db_conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 0


def test_sync_is_idempotent_within_the_same_month(db_conn):
    """Regression test: a retried/duplicate cron invocation for the same month must
    not double-post the installment payment. This is the concurrency bug fixed by
    the conditional-UPDATE 'claim' pattern in sync_installments_to_monthly_statement."""
    _insert_installment(db_conn)

    first = sync_installments_to_monthly_statement(db_conn, "2026-02-15")
    second = sync_installments_to_monthly_statement(db_conn, "2026-02-20")

    assert first["syncedRecords"] == 1
    assert second["syncedRecords"] == 0

    row = db_conn.execute("SELECT * FROM installments").fetchone()
    assert row["paid_periods"] == 1

    txs = db_conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    assert txs == 1


def test_sync_processes_next_month_after_already_synced(db_conn):
    _insert_installment(db_conn, paid_periods=1, last_synced_month="2026-02")

    result = sync_installments_to_monthly_statement(db_conn, "2026-03-15")

    assert result["syncedRecords"] == 1
    row = db_conn.execute("SELECT * FROM installments").fetchone()
    assert row["paid_periods"] == 2
    assert row["last_synced_month"] == "2026-03"


def test_accounts_page_renders_partial_on_htmx(logged_in_client):
    """Ensure /accounts uses partial layout under HTMX without duplicating app header/navbar."""
    full_resp = logged_in_client.get("/accounts")
    assert full_resp.status_code == 200
    assert b'<header class="app-header">' in full_resp.data

    htmx_resp = logged_in_client.get("/accounts", headers={"HX-Request": "true"})
    assert htmx_resp.status_code == 200
    assert b'<header class="app-header">' not in htmx_resp.data
    assert b"desktop-nav-links" not in htmx_resp.data
    assert "账户管理" in htmx_resp.data.decode("utf-8")


def test_liabilities_page_htmx_renders_modals_and_script_bindings(logged_in_client):
    """Ensure /liabilities renders properly with HTMX, modal triggers, and global script bindings."""
    htmx_resp = logged_in_client.get("/liabilities", headers={"HX-Request": "true"})
    assert htmx_resp.status_code == 200
    html = htmx_resp.data.decode("utf-8")

    # Partial layout: no duplicate global header
    assert '<header class="app-header">' not in html
    assert "desktop-nav-links" not in html

    # Modals must exist
    assert 'id="modalInstallment"' in html
    assert 'id="modalLoan"' in html
    assert 'id="modalAccount"' in html

    # Modals must have elevated z-index above bottom nav
    assert "z-index: 1050" in html

    # Buttons must have valid onclick handlers
    assert "openModal('modalInstallment')" in html
    assert "openModal('modalLoan')" in html
    assert "openModal('modalAccount')" in html

    # Scripts must explicitly bind to window
    assert "window.openModal = function" in html
    assert "window.closeModal = function" in html
    assert "window.calcInstMonthly = function" in html


def test_app_js_provides_global_modal_controller():
    """Verify that app.js declares global modal handlers and executes container scripts globally."""
    with open("static/app.js", "r", encoding="utf-8") as f:
        js = f.read()

    assert "window.openModal = function" in js
    assert "window.closeModal = function" in js
    assert "function executeContainerScripts(container)" in js
    assert "new Function(s.textContent)" not in js
    assert "window.initSplitBill = function" in js or "window.initSplitBill()" in js
    assert "window.initSettingsPage = function" in js or "window.initSettingsPage()" in js


def test_accounts_page_does_not_clobber_global_on_type_change(logged_in_client):
    """Ensure accounts page uses onAccountTypeChange and never overrides global window.onTypeChange."""
    resp = logged_in_client.get("/accounts", headers={"HX-Request": "true"})
    assert resp.status_code == 200
    html = resp.data.decode("utf-8")

    assert "onAccountTypeChange('add')" in html
    assert "onAccountTypeChange('edit')" in html
    assert "window.onAccountTypeChange = function" in html
    assert "window.onTypeChange = function" not in html


def test_split_bill_page_lifecycle_and_window_bindings(logged_in_client):
    """Ensure split bill page initializes correctly on SPA navigation and exposes all interactive functions."""
    resp = logged_in_client.get("/split-bill", headers={"HX-Request": "true"})
    assert resp.status_code == 200
    html = resp.data.decode("utf-8")

    # Lifecycle initialization must support both DOMContentLoaded and immediate execution
    assert "function initSplitBill()" in html
    assert "window.initSplitBill = initSplitBill" in html
    assert "if (document.readyState === 'loading')" in html

    # Public interactive functions must be attached to window
    assert "window.openReviewModal = openReviewModal" in html
    assert "window.closeReviewModal = closeReviewModal" in html
    assert "window.startReceiptScan = startReceiptScan" in html
    assert "window.addPerson = addPerson" in html
    assert "window.addNewItemRow = addNewItemRow" in html
    assert "window.copyClaimMessage = copyClaimMessage" in html


def test_settings_page_lifecycle_and_window_bindings(logged_in_client):
    """Ensure settings page initializes correctly on SPA navigation and binds tabs & helpers to window."""
    resp = logged_in_client.get("/settings", headers={"HX-Request": "true"})
    assert resp.status_code == 200
    html = resp.data.decode("utf-8")

    # Lifecycle initialization must support both DOMContentLoaded and immediate execution
    assert "function initSettingsPage()" in html
    assert "window.initSettingsPage = initSettingsPage" in html

    # Public interactive functions must be attached to window
    assert "window.switchSettingsTab = switchSettingsTab" in html
    assert "window.detectUserTimezone = detectUserTimezone" in html
    assert "window.setCurrencySymbol = setCurrencySymbol" in html
    assert "window.resetToDefaults = resetToDefaults" in html



