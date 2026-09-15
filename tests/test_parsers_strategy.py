from services.parsers.tng_parser import TouchNGoParser
from services.parsers.maybank_parser import MaybankParser
from services.parsers.grab_parser import GrabParser
from services.parsers.bank_card_parser import BankCardParser
from services.parsers.registry import NotificationParserComposite, _PARSER_REGISTRY
from services.notification_service import parse_auto_track_notification


def test_tng_parser_payment():
    parser = TouchNGoParser()
    text = "Touch 'n Go eWallet: You have paid RM 25.50 to FamilyMart on 2026-03-15."
    assert parser.can_handle(text) is True
    res = parser.parse(text)
    assert res is not None
    assert res.amount == 25.50
    assert res.type == "expense"
    assert "FamilyMart" in res.merchant
    assert res.channel == "TnG"


def test_tng_parser_refund():
    parser = TouchNGoParser()
    text = "Touch 'n Go eWallet: Payment refunded of RM 120.00 from PETRONAS."
    assert parser.can_handle(text) is True
    res = parser.parse(text)
    assert res is not None
    assert res.amount == 120.00
    assert res.type == "income"
    assert res.is_refund is True


def test_maybank_parser():
    parser = MaybankParser()
    text = "MAE by Maybank2u: RM 45.00 transferred to Ah Seng via DuitNow."
    assert parser.can_handle(text) is True
    res = parser.parse(text)
    assert res is not None
    assert res.amount == 45.00
    assert res.type == "expense"
    assert "Ah Seng" in res.merchant


def test_grab_parser():
    parser = GrabParser()
    text = "GrabPay: You paid RM 18.90 for your GrabFood order."
    assert parser.can_handle(text) is True
    res = parser.parse(text)
    assert res is not None
    assert res.amount == 18.90
    assert res.type == "expense"
    assert res.category == "餐饮"


def test_bank_card_parser():
    parser = BankCardParser()
    text = "Public Bank: RM 150.00 spent at UNIQULO on 12/03 with card ending 8888."
    assert parser.can_handle(text) is True
    res = parser.parse(text)
    assert res is not None
    assert res.amount == 150.00
    assert res.type == "expense"
    assert "UNIQULO" in res.merchant


def test_composite_promo_and_internal_transfer_filtering():
    composite = NotificationParserComposite()

    # 1. 营销广告
    promo_text = "Apply online today for Public Bank Credit Card and get 100% cashback! T&Cs apply."
    res_promo = composite.parse(promo_text)
    assert res_promo is not None
    assert res_promo.is_promo is True

    # 2. 内部划转
    transfer_text = "Touch 'n Go eWallet: RM 50.00 cashed in into your GO+ account."
    res_transfer = composite.parse(transfer_text)
    assert res_transfer is not None
    assert res_transfer.is_internal_transfer is True


def test_backward_compatibility_parse_auto_track_notification():
    """验证顶层函数 parse_auto_track_notification 依然返回原版字典结构"""
    raw_text = "Touch 'n Go eWallet: You have paid RM 35.00 to Shell."
    d = parse_auto_track_notification(raw_text)
    assert isinstance(d, dict)
    assert d["amount"] == 35.00
    assert d["type"] == "expense"
    assert d["category"] == "交通"  # 经由商户映射表成功强化
    assert "Shell" in d["note"]


def test_registry_has_auto_registered_parsers():
    """验证装饰器已成功自动注册策略类"""
    assert len(_PARSER_REGISTRY) >= 4


def test_shopee_promo_containing_grab_is_rejected():
    """验证包含 grab 动词的电商/Shopee营销通知不会被当作 Grab 交易，并被识别为广告"""
    parser = GrabParser()
    composite = NotificationParserComposite()

    t1 = "Time to REBUT RM500! Tebus Baucar Padu RM500 NOW & grab Yamaha Motor @ RM10 + Baucar Gempak 30% OFF!"
    t2 = "Dreame Super Brand Day ✨ Grab the new arrival AQUA20 Robot Vacuum & more + RM300 OFF. Upgrade to Dreame now!"
    t3 = "50% OFF + PS5 at RM10! 😍 Claim 50% OFF + Free Shipping to checkout PS5 @ RM10 & Casio Men Watch @ RM106 NOW!"
    t4 = "MONTIGO @ RM10!! 😄 SAVE up to 50% OFF on MONTIGO Whimsical Bottle + Free Shipping! Add to cart NOW! 🛒"

    # Grab 策略绝不接管此类营销文案
    assert parser.can_handle(t1) is False
    assert parser.can_handle(t2) is False

    # 复合解析器将其正确判定为营销广告
    for text in [t1, t2, t3, t4]:
        res = composite.parse(text)
        assert res is not None, f"Expected promo parsed for: {text}"
        assert res.is_promo is True, f"Expected is_promo=True for: {text}"

    # 顶层包装函数一致性
    p1 = parse_auto_track_notification(t1)
    assert p1 is not None and p1.get('is_promo') is True
    p2 = parse_auto_track_notification(t2)
    assert p2 is not None and p2.get('is_promo') is True


def test_auto_track_deduplication(client, admin_user_id):
    """验证完全相同的通知重复提交时触发防重机制，杜绝多次记账"""
    text = "Touch 'n Go eWallet: You have paid RM 9.00 to Golden Roastery."
    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}

    # 第 1 次提交：成功入账
    r1 = client.post("/api/auto-track", json={"text": text, "user_id": admin_user_id}, headers=headers)
    assert r1.status_code == 201
    d1 = r1.get_json()
    assert d1["ok"] is True
    assert d1["verdict"] == "accepted"
    tx_id = d1["transaction_id"]

    # 第 2 次提交（模拟离线队列重试或网络重复报送）：幂等拦截
    r2 = client.post("/api/auto-track", json={"text": text, "user_id": admin_user_id}, headers=headers)
    assert r2.status_code == 200
    d2 = r2.get_json()
    assert d2["ok"] is True
    assert d2["verdict"] == "duplicate_ignored"
    assert d2["transaction_id"] == tx_id


def test_auto_track_multi_user_isolation(client, admin_user_id, flask_app):
    """验证多用户通过 username 指定记账时，数据严格隔离且不可互相越权查看"""
    import uuid
    from werkzeug.security import generate_password_hash
    import sqlite3

    db = sqlite3.connect(flask_app.DB_PATH)
    db.row_factory = sqlite3.Row
    alice_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
        (alice_id, "alice", generate_password_hash("Pass123!"), "2026-09-15T00:00:00")
    )
    db.commit()

    headers = {"X-API-KEY": "test-auto-track-key", "Content-Type": "application/json"}
    text = "Touch 'n Go eWallet: You have paid RM 15.00 to Kinokuniya Bookstore."

    # 手机端/Webhook 携带 username=alice 上报
    res = client.post("/api/auto-track", json={"text": text, "username": "alice"}, headers=headers)
    assert res.status_code == 201
    d = res.get_json()
    assert d["ok"] is True
    assert d["verdict"] == "accepted"
    tx_id = d["transaction_id"]

    # 1. 验证交易在数据库中严格归属于 alice
    row = db.execute("SELECT user_id, amount, note FROM transactions WHERE id = ?", (tx_id,)).fetchone()
    assert row["user_id"] == alice_id
    assert row["amount"] == 15.00
    assert "Kinokuniya" in row["note"]

    # 2. 验证 admin 账号的明细中完全没有这笔记录，且 tx_id 绝不在 admin 交易列表中
    admin_rows = db.execute("SELECT id FROM transactions WHERE user_id = ? AND note LIKE '%Kinokuniya%'", (admin_user_id,)).fetchall()
    assert len(admin_rows) == 0
    all_admin_tx_ids = [r["id"] for r in db.execute("SELECT id FROM transactions WHERE user_id = ?", (admin_user_id,)).fetchall()]
    assert tx_id not in all_admin_tx_ids
    db.close()

    # 3. 验证 alice 登录后打开 /auto-track 页面时，生成的 Webhook 地址自动包含其专属 username
    with client.session_transaction() as sess:
        sess["logged_in"] = True
        sess["user_id"] = alice_id
        sess["username"] = "alice"

    page_resp = client.get("/auto-track")
    assert page_resp.status_code == 200
    html = page_resp.get_data(as_text=True)
    assert "username=alice" in html
    assert "专属绑定用户：alice" in html
