import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime

AJAX_HEADERS = {"X-Requested-With": "XMLHttpRequest"}


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    try:
        yield conn
    finally:
        conn.close()


def _insert_tx(flask_app, user_id, amount=50.0, category="餐饮", note="午餐", tx_type="expense", deleted_at=None):
    with _test_db(flask_app) as db:
        cur = db.cursor()
        now_iso = datetime.now().isoformat()
        cur.execute(
            """
            INSERT INTO transactions
            (user_id, date, type, category, amount, note, source, created_at, deleted_at)
            VALUES (?, '2026-10-18', ?, ?, ?, ?, 'manual', ?, ?)
            """,
            (user_id, tx_type, category, amount, note, now_iso, deleted_at)
        )
        tx_id = cur.lastrowid
        db.commit()
        return tx_id


def test_soft_delete_and_recycle_bin_page(flask_app, logged_in_client, admin_user_id):
    tx_id = _insert_tx(flask_app, admin_user_id, amount=88.0, note="测试软删除交易")

    # 1. 软删除该交易 (AJAX)
    resp = logged_in_client.post(f"/records/{tx_id}/delete", headers=AJAX_HEADERS)
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["ok"] is True
    assert "snapshot_id" in data
    assert "undo_url" in data

    # 检查数据库中 deleted_at 已被标记
    with _test_db(flask_app) as db:
        row = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert row[0] is not None

    # 2. 访问回收站页面
    page_resp = logged_in_client.get("/recycle-bin")
    assert page_resp.status_code == 200
    assert "回收站" in page_resp.get_data(as_text=True)
    assert "测试软删除交易" in page_resp.get_data(as_text=True)

    # 3. 访问回收站 JSON API
    api_resp = logged_in_client.get("/api/recycle-bin")
    assert api_resp.status_code == 200
    api_data = api_resp.get_json()
    assert api_data["ok"] is True
    assert any(it["id"] == tx_id for it in api_data["items"])


def test_recycle_bin_spending_isolation(flask_app, logged_in_client, admin_user_id):
    # 正常交易 vs 已删除交易
    _insert_tx(flask_app, admin_user_id, amount=100.0, category="购物", note="活跃账单", deleted_at=None)
    _insert_tx(flask_app, admin_user_id, amount=500.0, category="数码", note="被删除的高额账单", deleted_at="2026-10-18T10:00:00")

    # 验证常规账目页面不会渲染已删除项
    resp = logged_in_client.get("/records")
    assert resp.status_code == 200
    html = resp.get_data(as_text=True)
    assert "活跃账单" in html
    assert "被删除的高额账单" not in html


def test_restore_single_and_batch(flask_app, logged_in_client, admin_user_id):
    id1 = _insert_tx(flask_app, admin_user_id, amount=20.0, note="待恢复1", deleted_at="2026-10-18T10:00:00")
    id2 = _insert_tx(flask_app, admin_user_id, amount=30.0, note="待恢复2", deleted_at="2026-10-18T10:00:00")

    # 单条恢复 (AJAX)
    r1 = logged_in_client.post(f"/recycle-bin/{id1}/restore", headers=AJAX_HEADERS)
    assert r1.status_code == 200
    assert r1.get_json()["ok"] is True

    with _test_db(flask_app) as db:
        row1 = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (id1,)).fetchone()
        assert row1[0] is None

    # 批量恢复 (JSON)
    r2 = logged_in_client.post(
        "/recycle-bin/batch-restore",
        data=json.dumps({"ids": [id2]}),
        content_type="application/json"
    )
    assert r2.status_code == 200
    assert r2.get_json()["ok"] is True

    with _test_db(flask_app) as db:
        row2 = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (id2,)).fetchone()
        assert row2[0] is None


def test_permanent_delete_and_empty_bin(flask_app, logged_in_client, admin_user_id):
    id1 = _insert_tx(flask_app, admin_user_id, amount=15.0, note="彻底销毁1", deleted_at="2026-10-18T10:00:00")
    id2 = _insert_tx(flask_app, admin_user_id, amount=25.0, note="彻底销毁2", deleted_at="2026-10-18T10:00:00")
    active_id = _insert_tx(flask_app, admin_user_id, amount=99.0, note="正常保留", deleted_at=None)

    # 单条彻底删除
    r1 = logged_in_client.post(f"/recycle-bin/{id1}/permanent-delete", headers=AJAX_HEADERS)
    assert r1.status_code == 200
    assert r1.get_json()["ok"] is True

    with _test_db(flask_app) as db:
        assert db.execute("SELECT id FROM transactions WHERE id = ?", (id1,)).fetchone() is None

    # 清空回收站
    r2 = logged_in_client.post("/recycle-bin/empty", headers=AJAX_HEADERS)
    assert r2.status_code == 200
    assert r2.get_json()["ok"] is True

    # id2 被彻底清除，但 active_id 必须安然无恙
    with _test_db(flask_app) as db:
        assert db.execute("SELECT id FROM transactions WHERE id = ?", (id2,)).fetchone() is None
        assert db.execute("SELECT id FROM transactions WHERE id = ?", (active_id,)).fetchone() is not None


def test_operation_undo_single_delete(flask_app, logged_in_client, admin_user_id):
    tx_id = _insert_tx(flask_app, admin_user_id, amount=66.0, note="准备撤销单删")

    # 删除
    del_resp = logged_in_client.post(f"/records/{tx_id}/delete", headers=AJAX_HEADERS)
    del_data = del_resp.get_json()
    snapshot_id = del_data["snapshot_id"]

    # 撤销
    undo_resp = logged_in_client.post(f"/records/undo/{snapshot_id}", headers=AJAX_HEADERS)
    assert undo_resp.status_code == 200
    undo_data = undo_resp.get_json()
    assert undo_data["ok"] is True

    # 验证已恢复
    with _test_db(flask_app) as db:
        row = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (tx_id,)).fetchone()
        assert row[0] is None

    # 再次撤销应提示已撤销
    repeat_resp = logged_in_client.post(f"/records/undo/{snapshot_id}", headers=AJAX_HEADERS)
    assert repeat_resp.get_json()["ok"] is False


def test_operation_undo_batch_delete(flask_app, logged_in_client, admin_user_id):
    id1 = _insert_tx(flask_app, admin_user_id, amount=11.0, note="批量删除1")
    id2 = _insert_tx(flask_app, admin_user_id, amount=22.0, note="批量删除2")

    del_resp = logged_in_client.post(
        "/records/batch-delete",
        data=json.dumps({"ids": [id1, id2]}),
        content_type="application/json"
    )
    del_data = del_resp.get_json()
    assert del_data["ok"] is True
    snapshot_id = del_data["snapshot_id"]

    # 撤销批量删除
    undo_resp = logged_in_client.post(f"/records/undo/{snapshot_id}", headers=AJAX_HEADERS)
    assert undo_resp.status_code == 200
    assert undo_resp.get_json()["ok"] is True

    with _test_db(flask_app) as db:
        r1 = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (id1,)).fetchone()
        r2 = db.execute("SELECT deleted_at FROM transactions WHERE id = ?", (id2,)).fetchone()
        assert r1[0] is None
        assert r2[0] is None


def test_operation_undo_batch_edit(flask_app, logged_in_client, admin_user_id):
    id1 = _insert_tx(flask_app, admin_user_id, amount=15.0, category="餐饮", note="编辑前1")
    id2 = _insert_tx(flask_app, admin_user_id, amount=25.0, category="餐饮", note="编辑前2")

    # 批量修改分类为 交通
    edit_resp = logged_in_client.post(
        "/records/batch-edit",
        data=json.dumps({"ids": [id1, id2], "category": "交通"}),
        content_type="application/json"
    )
    edit_data = edit_resp.get_json()
    assert edit_data["ok"] is True
    snapshot_id = edit_data["snapshot_id"]

    # 检查已修改
    with _test_db(flask_app) as db:
        cat1 = db.execute("SELECT category FROM transactions WHERE id = ?", (id1,)).fetchone()[0]
        assert cat1 == "交通"

    # 执行撤销
    undo_resp = logged_in_client.post(f"/records/undo/{snapshot_id}", headers=AJAX_HEADERS)
    assert undo_resp.status_code == 200
    assert undo_resp.get_json()["ok"] is True

    # 恢复为原分类 餐饮
    with _test_db(flask_app) as db:
        cat1_reverted = db.execute("SELECT category FROM transactions WHERE id = ?", (id1,)).fetchone()[0]
        assert cat1_reverted == "餐饮"


def test_native_api_recycle_bin(flask_app, client, admin_user_id):
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
    tx_id = _insert_tx(flask_app, admin_user_id, amount=77.0, note="原生接口测试账单")

    # 1. 原生删除 (DELETE /api/native/transactions/<id>)
    del_resp = client.delete(
        f"/api/native/transactions/{tx_id}?username=admin",
        headers=headers
    )
    assert del_resp.status_code == 200
    del_data = del_resp.get_json()
    assert del_data["ok"] is True
    snapshot_id = del_data.get("snapshot_id")

    # 2. 原生查看回收站
    list_resp = client.get("/api/native/recycle-bin?username=admin", headers=headers)
    assert list_resp.status_code == 200
    list_data = list_resp.get_json()
    assert list_data["ok"] is True
    assert any(it["id"] == tx_id for it in list_data["items"])

    # 3. 原生恢复
    restore_resp = client.post(f"/api/native/recycle-bin/{tx_id}/restore?username=admin", headers=headers)
    assert restore_resp.status_code == 200
    assert restore_resp.get_json()["ok"] is True

    # 4. 原生再次删除并通过快照撤销
    del2_resp = client.delete(f"/api/native/transactions/{tx_id}?username=admin", headers=headers)
    del2_snap = del2_resp.get_json().get("snapshot_id") or snapshot_id
    undo_resp = client.post(f"/api/native/records/undo/{del2_snap}?username=admin", headers=headers)
    assert undo_resp.status_code == 200
    assert undo_resp.get_json()["ok"] is True


def test_multi_tenant_isolation_recycle_bin(flask_app, logged_in_client, admin_user_id):
    # 创建另一个用户 (使用 INSERT OR IGNORE 确保幂等安全)
    with _test_db(flask_app) as db:
        now_iso = datetime.now().isoformat()
        db.execute(
            "INSERT OR IGNORE INTO users (id, username, password_hash, created_at) VALUES ('recycle_other_uid', 'recycle_other_user', 'hash', ?)",
            (now_iso,)
        )
        db.commit()
        row = db.execute("SELECT id FROM users WHERE username = 'recycle_other_user'").fetchone()
        other_user_id = row[0]

    other_tx = _insert_tx(flask_app, other_user_id, amount=999.0, note="他人隐私", deleted_at="2026-10-18T10:00:00")

    # admin 访问回收站不应该看到 other_tx
    resp = logged_in_client.get("/api/recycle-bin")
    items = resp.get_json().get("items", [])
    assert all(it["id"] != other_tx for it in items)

    # admin 尝试恢复他人记录应当失败
    restore_resp = logged_in_client.post(f"/recycle-bin/{other_tx}/restore", headers=AJAX_HEADERS)
    assert restore_resp.status_code in (403, 404) or restore_resp.get_json().get("ok") is False
