from .base import NotificationParserStrategy, ParsedNotification
from .registry import register_parser, NotificationParserComposite, _PARSER_REGISTRY

# 导入所有策略类以触发 @register_parser 自动注册
from . import tng_parser  # noqa: F401
from . import maybank_parser  # noqa: F401
from . import grab_parser  # noqa: F401
from . import bank_card_parser  # noqa: F401

__all__ = [
    'NotificationParserStrategy',
    'ParsedNotification',
    'register_parser',
    'NotificationParserComposite',
    '_PARSER_REGISTRY'
]
