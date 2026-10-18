"""
tests/test_category_rules.py
商户分类规则与纠正记忆服务层、API及端到端集成测试套件
"""

import sqlite3
from contextlib import contextmanager

from services.category_rules_service import (
    get_category_rules,
    create_or_update_rule,
    delete_rule,
    toggle_rule_status,
    match_category_for_note,
    apply_rule_to_history,
    apply_all_rules_to_history
)

AJAX_HEADERS = {"X-Requested-With": "XMLHttpRequest"}


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def _insert_tx(flask_app, user_id, note, category="未分类", amount=15.0, date_str="2026-10-18"):
    with _test_db(flask_app) as db:
        cur = db.cursor()
        cur.execute(
            """
            INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at)
            VALUES (?, ?, 'expense', ?, ?, ?, 'manual', datetime('now'))
            """,
            (str(user_id), date_str, category, amount, note)
        )
        db.commit()
        return cur.lastrowid


def test_rule_crud_service(flask_app, admin_user_id):
    """测试分类规则的创建、查询、状态切换与删除"""
    with _test_db(flask_app) as db:
        # 清理旧测试数据
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 1. 创建 contains 规则
        res1 = create_or_update_rule(
            user_id=admin_user_id,
            merchant_pattern="FamilyMart",
            category="餐饮",
            match_type="contains",
            is_active=1,
            db=db
        )
        assert res1["ok"] is True
        assert res1["merchant_pattern"] == "FamilyMart"

        # 2. 查询规则列表
        rules_res = get_category_rules(user_id=admin_user_id, db=db)
        assert rules_res["ok"] is True
        assert rules_res["total_count"] == 1
        assert rules_res["rules"][0]["merchant_pattern"] == "FamilyMart"
        assert rules_res["rules"][0]["category"] == "餐饮"
        assert rules_res["rules"][0]["match_type"] == "contains"

        # 3. 切换状态 (停用 -> 启用)
        tog_res = toggle_rule_status(user_id=admin_user_id, merchant_pattern="FamilyMart", db=db)
        assert tog_res["ok"] is True
        assert tog_res["is_active"] == 0

        tog_res2 = toggle_rule_status(user_id=admin_user_id, merchant_pattern="FamilyMart", db=db)
        assert tog_res2["ok"] is True
        assert tog_res2["is_active"] == 1

        # 4. 删除规则
        del_res = delete_rule(user_id=admin_user_id, merchant_pattern="FamilyMart", db=db)
        assert del_res["ok"] is True
        assert get_category_rules(user_id=admin_user_id, db=db)["total_count"] == 0


def test_multitier_matching_priority(flask_app, admin_user_id):
    """测试多级规则匹配引擎：精确匹配 > 长词包含 > 短词包含 > 正则，以及命中统计自增"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 规则 1: 短词 Grab -> 交通
        create_or_update_rule(admin_user_id, "Grab", "交通", match_type="contains", db=db)
        # 规则 2: 长词 GrabFood -> 外卖
        create_or_update_rule(admin_user_id, "GrabFood", "外卖", match_type="contains", db=db)
        # 规则 3: 正则匹配 TNG 转账 -> 个人转账
        create_or_update_rule(admin_user_id, r"^TNG\s+Transfer", "个人转账", match_type="regex", db=db)
        # 规则 4: 精确匹配 KFC -> 餐饮
        create_or_update_rule(admin_user_id, "KFC", "餐饮", match_type="exact", db=db)

        # Case 1: 备注为 'GrabFood Order #1234'，应长词优先命中 '外卖' 而非 '交通'
        matched1 = match_category_for_note("GrabFood Order #1234", admin_user_id, db)
        assert matched1 == "外卖"

        # Case 2: 备注为 'Grab Ride to KLCC'，命中短词 '交通'
        matched2 = match_category_for_note("Grab Ride to KLCC", admin_user_id, db)
        assert matched2 == "交通"

        # Case 3: 正则匹配 'TNG Transfer to Ali'
        matched3 = match_category_for_note("TNG Transfer to Ali", admin_user_id, db)
        assert matched3 == "个人转账"

        # Case 4: 精确匹配 'KFC'
        matched4 = match_category_for_note("KFC", admin_user_id, db)
        assert matched4 == "餐饮"

        # Case 5: 'KFC Delivery' 由于 KFC 是 exact 规则，不会被匹配
        matched5 = match_category_for_note("KFC Delivery", admin_user_id, db)
        assert matched5 is None

        # 验证命中计数 (apply_count) 是否正确自增
        rules = get_category_rules(admin_user_id, db)["rules"]
        grab_food_rule = next(r for r in rules if r["merchant_pattern"] == "GrabFood")
        assert grab_food_rule["apply_count"] >= 1


def test_apply_rule_to_history(flask_app, admin_user_id):
    """测试将单条商户规则追溯应用到历史流水"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM transactions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 插入 3 笔流水：2 笔未分类，1 笔已有人工分类
        tx1 = _insert_tx(flask_app, admin_user_id, "Starbucks Midvalley", category="未分类")
        tx2 = _insert_tx(flask_app, admin_user_id, "Starbucks KLIA", category="其他")
        tx3 = _insert_tx(flask_app, admin_user_id, "Starbucks Office", category="商务招待")

        create_or_update_rule(admin_user_id, "Starbucks", "咖啡饮品", match_type="contains", db=db)

        # 1. 仅更新未分类 (only_uncategorized=True)
        res_safe = apply_rule_to_history(
            user_id=admin_user_id,
            merchant_pattern="Starbucks",
            category="咖啡饮品",
            match_type="contains",
            only_uncategorized=True,
            db=db
        )
        assert res_safe["ok"] is True
        assert res_safe["count"] == 2

        # 验证数据库状态
        row1 = db.execute("SELECT category FROM transactions WHERE id = ?", (tx1,)).fetchone()
        row2 = db.execute("SELECT category FROM transactions WHERE id = ?", (tx2,)).fetchone()
        row3 = db.execute("SELECT category FROM transactions WHERE id = ?", (tx3,)).fetchone()
        assert row1["category"] == "咖啡饮品"
        assert row2["category"] == "咖啡饮品"
        assert row3["category"] == "商务招待"  # 保持既有人工分类不变

        # 2. 全量覆盖历史 (only_uncategorized=False)
        res_all = apply_rule_to_history(
            user_id=admin_user_id,
            merchant_pattern="Starbucks",
            category="咖啡饮品",
            match_type="contains",
            only_uncategorized=False,
            db=db
        )
        assert res_all["ok"] is True
        assert res_all["count"] == 1  # 仅剩 tx3 被更新
        row3_updated = db.execute("SELECT category FROM transactions WHERE id = ?", (tx3,)).fetchone()
        assert row3_updated["category"] == "咖啡饮品"


def test_apply_all_rules_to_history(flask_app, admin_user_id):
    """测试全量规则一键重筛历史"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM transactions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        _insert_tx(flask_app, admin_user_id, "Shell Fuel RON95", category="未分类")
        _insert_tx(flask_app, admin_user_id, "Netflix Subscription", category="未分类")

        create_or_update_rule(admin_user_id, "Shell", "交通加油", match_type="contains", db=db)
        create_or_update_rule(admin_user_id, "Netflix", "娱乐订阅", match_type="contains", db=db)

        res = apply_all_rules_to_history(user_id=admin_user_id, only_uncategorized=True, db=db)
        assert res["ok"] is True
        assert res["count"] == 2


def test_web_category_rules_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Web 端分类规则 REST API 与页面渲染"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

    # 1. 保存规则 (POST /api/category-rules)
    save_resp = logged_in_client.post("/api/category-rules", json={
        "merchant_pattern": "Uniqlo",
        "category": "服饰",
        "match_type": "contains",
        "is_active": 1
    })
    assert save_resp.status_code == 200
    assert save_resp.get_json()["ok"] is True

    # 2. 查询列表 (GET /api/category-rules)
    list_resp = logged_in_client.get("/api/category-rules")
    assert list_resp.status_code == 200
    json_data = list_resp.get_json()
    assert json_data["ok"] is True
    assert json_data["total_count"] == 1
    assert json_data["rules"][0]["merchant_pattern"] == "Uniqlo"

    # 3. 状态切换 (POST /api/category-rules/toggle)
    tog_resp = logged_in_client.post("/api/category-rules/toggle", json={"merchant_pattern": "Uniqlo"})
    assert tog_resp.status_code == 200
    assert tog_resp.get_json()["is_active"] == 0

    # 4. 访问分类管理 Web 页面 (GET /categories)
    page_resp = logged_in_client.get("/categories")
    assert page_resp.status_code == 200
    assert "商户分类规则与纠正记忆" in page_resp.get_data(as_text=True)
    assert "Uniqlo" in page_resp.get_data(as_text=True)

    # 5. 删除规则 (POST /api/category-rules/delete)
    del_resp = logged_in_client.post("/api/category-rules/delete", json={"merchant_pattern": "Uniqlo"})
    assert del_resp.status_code == 200
    assert del_resp.get_json()["ok"] is True


def test_native_category_rules_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Native 原生伴侣端分类规则 REST API"""
    headers = {"X-API-KEY": "test-auto-track-key"}
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM merchant_category_overrides WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

    # 1. 保存规则
    save_res = logged_in_client.post(
        "/api/native/category-rules?username=admin",
        headers=headers,
        json={
            "merchant_pattern": "Watsons",
            "category": "日用保健",
            "match_type": "contains",
            "is_active": 1
        }
    )
    assert save_res.status_code == 200
    assert save_res.get_json()["ok"] is True

    # 2. 获取列表
    list_res = logged_in_client.get("/api/native/category-rules?username=admin", headers=headers)
    assert list_res.status_code == 200
    rules = list_res.get_json()["rules"]
    assert any(r["merchant_pattern"] == "Watsons" for r in rules)

    # 3. 追溯历史
    _insert_tx(flask_app, admin_user_id, "Watsons Pavilion", category="未分类")
    apply_res = logged_in_client.post(
        "/api/native/category-rules/apply-history?username=admin",
        headers=headers,
        json={
            "merchant_pattern": "Watsons",
            "category": "日用保健",
            "match_type": "contains",
            "only_uncategorized": True
        }
    )
    assert apply_res.status_code == 200
    assert apply_res.get_json()["count"] == 1

    # 4. 删除规则
    del_res = logged_in_client.post(
        "/api/native/category-rules/delete?username=admin",
        headers=headers,
        json={"merchant_pattern": "Watsons"}
    )
    assert del_res.status_code == 200
    assert del_res.get_json()["ok"] is True
