import sqlite3
from core.db import bump_data_version, get_data_version, get_latest_event, init_db


def test_system_metadata_version_persistence(tmp_path, monkeypatch):
    """测试 bump_data_version 持久化到 system_metadata 表，确保多 Worker 强一致"""
    db_file = str(tmp_path / "test_meta.db")
    monkeypatch.setattr("core.db.DB_PATH", db_file)
    monkeypatch.setattr("core.config.DB_PATH", db_file)

    init_db()

    conn1 = sqlite3.connect(db_file)
    conn1.row_factory = sqlite3.Row

    test_uid = "user_worker_test_123"
    event_payload = {"id": 999, "amount": 88.5, "note": "多Worker测试"}

    # Worker 1 触发更新
    bump_data_version("test_event", event_payload, user_id=test_uid, db=conn1)

    # 模拟 Worker 2 打开独立新连接读取
    conn2 = sqlite3.connect(db_file)
    conn2.row_factory = sqlite3.Row

    # 1. 验证 Worker 2 能读取到 Worker 1 写入的用户级版本号
    v_user = get_data_version(user_id=test_uid, db=conn2)
    assert isinstance(v_user, int)
    assert v_user > 0

    # 2. 验证全局版本号亦已同步持久化
    v_global = get_data_version(user_id=None, db=conn2)
    assert isinstance(v_global, int)
    assert v_global == v_user

    # 3. 验证 Worker 2 能还原最新事件详情
    evt = get_latest_event(user_id=test_uid, db=conn2)
    assert evt is not None
    assert evt["version"] == v_user
    assert evt["type"] == "test_event"
    assert evt["data"]["amount"] == 88.5

    # 4. 直接验证底层 system_metadata 表物理记录
    row = conn2.execute("SELECT val FROM system_metadata WHERE key = ?", (f"user_data_version:{test_uid}",)).fetchone()
    assert row is not None
    assert int(row["val"]) == v_user

    conn1.close()
    conn2.close()


def test_realtime_check_api(logged_in_client, flask_app, admin_user_id):
    """测试 /api/realtime/check 接口在数据版本同步与更新时的响应"""
    with flask_app.app.app_context():
        db = flask_app.get_db()
        v1 = bump_data_version("auto_track", {"id": 100, "amount": 12.5}, user_id=admin_user_id, db=db)
        db.commit()

    # 1. 客户端带上当前版本 v1 查询，应返回 has_update=False
    res = logged_in_client.get(f'/api/realtime/check?v={v1}')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['version'] == v1
    assert data['has_update'] is False
    assert data['event'] is None

    # 2. 客户端带上旧版本查询，应返回 has_update=True 且携带 event
    res = logged_in_client.get(f'/api/realtime/check?v={v1 - 1}')
    assert res.status_code == 200
    data = res.get_json()
    assert data['ok'] is True
    assert data['has_update'] is True
    assert data['event'] is not None
    assert data['event']['type'] == 'auto_track'
    assert data['event']['data']['amount'] == 12.5

    # 3. 验证页面渲染注入的 INITIAL_DATA_VERSION 也是当前用户版本 v1
    res = logged_in_client.get('/')
    assert res.status_code == 200
    html = res.get_data(as_text=True)
    assert f'window.INITIAL_DATA_VERSION = {v1};' in html

