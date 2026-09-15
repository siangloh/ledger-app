import re
from datetime import date
from typing import Optional
from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser


@register_parser
class BankCardParser(NotificationParserStrategy):
    """马来西亚主要商业银行借记卡/信用卡消费短信及 DuitNow QR 解析策略"""

    BANK_KEYWORDS = [
        'public bank', 'mypb', 'cimb', 'hong leong', 'hlb', 'rhb', 'ambank',
        'uob', 'hsbc', 'ocbc', 'standard chartered', 'affin', 'bank islam', 'duitnow'
    ]

    SPEND_PATTERNS = [
        re.compile(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:spent at|charged at|purchase at|deducted for|paid to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})', re.IGNORECASE),
        re.compile(r'(?:spent|charged|purchase of)\s+(?:RM|MYR)?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+(?:at|to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,40})', re.IGNORECASE),
    ]

    def can_handle(self, text: str) -> bool:
        t = text.lower()
        return any(k in t for k in self.BANK_KEYWORDS) or 'card ending' in t or 'cardmember' in t

    def parse(self, text: str) -> Optional[ParsedNotification]:
        today_str = date.today().isoformat()
        lower_text = text.lower()

        # 1. 尝试匹配各种消费模式
        for pattern in self.SPEND_PATTERNS:
            m = pattern.search(text)
            if m:
                try:
                    amt = float(m.group(1).replace(',', ''))
                    raw_merchant = m.group(2).strip()
                    cleaned_merchant = re.split(
                        r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid)\b',
                        raw_merchant,
                        flags=re.IGNORECASE
                    )[0].strip(' .,-')

                    channel_name = 'Bank'
                    for kw in self.BANK_KEYWORDS:
                        if kw in lower_text:
                            channel_name = kw.upper()
                            break

                    return ParsedNotification(
                        amount=amt,
                        type='expense',
                        category='其他',
                        merchant=cleaned_merchant or '银行卡消费',
                        note=cleaned_merchant or '银行卡消费',
                        date=today_str,
                        channel=channel_name,
                        confidence=0.92,
                        raw_text=text
                    )
                except ValueError:
                    pass

        return None
