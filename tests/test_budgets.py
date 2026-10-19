"""
tests/test_budgets.py
预算核心服务层、Web 端与 Native 原生伴侣端 API 完整自动化测试套件
"""

import sqlite3
from contextlib import contextmanager
from datetime import date

from services.budget_service import (
    get_overall_budget_limit,
    set_overall_budget,
    set_category_budget,
    delete_category_budget,
    get_budget_overview
)


@contextmanager
def _test_db(flask_app):
    conn = sqlite3.connect(flask_app.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def test_budget_service_overall_crud(flask_app, admin_user_id):
    """测试月度总预算的设置、查询与取消"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM category_budgets WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 1. 默认无总预算
        assert get_overall_budget_limit(admin_user_id, db) == 0.0

        # 2. 设置总预算为 3500.00
        res = set_overall_budget(admin_user_id, 3500.0, db)
        assert res["ok"] is True
        assert res["monthly_limit"] == 3500.0
        assert get_overall_budget_limit(admin_user_id, db) == 3500.0

        # 3. 更新总预算为 4000.50
        res = set_overall_budget(admin_user_id, 4000.50, db)
        assert res["ok"] is True
        assert res["monthly_limit"] == 4000.50
        assert get_overall_budget_limit(admin_user_id, db) == 4000.50

        # 4. 取消总预算 (输入 0)
        res = set_overall_budget(admin_user_id, 0.0, db)
        assert res["ok"] is True
        assert res["monthly_limit"] == 0.0
        assert get_overall_budget_limit(admin_user_id, db) == 0.0


def test_budget_service_category_crud(flask_app, admin_user_id):
    """测试单分类预算的增删改"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM category_budgets WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

        # 1. 设置餐饮分类预算
        res = set_category_budget(admin_user_id, "餐饮", 800.0, db)
        assert res["ok"] is True
        assert res["category"] == "餐饮"
        assert res["monthly_limit"] == 800.0

        # 2. set_category_budget 传入 __OVERALL__ 自动代理到总预算
        res_overall = set_category_budget(admin_user_id, "__OVERALL__", 5000.0, db)
        assert res_overall["ok"] is True
        assert get_overall_budget_limit(admin_user_id, db) == 5000.0

        # 3. 删除/取消餐饮分类预算
        res_del = delete_category_budget(admin_user_id, "餐饮", db)
        assert res_del["ok"] is True
        assert res_del["monthly_limit"] == 0.0


def test_budget_service_overview_calculation(flask_app, admin_user_id):
    """测试预算概览的全面计算：总预算进度、每日安全限额、分类进度与警戒级别"""
    today_str = date.today().strftime("%Y-%m-%d")
    current_month = date.today().strftime("%Y-%m")

    with _test_db(flask_app) as db:
        # 清理旧数据
        db.execute("DELETE FROM transactions WHERE user_id = ?", (str(admin_user_id),))
        db.execute("DELETE FROM category_budgets WHERE user_id = ?", (str(admin_user_id),))

        # 插入分类与颜色
        db.execute(
            "INSERT OR REPLACE INTO categories (user_id, name, type, color) VALUES (?, ?, 'expense', ?)",
            (str(admin_user_id), "餐饮", "#FF5733")
        )
        db.execute(
            "INSERT OR REPLACE INTO categories (user_id, name, type, color) VALUES (?, ?, 'expense', ?)",
            (str(admin_user_id), "交通", "#33FF57")
        )

        # 设置总预算 2000，餐饮预算 500，交通预算 300
        set_overall_budget(admin_user_id, 2000.0, db)
        set_category_budget(admin_user_id, "餐饮", 500.0, db)
        set_category_budget(admin_user_id, "交通", 300.0, db)

        # 插入支出记录: 餐饮花费 300 (60% ok), 交通花费 270 (90% warn), 购物花费 100
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', '餐饮', 300.0, '午餐', 'manual', datetime('now'))",
            (str(admin_user_id), today_str)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', '交通', 270.0, '打车', 'manual', datetime('now'))",
            (str(admin_user_id), today_str)
        )
        db.execute(
            "INSERT INTO transactions (user_id, date, type, category, amount, note, source, created_at) "
            "VALUES (?, ?, 'expense', '购物', 100.0, '买书', 'manual', datetime('now'))",
            (str(admin_user_id), today_str)
        )
        db.commit()

        # 计算概览
        overview = get_budget_overview(admin_user_id, month=current_month, db=db)
        assert overview["ok"] is True
        assert overview["month"] == current_month

        # 周期检验
        cycle = overview["billing_cycle"]
        assert cycle["total_days"] >= 28
        assert cycle["remaining_days"] >= 0

        # 总预算检验: 总限额 2000, 总支出 670, 剩余 1330
        overall = overview["overall"]
        assert overall["limit"] == 2000.0
        assert overall["spent"] == 670.0
        assert overall["remaining"] == 1330.0
        assert overall["pct"] == round((670.0 / 2000.0) * 100, 1)
        assert overall["level"] == "ok"
        assert overall["is_explicit"] is True
        assert overall["is_set"] is True
        if cycle["remaining_days"] > 0:
            assert overall["daily_allowance"] > 0

        # 分类检验
        cats = {c["category"]: c for c in overview["categories"]}
        assert "餐饮" in cats
        assert cats["餐饮"]["limit"] == 500.0
        assert cats["餐饮"]["spent"] == 300.0
        assert cats["餐饮"]["remaining"] == 200.0
        assert cats["餐饮"]["level"] == "ok"
        assert cats["餐饮"]["color"] == "#FF5733"

        assert "交通" in cats
        assert cats["交通"]["limit"] == 300.0
        assert cats["交通"]["spent"] == 270.0
        assert cats["交通"]["pct"] == 90.0
        assert cats["交通"]["level"] == "warn"


def test_web_budget_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Web 端预算 REST API"""
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM category_budgets WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

    # 1. 设置月度总预算 (POST /api/budgets/overall)
    res_overall = logged_in_client.post("/api/budgets/overall", json={"monthly_limit": 2500.0})
    assert res_overall.status_code == 200
    assert res_overall.get_json()["ok"] is True
    assert res_overall.get_json()["monthly_limit"] == 2500.0

    # 2. 设置分类预算 (POST /api/budgets/category)
    res_cat = logged_in_client.post("/api/budgets/category", json={"category": "娱乐", "monthly_limit": 450.0})
    assert res_cat.status_code == 200
    assert res_cat.get_json()["ok"] is True
    assert res_cat.get_json()["monthly_limit"] == 450.0

    # 3. 查询预算概览 (GET /api/budgets)
    res_view = logged_in_client.get("/api/budgets")
    assert res_view.status_code == 200
    view_data = res_view.get_json()
    assert view_data["ok"] is True
    assert view_data["overall"]["limit"] == 2500.0
    assert any(c["category"] == "娱乐" for c in view_data["categories"])

    # 4. 删除分类预算 (POST /api/budgets/delete)
    res_del = logged_in_client.post("/api/budgets/delete", json={"category": "娱乐"})
    assert res_del.status_code == 200
    assert res_del.get_json()["ok"] is True

    # 5. 访问分类页面验证包含总预算专区 (GET /categories)
    res_page = logged_in_client.get("/categories")
    assert res_page.status_code == 200
    page_text = res_page.get_data(as_text=True)
    assert "月度总预算" in page_text or "Overall Budget" in page_text


def test_native_budget_endpoints(logged_in_client, flask_app, admin_user_id):
    """测试 Native 原生伴侣端预算 REST API"""
    headers = {"X-API-KEY": "test-auto-track-key"}
    with _test_db(flask_app) as db:
        db.execute("DELETE FROM category_budgets WHERE user_id = ?", (str(admin_user_id),))
        db.commit()

    # 1. Native 设置总预算 (POST /api/native/budgets/overall)
    res1 = logged_in_client.post(
        "/api/native/budgets/overall?username=admin",
        json={"monthly_limit": 3200.0},
        headers=headers
    )
    assert res1.status_code == 200
    assert res1.get_json()["ok"] is True

    # 2. Native 设置分类预算 (POST /api/native/budgets/category)
    res2 = logged_in_client.post(
        "/api/native/budgets/category?username=admin",
        json={"category": "数码", "monthly_limit": 600.0},
        headers=headers
    )
    assert res2.status_code == 200
    assert res2.get_json()["ok"] is True

    # 3. Native 查询概览 (GET /api/native/budgets)
    res3 = logged_in_client.get("/api/native/budgets?username=admin", headers=headers)
    assert res3.status_code == 200
    b_data = res3.get_json()
    assert b_data["ok"] is True
    assert b_data["overall"]["limit"] == 3200.0
    assert any(c["category"] == "数码" for c in b_data["categories"])

    # 4. Native 删除分类预算 (POST /api/native/budgets/delete)
    res4 = logged_in_client.post(
        "/api/native/budgets/delete?username=admin",
        json={"category": "数码"},
        headers=headers
    )
    assert res4.status_code == 200
    assert res4.get_json()["ok"] is True
