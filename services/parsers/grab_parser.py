import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class GrabParser(NotificationParserStrategy):
    """GrabPay / GrabFood / Grab 专属解析策略"""

    PAYMENT_PATTERN = re.compile(
        r'(?:paid|payment of)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|for)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
        re.IGNORECASE
    )

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        return any(k in t for k in ['grab', 'grabpay', 'grabfood', 'grabcar'])

    def parse(self, text: str) -> Optional[ParsedNotification]:
        today_str = date.today().isoformat()
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

        # 简单金额提取
        m_amt = re.search(r'(?:RM|MYR)\s*([0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
        if m_amt:
            try:
                amt = float(m_amt.group(1))
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
