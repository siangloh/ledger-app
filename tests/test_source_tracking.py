import json
import sqlite3
from services.source_tracking_service import (
    normalize_source,
    format_source_display,
    record_import_batch,
    get_import_batch_info,
    get_transaction_source_details,
    get_supported_sources_for_filter,
    get_supported_channels_for_filter,
)


def test_normalize_source():
    # Legacy backward compatibility
    assert normalize_source("auto_track") == "notification"
    assert normalize_source("auto_track_confirmed") == "notification"
    assert normalize_source("notification") == "notification"

    # Known sources
    assert normalize_source("manual") == "manual"
    assert normalize_source("import") == "import"
    assert normalize_source("api") == "api"
    assert normalize_source("nlp") == "nlp"
    assert normalize_source("recurring") == "recurring"
    assert normalize_source("split_bill") == "split_bill"
    assert normalize_source("reconciliation") == "reconciliation"

    # Unknown / fallback: must NOT guess manual
    assert normalize_source("") == "unknown"
    assert normalize_source(None) == "unknown"
    assert normalize_source("unsupported_source_xyz") == "unknown"


def test_format_source_display(flask_app):
    with flask_app.app.app_context():
        # Manual
        res_m = format_source_display("manual", lang="zh")
        assert res_m["badge_emoji"] == "✍️"
        assert res_m["badge_text"] == "手动"
        assert res_m["source_label"] == "手动"

        # Notification with Maybank channel
        res_notif = format_source_display("notification", channel="maybank", lang="zh")
        assert res_notif["badge_emoji"] == "📱"
        assert res_notif["badge_text"] == "Maybank"
        assert res_notif["channel_label"] == "Maybank MAE"

        # Notification without specific channel
        res_notif_gen = format_source_display("notification", channel=None, lang="en")
        assert res_notif_gen["badge_text"] == "Notification"
        assert res_notif_gen["source_label"] == "Notification"

        # Unknown / legacy
        res_unk = format_source_display("unknown", lang="ms")
        assert res_unk["badge_emoji"] == "❓"
        assert res_unk["badge_text"] == "Tidak Diketahui / Sejarah"


def test_record_import_batch_and_isolation(flask_app, admin_user_id):
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)
        db.row_factory = sqlite3.Row

        # User 1 batch
        batch_id_1 = record_import_batch(
            user_id=admin_user_id,
            filename="maybank_oct2026.csv",
            row_count=42,
            file_type="csv",
            db=db,
        )
        assert batch_id_1.startswith("imp_")
        db.commit()

        info_1 = get_import_batch_info(user_id=admin_user_id, batch_id=batch_id_1, db=db)
        assert info_1 is not None
        assert info_1["filename"] == "maybank_oct2026.csv"
        assert info_1["row_count"] == 42
        assert info_1["file_type"] == "csv"

        # User isolation: another user (user_id=99999) cannot access this batch
        info_other = get_import_batch_info(user_id=99999, batch_id=batch_id_1, db=db)
        assert info_other is None

        db.close()


def test_transaction_provenance_and_edit_protection(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)
        db.row_factory = sqlite3.Row

        # 1. Insert a manual transaction
        cur = db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, created_at)
            VALUES (?, 'expense', 25.50, '餐饮', '2026-10-18', 'Coffee bean', 'manual', '2026-10-18 10:00:00')
            """,
            (admin_user_id,),
        )
        tx_id = cur.lastrowid
        db.commit()
        db.close()

    # 2. Edit transaction via web endpoint
    resp = logged_in_client.post(
        f"/records/{tx_id}/edit",
        data={
            "type": "expense",
            "amount": "28.00",
            "category": "餐饮",
            "date": "2026-10-18",
            "note": "Coffee bean & tea leaf (edited)",
            "tags": "coffee",
        },
        follow_redirects=True,
    )
    assert resp.status_code == 200

    # 3. Verify original source is NOT modified, while updated_at and last_edit_source are recorded
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)
        db.row_factory = sqlite3.Row
        row = db.execute("SELECT * FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert row["source"] == "manual"
        assert row["amount"] == 28.00
        assert row["updated_at"] is not None
        assert row["last_edit_source"] == "web_edit"
        db.close()

    # 4. Query source details API
    api_resp = logged_in_client.get(f"/api/records/{tx_id}/source-details")
    assert api_resp.status_code == 200
    data = api_resp.get_json()
    assert data["ok"] is True
    details = data["details"]
    assert details["source"] == "manual"
    assert details["source_label"] == "手动"
    assert details["is_edited"] is True
    assert details["last_edit_source"] == "web_edit"


def test_notification_and_channel_filtering(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)

        # Clean existing test transactions for clean query check
        db.execute("DELETE FROM transactions WHERE user_id = ?", (admin_user_id,))

        # Insert diverse transactions
        db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, channel, source_ref, created_at)
            VALUES (?, 'expense', 15.00, '餐饮', '2026-10-18', 'Maybank Lunch', 'notification', 'maybank', 'mbk_hash1', '2026-10-18 12:00:00')
            """,
            (admin_user_id,),
        )
        db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, channel, source_ref, created_at)
            VALUES (?, 'expense', 8.50, '交通', '2026-10-18', 'TNG Toll', 'notification', 'tng', 'tng_hash2', '2026-10-18 13:00:00')
            """,
            (admin_user_id,),
        )
        db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, import_batch_id, created_at)
            VALUES (?, 'expense', 120.00, '购物', '2026-10-18', 'Imported Statement', 'import', 'batch_test123', '2026-10-18 14:00:00')
            """,
            (admin_user_id,),
        )
        db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, created_at)
            VALUES (?, 'expense', 50.00, '娱乐', '2026-10-18', 'Manual Cash', 'manual', '2026-10-18 15:00:00')
            """,
            (admin_user_id,),
        )
        db.commit()
        db.close()

    # Web records page with source=notification
    resp_notif = logged_in_client.get("/records?source=notification")
    assert resp_notif.status_code == 200
    html_notif = resp_notif.data.decode("utf-8")
    assert "Maybank Lunch" in html_notif
    assert "TNG Toll" in html_notif
    assert "Imported Statement" not in html_notif
    assert "Manual Cash" not in html_notif

    # Web records page with source=notification and channel=maybank
    resp_mbk = logged_in_client.get("/records?source=notification&channel=maybank")
    assert resp_mbk.status_code == 200
    html_mbk = resp_mbk.data.decode("utf-8")
    assert "Maybank Lunch" in html_mbk
    assert "TNG Toll" not in html_mbk

    # Web records page with source=import
    resp_imp = logged_in_client.get("/records?source=import")
    assert resp_imp.status_code == 200
    html_imp = resp_imp.data.decode("utf-8")
    assert "Imported Statement" in html_imp
    assert "Maybank Lunch" not in html_imp

    # Native transactions endpoint filtering
    resp_native = logged_in_client.get("/api/native/transactions?source=notification&channel=tng")
    assert resp_native.status_code == 200
    native_data = resp_native.get_json()
    assert native_data["ok"] is True
    assert len(native_data["items"]) == 1
    item = native_data["items"][0]
    assert item["note"] == "TNG Toll"
    assert item["source"] == "notification"
    assert item["channel"] == "tng"
    assert item["channel_label"] == "Touch 'n Go eWallet"
    assert item["badge_text"] == "TNG"


def test_merge_pending_retains_evidence(flask_app, logged_in_client, admin_user_id):
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)
        db.row_factory = sqlite3.Row

        # Create target transaction
        cur = db.execute(
            """
            INSERT INTO transactions (user_id, type, amount, category, date, note, source, channel, created_at)
            VALUES (?, 'expense', 45.00, '餐饮', '2026-10-18', 'Dinner with friends', 'manual', NULL, '2026-10-18 19:00:00')
            """,
            (admin_user_id,),
        )
        target_tx_id = cur.lastrowid

        # Create pending transaction in on_hold status
        cur_p = db.execute(
            """
            INSERT INTO pending_transactions (user_id, date, amount, category, note, channel, raw_text, status, created_at)
            VALUES (?, '2026-10-18', 45.00, '餐饮', 'GrabFood Payment', 'grab', 'GrabPay RM45', 'on_hold', '2026-10-18 19:05:00')
            """,
            (admin_user_id,),
        )
        pending_id = cur_p.lastrowid
        db.commit()
        db.close()

    # Call merge API
    resp = logged_in_client.post(
        f"/api/pending-transactions/{pending_id}/merge",
        data=json.dumps({"target_tx_id": target_tx_id}),
        content_type="application/json",
    )
    assert resp.status_code == 200
    assert resp.get_json()["ok"] is True

    # Verify target transaction merged_info has evidence
    with flask_app.app.app_context():
        db = sqlite3.connect(flask_app.DB_PATH)
        db.row_factory = sqlite3.Row
        row = db.execute("SELECT * FROM transactions WHERE id = ?", (target_tx_id,)).fetchone()
        assert row["source"] == "manual"  # Primary source unchanged
        assert row["last_edit_source"] == "merge_pending"
        assert row["merged_info"] is not None

        merged_data = json.loads(row["merged_info"])
        assert len(merged_data) >= 1
        evidence = merged_data[0]
        assert evidence["channel"] == "grab"
        assert evidence["source"] == "notification"
        assert evidence["source_ref"] == "GrabPay RM45"

        # Check source details API includes merged evidences
        details = get_transaction_source_details(user_id=admin_user_id, tx_id=target_tx_id, db=db)
        assert details["has_merged_evidence"] is True
        assert len(details["merged_evidence"]) >= 1
        assert details["merged_evidence"][0]["channel_name"] == "GrabPay"

        db.close()


def test_supported_sources_and_channels_lists(flask_app):
    with flask_app.app.app_context():
        sources = get_supported_sources_for_filter(lang="zh")
        source_keys = [s["id"] for s in sources]
        assert "manual" in source_keys
        assert "notification" in source_keys
        assert "import" in source_keys
        assert "api" in source_keys
        assert "nlp" in source_keys
        assert "recurring" in source_keys
        assert "split_bill" in source_keys
        assert "reconciliation" in source_keys
        assert "unknown" in source_keys

        channels = get_supported_channels_for_filter()
        channel_keys = [c["id"] for c in channels]
        assert "maybank" in channel_keys
        assert "tng" in channel_keys
        assert "grab" in channel_keys
        assert "public_bank" in channel_keys
        assert "shopee" in channel_keys
        assert "bank_card" in channel_keys
        assert "generic" in channel_keys
