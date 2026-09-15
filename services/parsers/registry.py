import re
from datetime import date
from typing import List, Optional

from .base import NotificationParserStrategy, ParsedNotification

# 全局自注册策略列表
_PARSER_REGISTRY: List[NotificationParserStrategy] = []


def register_parser(cls):
    """自动注册策略类的装饰器（支持开箱即用扩展）"""
    instance = cls()
    _PARSER_REGISTRY.append(instance)
    return cls


# 前置过滤词库：非动账类通知（营销推广、信用卡推销、返现活动宣传、安全提醒等）
PROMO_AND_AD_KEYWORDS = [
    'apply online', 'apply & get', 'apply for', 'apply now', 'apply today', 'application for',
    'cardmember yet', 'credit cardmember', 'not a pb', 'not a member', 'eligible for',
    'double cashback', 'cash back', 'stand a chance', 'lucky draw', 'win a', 'win up to',
    'contest', 'rewards point', 'free gift', 'luggage set', 'gift voucher', 'earn entries',
    't&cs apply', "t&c's apply", 'terms and conditions apply', 'terms and conditions', 'spend requirements', 'campaign period',
    'exclusive offer', 'special offer', 'limited time offer', 'limited time only',
    'balance transfer', 'flexi payment', 'personal loan', 'home loan', 'car loan', 'hire purchase',
    'unit trust', 'fixed deposit promo', 'interest rate',
    'maintenance notice', 'system maintenance', 'system upgrade', 'scheduled downtime',
    'security reminder', 'stay alert', 'scam alert', 'fraud alert',
    'otp', 'tac', 'one-time password', 'verification code', 'authorization code', 'do not share',
    'your password', 'reset password', 'login alert', 'new login',
    'you just got a voucher', 'got a voucher', 'claim your voucher', 'claim voucher',
    'free shipping', '100% cashback', 'cashback, sehingga', 'sehingga rm',
    'check out in-store now', 'check out now', 'shop now', 'voucher inside'
]

# 内部划转与充值（不计入日常外部收支）
INTERNAL_TRANSFER_KEYWORDS = [
    'into your go+ account', 'into your go+', 'cashed in', 'cash in successful',
    'reload successful', 'top up successful', 'top up into', 'reload into',
    '转存进入', '转入余额宝', '钱包充值成功'
]


class NotificationParserComposite:
    """策略聚合器与协调上下文（Composite & Chain of Responsibility）"""

    def __init__(self, strategies: Optional[List[NotificationParserStrategy]] = None):
        self.strategies = strategies if strategies is not None else _PARSER_REGISTRY

    def parse(self, text: str, merchant_mapping=None, category_keywords=None) -> Optional[ParsedNotification]:
        if not text or not text.strip():
            return None

        raw_text = text.strip()
        lower_text = raw_text.lower()

        # 0. 强力前置过滤：非动账与营销广告
        if any(k in lower_text for k in PROMO_AND_AD_KEYWORDS):
            return ParsedNotification(
                amount=0.0,
                type='expense',
                category='广告过滤',
                merchant='营销推广',
                is_promo=True,
                note='命中营销推广活动或非动账安全词库',
                raw_text=raw_text
            )

        # 0.1 内部资金划转与充值
        if any(k in lower_text for k in INTERNAL_TRANSFER_KEYWORDS):
            return ParsedNotification(
                amount=0.0,
                type='expense',
                category='内部划转',
                merchant='内部划转',
                is_internal_transfer=True,
                note='钱包内部资金划转/充值（如 GO+ 转存），已自动忽略不记入财务收支',
                raw_text=raw_text
            )

        # 1. 遍历自注册策略责任链
        for strategy in self.strategies:
            if strategy.can_handle(raw_text):
                res = strategy.parse(raw_text)
                if res:
                    return self._enrich_category(res, raw_text, merchant_mapping, category_keywords)

        # 2. 策略未命中时的通用规则引擎回退解析
        fallback_res = self._parse_generic_fallback(raw_text)
        if fallback_res:
            return self._enrich_category(fallback_res, raw_text, merchant_mapping, category_keywords)

        return None

    def _parse_generic_fallback(self, text: str) -> Optional[ParsedNotification]:
        """通用兜底规则解析"""
        lower_text = text.lower()

        is_expense = any(k in lower_text for k in [
            'paid', 'spent', 'payment to', 'payment of', 'payment successful', 'payment has been made',
            'deducted', 'debited', 'charged', 'transfer to', 'transferred to', 'transfer of',
            'purchase at', 'purchase of', 'withdrawal', 'withdrawn', 'duitnow qr', 'duitnow transfer to',
            '付款', '支出', '扣款', '转账给', '已支付', '买单', '消费', '成功支付', '成功转账', '成功扣款'
        ])
        is_refund = any(k in lower_text for k in ['payment refunded', 'refunded', 'refund of', 'refund', '退款', '撤销', '退回'])
        is_income = is_refund or any(k in lower_text for k in [
            'received from', 'received', 'credited', 'deposit', 'salary', 'dividend',
            'duitnow transfer from', 'transfer from',
            '转入', '收款', '存入', '到账', '收到转账', '入账'
        ])

        if not is_expense and not is_income:
            return None

        tx_type = 'income' if is_income and not is_expense else 'expense'

        # 提取金额
        amount = None
        m_rm = re.search(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
        if m_rm:
            try:
                amount = float(m_rm.group(1).replace(',', ''))
            except ValueError:
                pass

        if amount is None:
            nums = list(re.finditer(r'\b([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\b', text))
            if nums:
                try:
                    amount = float(nums[-1].group(1).replace(',', ''))
                except ValueError:
                    pass

        if not amount or amount <= 0:
            return None

        # 提取商户
        merchant = ''
        m_to = re.search(r'(?:to|at|from|for|paid to|transfer to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,35})', text, re.IGNORECASE)
        if m_to:
            m_str = m_to.group(1).strip()
            m_cleaned = re.split(r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid|claim|get|earn|earned|cashback|voucher|was|is|successful)\b', m_str, flags=re.IGNORECASE)[0]
            merchant = m_cleaned.strip(' .,-')

        if not merchant:
            m_cn = re.search(r'(?:在|向)\s*([A-Za-z0-9\u4e00-\u9fa5\s&]{2,20})\s*(?:消费|转账|付款)', text)
            if m_cn:
                merchant = m_cn.group(1).strip()

        if not merchant:
            merchant = '自动追踪消费' if tx_type == 'expense' else '自动追踪入账'

        # 提取日期
        tx_date = date.today().isoformat()
        m_date = re.search(r'(\d{4}[-/.]\d{1,2}[-/.]\d{1,2})', text)
        if m_date:
            try:
                d_str = m_date.group(1).replace('/', '-').replace('.', '-')
                parts = d_str.split('-')
                tx_date = f'{int(parts[0]):04d}-{int(parts[1]):02d}-{int(parts[2]):02d}'
            except Exception:
                pass

        return ParsedNotification(
            amount=amount,
            type=tx_type,
            category='其他',
            merchant=merchant,
            note=merchant,
            date=tx_date,
            is_refund=is_refund,
            group_name='side' if tx_type == 'income' else None,
            channel='generic',
            confidence=0.80,
            raw_text=text
        )

    def _enrich_category(self, parsed: ParsedNotification, text: str, merchant_mapping=None, category_keywords=None) -> ParsedNotification:
        """基于商户词库与关键词二次强化分类预测"""
        if parsed.category != '其他' and parsed.category:
            return parsed

        matched_cat = None
        m_lower = parsed.merchant.lower()
        t_lower = text.lower()

        if merchant_mapping:
            for kw, cat in merchant_mapping.items():
                if kw in m_lower or kw in t_lower:
                    matched_cat = cat
                    break

        if not matched_cat and category_keywords and parsed.type == 'expense':
            for cat, kws in category_keywords.items():
                if any(k in m_lower or k in t_lower for k in kws):
                    matched_cat = cat
                    break

        if matched_cat:
            return ParsedNotification(
                amount=parsed.amount,
                type=parsed.type,
                category=matched_cat,
                merchant=parsed.merchant,
                date=parsed.date,
                group_name=parsed.group_name,
                note=parsed.note,
                is_refund=parsed.is_refund,
                is_friend_repayment=parsed.is_friend_repayment,
                is_internal_transfer=parsed.is_internal_transfer,
                is_promo=parsed.is_promo,
                channel=parsed.channel,
                confidence=parsed.confidence,
                raw_text=parsed.raw_text,
                extra=parsed.extra
            )
        return parsed
