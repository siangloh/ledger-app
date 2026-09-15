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
