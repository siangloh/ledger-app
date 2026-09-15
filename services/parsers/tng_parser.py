import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class TouchNGoParser(NotificationParserStrategy):
    """Touch'n Go eWallet 专属解析策略"""

    PAYMENT_PATTERN = re.compile(
        r'(?:paid|payment of|spent)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:to|at)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})',
        re.IGNORECASE
    )
    REFUND_PATTERN = re.compile(
        r'(?:refund of|refunded|payment refunded)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:from|for)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{0,40})',
        re.IGNORECASE
    )
    TRANSFER_IN_PATTERN = re.compile(
        r'(?:received|transfer from|duitnow transfer from)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:from)?\s*([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})?',
        re.IGNORECASE
    )

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        return any(k in t for k in ['touchngo', 'tng', "touch 'n go", 'ewallet', 'tngd', 'touch n go'])

    def parse(self, text: str) -> Optional[ParsedNotification]:
        lower_text = text.lower()
        today_str = date.today().isoformat()

        # 1. 检查加油或预授权退款
        m_ref = self.REFUND_PATTERN.search(text)
        if m_ref or any(k in lower_text for k in ['refund', '退款', '退回']):
            amt = None
            if m_ref:
                try:
                    amt = float(m_ref.group(1).replace(',', ''))
                except ValueError:
                    pass
            if not amt:
                m_amt = re.search(r'(?:RM|MYR)\s*([0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
                if m_amt:
                    amt = float(m_amt.group(1))

            if amt and amt > 0:
                merchant = (m_ref.group(2).strip() if m_ref and m_ref.group(2) else '') or 'TnG预授权/加油退款'
                return ParsedNotification(
                    amount=amt,
                    type='income',
                    category='退款',
                    merchant=merchant,
                    note=merchant,
                    date=today_str,
                    is_refund=True,
                    channel='TnG',
                    confidence=0.98,
                    raw_text=text
                )

        # 2. 检查收款 / 朋友转账还款
        m_in = self.TRANSFER_IN_PATTERN.search(text)
        if m_in or any(k in lower_text for k in ['received from', 'transfer from', '收到转账', '转入']):
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
                sender = (m_in.group(2).strip() if m_in and m_in.group(2) else '') or '朋友转账'
                return ParsedNotification(
                    amount=amt,
                    type='income',
                    category='其他',
                    merchant=sender,
                    note=sender,
                    date=today_str,
                    group_name='side',
                    is_friend_repayment=True,
                    channel='TnG',
                    confidence=0.95,
                    raw_text=text
                )

        # 3. 正常消费扣款
        m_pay = self.PAYMENT_PATTERN.search(text)
        if m_pay:
            try:
                amt = float(m_pay.group(1).replace(',', ''))
                raw_merchant = m_pay.group(2).strip()
                cleaned_merchant = re.split(
                    r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                    raw_merchant,
                    flags=re.IGNORECASE
                )[0].strip(' .,-')
                return ParsedNotification(
                    amount=amt,
                    type='expense',
                    category='其他',  # 后续由映射层二次强化
                    merchant=cleaned_merchant or 'TnG消费',
                    note=cleaned_merchant or 'TnG消费',
                    date=today_str,
                    channel='TnG',
                    confidence=0.95,
                    raw_text=text
                )
            except ValueError:
                pass

        return None
