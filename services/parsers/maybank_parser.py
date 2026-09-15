import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class MaybankParser(NotificationParserStrategy):
    """Maybank / MAE 专属解析策略"""

    PAYMENT_PATTERN = re.compile(
        r'(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:transferred to|paid to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
        re.IGNORECASE
    )
    INCOME_PATTERN = re.compile(
        r'(?:received|transfer from|duitnow transfer from)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:from)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})?',
        re.IGNORECASE
    )

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        return any(k in t for k in ['maybank', 'mae', 'm2u', 'maybank2u'])

    def parse(self, text: str) -> Optional[ParsedNotification]:
        lower_text = text.lower()
        today_str = date.today().isoformat()

        # 1. 检查收入 / 朋友还款
        if any(k in lower_text for k in ['received', 'credited', 'transfer from', '转入', '收到']):
            m_in = self.INCOME_PATTERN.search(text)
            amt = None
            if m_in and m_in.group(1):
                try:
                    amt = float(m_in.group(1).replace(',', ''))
                except ValueError:
                    pass
            if not amt:
                m_amt = re.search(r'(?:RM|MYR)\s*([0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
                if m_amt:
                    amt = float(m_amt.group(1))

            if amt and amt > 0:
                sender = (m_in.group(2).strip() if m_in and m_in.group(2) else '') or 'Maybank收到转账'
                return ParsedNotification(
                    amount=amt,
                    type='income',
                    category='其他',
                    merchant=sender,
                    note=sender,
                    date=today_str,
                    group_name='side',
                    is_friend_repayment=True,
                    channel='Maybank',
                    confidence=0.95,
                    raw_text=text
                )

        # 2. 支出 / 转账付款
        m_pay = self.PAYMENT_PATTERN.search(text)
        if m_pay:
            try:
                amt = float(m_pay.group(1).replace(',', ''))
                raw_target = m_pay.group(2).strip()
                cleaned_target = re.split(
                    r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                    raw_target,
                    flags=re.IGNORECASE
                )[0].strip(' .,-')
                return ParsedNotification(
                    amount=amt,
                    type='expense',
                    category='其他',
                    merchant=cleaned_target or 'Maybank支出',
                    note=cleaned_target or 'Maybank支出',
                    date=today_str,
                    channel='Maybank',
                    confidence=0.95,
                    raw_text=text
                )
            except ValueError:
                pass

        return None
