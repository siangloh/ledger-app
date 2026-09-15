import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class GrabParser(NotificationParserStrategy):
    """GrabPay / GrabFood / Grab 专属解析策略"""

    # 匹配 Grab 官方服务标识
    GRAB_BRAND_PATTERN = re.compile(
        r'\b(?:grabpay|grabfood|grabcar|grabride|grabmart|grabexpress|grab\s+wallet|grab\s+driver)\b|^grab\s*[:\-|]',
        re.IGNORECASE
    )

    # 匹配 Grab 真实支付动账句式 (含付款动词与金额)
    PAYMENT_PATTERN = re.compile(
        r'(?:you(?:\'ve|\s+have)?\s+paid|payment of|spent|charged)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
        re.IGNORECASE
    )

    # 倒序句式：如 "RM 18.90 paid to GrabCar"
    REVERSE_PAYMENT_PATTERN = re.compile(
        r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:paid|transferred|spent|charged)\s+(?:to|for|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
        re.IGNORECASE
    )

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        # 排除包含电商营销词的句子，避免诸如 "grab Yamaha Motor @ RM10" 误判
        if any(w in t for w in ['baucar', 'voucher', 'tebus', 'rebut', 'super brand day', 'brand day', '% off', 'free shipping', 'add to cart']):
            return False

        # 1. 明确的 Grab 品牌/产品标识
        if self.GRAB_BRAND_PATTERN.search(text):
            return True

        # 2. 独立单词 grab，但必须同时具备支付上下文 (如 "grab ... paid", "grab ... order")
        if re.search(r'\bgrab\b', t):
            has_payment_context = any(w in t for w in [
                'paid', 'payment', 'grabpay', 'grabfood', 'grabcar', 'order', 'trip', 'ride', 'receipt'
            ])
            return has_payment_context

        return False

    def parse(self, text: str) -> Optional[ParsedNotification]:
        today_str = date.today().isoformat()

        # 1. 标准句式匹配
        m_pay = self.PAYMENT_PATTERN.search(text)
        if m_pay:
            try:
                amt = float(m_pay.group(1).replace(',', ''))
                merchant = m_pay.group(2).strip()
                cleaned_merchant = re.split(
                    r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                    merchant,
                    flags=re.IGNORECASE
                )[0].strip(' .,-')

                cat = '餐饮' if 'food' in text.lower() else '交通'
                return ParsedNotification(
                    amount=amt,
                    type='expense',
                    category=cat,
                    merchant=cleaned_merchant or 'Grab',
                    note=cleaned_merchant or 'Grab',
                    date=today_str,
                    channel='Grab',
                    confidence=0.96,
                    raw_text=text
                )
            except ValueError:
                pass

        # 2. 倒序句式匹配 (如 "RM 25.00 paid to GrabCar")
        m_rev = self.REVERSE_PAYMENT_PATTERN.search(text)
        if m_rev:
            try:
                amt = float(m_rev.group(1).replace(',', ''))
                merchant = m_rev.group(2).strip()
                cleaned_merchant = re.split(
                    r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                    merchant,
                    flags=re.IGNORECASE
                )[0].strip(' .,-')

                cat = '餐饮' if 'food' in text.lower() else '交通'
                return ParsedNotification(
                    amount=amt,
                    type='expense',
                    category=cat,
                    merchant=cleaned_merchant or 'Grab',
                    note=cleaned_merchant or 'Grab',
                    date=today_str,
                    channel='Grab',
                    confidence=0.95,
                    raw_text=text
                )
            except ValueError:
                pass

        # 3. 针对已确认包含 Grab 品牌且具有明确动账动词的通知提取金额
        lower_text = text.lower()
        has_strict_payment_verb = any(v in lower_text for v in ['you paid', "you've paid", 'you have paid', 'payment of', 'order completed'])
        if has_strict_payment_verb:
            m_amt = re.search(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
            if m_amt:
                try:
                    amt = float(m_amt.group(1).replace(',', ''))
                    cat = '餐饮' if 'food' in text.lower() else '交通'
                    return ParsedNotification(
                        amount=amt,
                        type='expense',
                        category=cat,
                        merchant='Grab',
                        note='Grab消费',
                        date=today_str,
                        channel='Grab',
                        confidence=0.90,
                        raw_text=text
                    )
                except ValueError:
                    pass

        return None
