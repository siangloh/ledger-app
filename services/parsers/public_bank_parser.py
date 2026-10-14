import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


# 马来西亚数字银行与主流电子钱包机构名称关键词（通常为对公清算机构户名）
WALLET_AND_DIGITAL_BANK_ENTITIES = [
    'gx bank', 'gxbank', 'gx bank berhad',
    'tng digital', 'tng digital sdn bhd', 'touchngo', 'touch n go', "touch 'n go", 'tng ewallet',
    'boost bank', 'boost bank berhad',
    'aeon bank', 'aeon bank (m) berhad',
    'grabpay', 'grab digital',
    'shopeepay', 'bigpay'
]


@register_parser
class PublicBankParser(NotificationParserStrategy):
    """大众银行 Public Bank (MyPB / PBB) 专属通知解析策略"""

    # 1. 转出 / 支付模式 (DuitNow OBW, DuitNow Transfer, DuitNow QR, 通用付款)
    SENT_PATTERNS = [
        # 例如: Money Sent PBB. You have made a DuitNow Online Banking/Wallets Payment of RM1,500.00 to GX BANK BERHAD.
        re.compile(
            r'money\s+sent\s+pbb[.\s]+you\s+have\s+made\s+a\s+(?:duitnow\s+online\s+banking/wallets\s+|duitnow\s+transfer\s+of\s+|duitnow\s+qr\s+|duitnow\s+|fund\s+transfer\s+of\s+)?(?:payment\s+of\s+)?(?:rm|myr)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+to\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_()]{2,50})',
            re.IGNORECASE
        ),
        # 兼容简易格式: PBB: You have made a payment of RM 50.00 to ...
        re.compile(
            r'(?:you\s+have\s+made\s+a\s+)?(?:payment|transfer)\s+of\s+(?:rm|myr)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+to\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_()]{2,50})',
            re.IGNORECASE
        ),
        # 银行卡消费: RM50.00 spent at ...
        re.compile(
            r'(?:rm|myr)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:spent at|paid to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_()]{2,50})',
            re.IGNORECASE
        )
    ]

    # 2. 转入 / 收款模式
    RECEIVED_PATTERN = re.compile(
        r'money\s+received\s+pbb[.\s]+you\s+have\s+received\s+(?:rm|myr)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+from\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_()]{2,50})',
        re.IGNORECASE
    )

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        return any(k in t for k in ['mypb', 'public bank', 'pbb', 'publicbank'])

    def parse(self, text: str) -> Optional[ParsedNotification]:
        today_str = date.today().isoformat()
        lower_text = text.lower()

        # 0. 排除授权/审批提醒通知 (非动账凭单)
        if any(k in lower_text for k in ['transaction to be approved', 'authorize this transaction', 'challenge question']):
            return None

        # 1. 检查转入 / 收款
        m_rec = self.RECEIVED_PATTERN.search(text)
        if m_rec:
            try:
                amt = float(m_rec.group(1).replace(',', ''))
                raw_sender = m_rec.group(2).strip()
                cleaned_sender = re.split(
                    r'[\.\n\r]|\s+(?:via|on|ref|using|with|at|date|txid)\b',
                    raw_sender,
                    flags=re.IGNORECASE
                )[0].strip(' .,-')
                return ParsedNotification(
                    amount=amt,
                    type='income',
                    category='其他',
                    merchant=cleaned_sender or 'Public Bank收到转账',
                    note=cleaned_sender or 'Public Bank收到转账',
                    date=today_str,
                    group_name='side',
                    is_friend_repayment=True,
                    channel='Public Bank',
                    confidence=0.96,
                    raw_text=text
                )
            except ValueError:
                pass

        # 2. 检查转出 / 付款
        for pattern in self.SENT_PATTERNS:
            m_sent = pattern.search(text)
            if m_sent:
                try:
                    amt = float(m_sent.group(1).replace(',', ''))
                    raw_target = m_sent.group(2).strip()
                    cleaned_target = re.split(
                        r'[\.\n\r]|\s+(?:via|on|ref|using|with|at|date|txid)\b',
                        raw_target,
                        flags=re.IGNORECASE
                    )[0].strip(' .,-')

                    cleaned_lower = cleaned_target.lower()

                    # 判定是否属于数字银行或电子钱包充值/自转
                    is_digital_bank_or_wallet = any(w in cleaned_lower for w in WALLET_AND_DIGITAL_BANK_ENTITIES)
                    is_obw_or_transfer = any(k in lower_text for k in [
                        'online banking/wallets', 'fund transfer', 'duitnow transfer'
                    ])

                    # 只要收款方是数字银行/电子钱包清算实体，触发 on_hold 待用户决定
                    if is_digital_bank_or_wallet or (is_obw_or_transfer and any(w in lower_text for w in WALLET_AND_DIGITAL_BANK_ENTITIES)):
                        reason_msg = f"检测到转账给数字银行/电子钱包 ({cleaned_target})，可能为个人储蓄自转或充值"
                        return ParsedNotification(
                            amount=amt,
                            type='expense',
                            category='转账/充值',
                            merchant=cleaned_target or '数字银行/钱包充值',
                            note=cleaned_target or '数字银行/钱包充值',
                            date=today_str,
                            is_on_hold=True,
                            on_hold_reason=reason_msg,
                            channel='Public Bank',
                            confidence=0.95,
                            raw_text=text
                        )

                    # 若为普通 QR 扫码支付或明确商户消费，直接正常记账
                    return ParsedNotification(
                        amount=amt,
                        type='expense',
                        category='其他',
                        merchant=cleaned_target or 'Public Bank消费',
                        note=cleaned_target or 'Public Bank消费',
                        date=today_str,
                        is_on_hold=False,
                        channel='Public Bank',
                        confidence=0.95,
                        raw_text=text
                    )
                except ValueError:
                    pass

        return None
