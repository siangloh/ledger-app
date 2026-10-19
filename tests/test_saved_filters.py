"""
tests/test_saved_filters.py
交易搜索与保存筛选预设 (Saved Filters) 核心服务层、Web 端与 Native 原生伴侣端 API 完整自动化测试套件
"""

import sqlite3
from contextlib import contextmanager

from services.saved_filters_service import (
    get_saved_filters,
    create_saved_filter,
    update_saved_filter,
    delete_saved_filter,
    resolve_date_range,
    build_filter_sql
)


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def test_saved_filters_service_defaults_and_crud(flask_app, admin_user_id):
    """测试筛选预设的默认自动初始化与 CRUD 操作"""
    with _test_db(flask_app) as db:
        # 清理旧数据
        db.execute("DELETE FROM saved_filters WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 1. 首次查询自动初始化默认预设
        filters = get_saved_filters(admin_user_id, db)
        assert len(filters) >= 4
        names = [f["name"] for f in filters]
        assert "本月大额" in names
        assert "待核对/未分类" in names
        assert "结余储蓄" in names
        assert "本月支出" in names

        # 2. 创建自定义预设
        res_create = create_saved_filter(
            user_id=admin_user_id,
            name="星巴克咖啡",
            criteria={"q": "Starbucks", "category": "餐饮", "min_amount": 10.0},
            is_pinned=True,
            db=db
        )
        assert res_create["ok"] is True
        new_id = res_create["filter"]["id"]
        assert res_create["filter"]["name"] == "星巴克咖啡"
        assert res_create["filter"]["is_pinned"] is True
        assert res_create["filter"]["criteria"]["q"] == "Starbucks"

        # 3. 更新预设
        res_update = update_saved_filter(
            user_id=admin_user_id,
            filter_id=new_id,
            name="精品咖啡与下午茶",
            criteria={"q": "Coffee", "category": "餐饮", "min_amount": 15.0},
            is_pinned=False,
            db=db
        )
        assert res_update["ok"] is True
        assert res_update["filter"]["name"] == "精品咖啡与下午茶"
        assert res_update["filter"]["is_pinned"] is False
        assert res_update["filter"]["criteria"]["q"] == "Coffee"

        # 4. 删除预设
        res_del = delete_saved_filter(user_id=admin_user_id, filter_id=new_id, db=db)
        assert res_del["ok"] is True

        # 确认删除后不再存在
        filters_after = get_saved_filters(admin_user_id, db)
        assert not any(f["id"] == new_id for f in filters_after)


def test_resolve_date_range():
    """测试相对时间范围的解析计算"""
    # 1. 空或自定义
    assert resolve_date_range("") == (None, None)
    assert resolve_date_range("custom") == (None, None)

    # 2. 本月
    start_tm, end_tm = resolve_date_range("this_month")
    assert start_tm is not None
    assert end_tm is not None
    assert start_tm.endswith("-01")

    # 3. 上月
    start_lm, end_lm = resolve_date_range("last_month")
    assert start_lm is not None
    assert end_lm is not None
    assert start_lm.endswith("-01")
    assert start_lm < start_tm

    # 4. 近 30 天
    start_30, end_30 = resolve_date_range("last_30_days")
    assert start_30 is not None
    assert end_30 is not None
    assert start_30 < end_30

    # 5. 今年
    start_ty, end_ty = resolve_date_range("this_year")
    assert start_ty is not None
    assert end_ty is not None
    assert start_ty.endswith("-01-01")


def test_build_filter_sql():
    """测试多维条件 SQL WHERE 语句构建"""
    # 1. 基础条件
    sql1, params1 = build_filter_sql(
        user_id="1",
        q="Grab",
        tx_type="expense",
        category="交通",
        account_id=2,
        start="2026-10-01",
        end="2026-10-15",
        min_amount=15.5,
        max_amount=100.0,
        source="auto_track"
    )
    assert "user_id = ?" in sql1
    assert "deleted_at IS NULL" in sql1
    assert "type = 'expense'" in sql1
    assert "category = ?" in sql1
    assert "account_id = ?" in sql1
    assert "date >= ?" in sql1
    assert "date <= ?" in sql1
    assert "amount >= ?" in sql1
    assert ("source = ?" in sql1 or "source = 'auto_track'" in sql1)
    assert "%Grab%" in params1

    # 1.5 验证渠道过滤
    sql_chan, params_chan = build_filter_sql(user_id="1", channel="maybank")
    assert "channel = ?" in sql_chan
    assert "maybank" in params_chan

    # 2. 储蓄分类条件
    sql2, params2 = build_filter_sql(
        user_id="1",
        tx_type="savings"
    )
    assert "type = 'savings'" in sql2

    # 3. 划转条件
    sql3, params3 = build_filter_sql(
        user_id="1",
        tx_type="transfer"
    )
    assert "type = 'transfer'" in sql3


def test_web_saved_filters_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Web 端筛选预设 REST API 与 Records 页面多维过滤"""
    # 1. GET /api/saved-filters 获取列表
    res_list = logged_in_client.get("/api/saved-filters")
    assert res_list.status_code == 200
    data_list = res_list.get_json()
    assert data_list["ok"] is True
    assert isinstance(data_list["filters"], list)
    assert len(data_list["filters"]) >= 4

    # 2. POST /api/saved-filters 创建新预设
    res_post = logged_in_client.post(
        "/api/saved-filters",
        json={
            "name": "高频餐饮支出",
            "criteria": {"type": "expense", "category": "餐饮", "min_amount": 20},
            "is_pinned": True
        }
    )
    assert res_post.status_code == 200
    created = res_post.get_json()
    assert created["ok"] is True
    filter_id = created["filter"]["id"]

    # 3. POST /api/saved-filters/<id>/toggle-pin 切换钉选
    res_pin = logged_in_client.post(f"/api/saved-filters/{filter_id}/toggle-pin")
    assert res_pin.status_code == 200
    assert res_pin.get_json()["ok"] is True
    assert res_pin.get_json()["is_pinned"] is False

    # 4. PUT /api/saved-filters/<id> 更新预设
    res_put = logged_in_client.put(
        f"/api/saved-filters/{filter_id}",
        json={
            "name": "修改后的高频餐饮",
            "is_pinned": True
        }
    )
    assert res_put.status_code == 200
    assert res_put.get_json()["ok"] is True
    assert res_put.get_json()["filter"]["name"] == "修改后的高频餐饮"
    assert res_put.get_json()["filter"]["is_pinned"] is True

    # 5. GET /records 验证带 filter_id 渲染
    res_rec_preset = logged_in_client.get(f"/records?filter_id={filter_id}")
    assert res_rec_preset.status_code == 200
    text = res_rec_preset.get_data(as_text=True)
    assert "修改后的高频餐饮" in text

    # 6. GET /records 验证综合多维搜索参数
    res_rec_search = logged_in_client.get("/records?q=Starbucks&type=expense&min_amount=10&max_amount=50")
    assert res_rec_search.status_code == 200

    # 7. DELETE /api/saved-filters/<id> 删除预设
    res_del = logged_in_client.delete(f"/api/saved-filters/{filter_id}")
    assert res_del.status_code == 200
    assert res_del.get_json()["ok"] is True


def test_native_saved_filters_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Native 原生伴侣端筛选预设及多维交易列表 API"""
    headers = {"X-API-KEY": "test-auto-track-key"}

    # 1. GET /api/native/saved-filters
    res_get = logged_in_client.get("/api/native/saved-filters?username=admin", headers=headers)
    assert res_get.status_code == 200
    body = res_get.get_json()
    assert body["ok"] is True
    assert len(body["filters"]) >= 4

    # 2. POST /api/native/saved-filters
    res_create = logged_in_client.post(
        "/api/native/saved-filters?username=admin",
        json={
            "name": "Native大额支出",
            "criteria": {"type": "expense", "min_amount": 500},
            "is_pinned": True
        },
        headers=headers
    )
    assert res_create.status_code == 200
    created = res_create.get_json()
    assert created["ok"] is True
    new_id = created["filter"]["id"]

    # 3. PUT /api/native/saved-filters/<id>
    res_put = logged_in_client.put(
        f"/api/native/saved-filters/{new_id}?username=admin",
        json={"name": "Native超大额支出", "is_pinned": False},
        headers=headers
    )
    assert res_put.status_code == 200
    assert res_put.get_json()["ok"] is True
    assert res_put.get_json()["filter"]["name"] == "Native超大额支出"

    # 4. GET /api/native/transactions 带金额和时间过滤
    res_tx = logged_in_client.get(
        "/api/native/transactions?username=admin&type=expense&min_amount=100&max_amount=1000",
        headers=headers
    )
    assert res_tx.status_code == 200
    tx_body = res_tx.get_json()
    assert tx_body["ok"] is True
    assert "items" in tx_body and isinstance(tx_body["items"], list)

    # 5. DELETE /api/native/saved-filters/<id>
    res_del = logged_in_client.delete(f"/api/native/saved-filters/{new_id}?username=admin", headers=headers)
    assert res_del.status_code == 200
    assert res_del.get_json()["ok"] is True
