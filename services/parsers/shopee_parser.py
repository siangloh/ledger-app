import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class ShopeeParser(NotificationParserStrategy):
    """Shopee / ShopeePay 专属解析策略（支持电商交易与营销广告智能过滤）"""

    SHOPEE_BRAND_PATTERN = re.compile(
        r'\b(?:shopeepay|shopee)\b|com\.shopee\.my',
        re.IGNORECASE
    )

    # 营销广告关键词（包含时直接拦截，避免进入账本）
    PROMO_KEYWORDS = [
        'just for you', 'cashback', 'cash back', 'coins', 'shopee coins',
        'voucher', 'vouchers', 'baucar', 'free shipping', 'no min. spend', 'no min spend',
        'min. spend', 'min spend', 'limited redemption', 'limited redemptions',
        'pay with duitnow', 'pay with shopeepay', 'pay with', 'bayar guna duitnow', 'bayar guna',
        'live stream', 'livestream', 'shocking sale', 'flash sale', 'mega sale',
        'super brand day', 'brand day', '% off', 'diskaun', 'discount',
        'check out in-store now', 'check out now', 'shop now', 'add to cart',
        'rebut', 'tebus', 'spin & win', 'shake & win'
    ]

    # 已完成交易动词特征（如已发生扣款）
    COMPLETED_PAYMENT_PATTERNS = [
        # 例如: "Payment of RM 25.50 to ABC was successful"
        re.compile(
            r'(?:payment of|paid)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
            re.IGNORECASE
        ),
        # 例如: "You have successfully paid RM 15.00 to FamilyMart"
        re.compile(
            r'(?:you(?:\'ve|\s+have)?\s+successfully\s+paid|you(?:\'ve|\s+have)?\s+paid)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
            re.IGNORECASE
        ),
        # 倒序: "RM 32.00 has been deducted from your ShopeePay"
        re.compile(
            r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:has been deducted|paid|charged|spent)',
            re.IGNORECASE
        ),
        # 订单扣款: "Order #12345 paid RM 45.00" or "Payment successful: RM 20.00 to Merchant"
        re.compile(
            r'(?:payment successful|successful payment)(?::|\s+of)?\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})?',
            re.IGNORECASE
        )
    ]

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        if self.SHOPEE_BRAND_PATTERN.search(text):
            return True
        # 具有 Shopee 营销或专属文案
        if any(w in t for w in ['shopee', 'shopeepay']):
            return True
        return False

    def parse(self, text: str) -> Optional[ParsedNotification]:
        t = text.lower()

        # 1. 尝试匹配明确的真实扣款动账
        for pattern in self.COMPLETED_PAYMENT_PATTERNS:
            m = pattern.search(text)
            if m:
                try:
                    amt = float(m.group(1).replace(',', ''))
                    merchant = ''
                    if m.lastindex and m.lastindex >= 2 and m.group(2):
                        raw_m = m.group(2).strip()
                        merchant = re.split(
                            r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                            raw_m,
                            flags=re.IGNORECASE
                        )[0].strip(' .,-')

                    if not merchant:
                        merchant = 'Shopee'

                    return ParsedNotification(
                        amount=amt,
                        type='expense',
                        category='购物',
                        merchant=merchant,
                        note=merchant,
                        date=date.today().isoformat(),
                        channel='shopeepay',
                        confidence=0.92,
                        raw_text=text
                    )
                except (ValueError, IndexError):
                    pass

        # 2. 如果包含营销词且无明确扣款，判定为广告营销
        if any(w in t for w in self.PROMO_KEYWORDS):
            return ParsedNotification(
                amount=0.0,
                type='expense',
                category='广告过滤',
                merchant='Shopee营销推广',
                is_promo=True,
                note='命中Shopee营销推广/优惠卡券活动',
                raw_text=text
            )

        return None
