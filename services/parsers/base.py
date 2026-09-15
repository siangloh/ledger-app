from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import date
from typing import Optional, Dict, Any


@dataclass(frozen=True)
class ParsedNotification:
    """通知解析结果的领域值对象 (Value Object)"""
    amount: float
    type: str  # 'expense' / 'income'
    category: str
    merchant: str
    date: str = field(default_factory=lambda: date.today().isoformat())
    group_name: Optional[str] = None
    note: str = ""
    is_refund: bool = False
    is_friend_repayment: bool = False
    is_internal_transfer: bool = False
    is_promo: bool = False
    channel: str = "generic"
    confidence: float = 1.0
    raw_text: str = ""
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """向后兼容原有字典格式"""
        if self.is_promo:
            return {'is_promo': True, 'reason': self.note or '命中营销推广活动或非动账安全词库'}
        if self.is_internal_transfer:
            return {'is_internal_transfer': True, 'reason': self.note or '钱包内部资金划转/充值'}

        return {
            'date': self.date,
            'type': self.type,
            'group_name': self.group_name,
            'category': self.category,
            'amount': self.amount,
            'note': self.note or self.merchant,
            'is_refund': self.is_refund,
            'is_friend_repayment': self.is_friend_repayment,
            'channel': self.channel,
            'confidence': self.confidence,
            'raw_text': self.raw_text
        }


class NotificationParserStrategy(ABC):
    """通知解析器策略抽象基类（遵守开闭原则 OCP）"""

    @abstractmethod
    def can_handle(self, text: str) -> bool:
        """快速判断此通知是否属于该渠道"""
        pass

    @abstractmethod
    def parse(self, text: str) -> Optional[ParsedNotification]:
        """执行正则或语法解析，成功返回 ParsedNotification，不匹配返回 None"""
        pass
