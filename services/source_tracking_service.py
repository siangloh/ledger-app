"""
services/source_tracking_service.py
交易记录来源（Record Source）与溯源追踪（Provenance Tracking）核心服务层。

严格区分三个核心财务概念：
1. 消费分类 (Category)：餐饮、交通、购物、医疗等。
2. 资金账户 (Account)：Maybank 储蓄账户、TNG 钱包、现金、信用卡等。
3. 记录来源 (Record Source)：手动记账、手机通知/短信、CSV/Excel 导入、API 接入等。

通知来自哪个 App，不代表实际扣款账户，绝不与资金账户混淆绑定。
来源属于服务端可校验元数据，编辑交易（修改分类/备注/金额）不会改变原始来源。
"""

import json
import logging
import uuid
from datetime import datetime, date
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# 标准规范来源枚举
SOURCE_MANUAL = 'manual'                  # 手动输入 (Web快速记账、明细新增、伴侣端手动记账)
SOURCE_NOTIFICATION = 'notification'      # 手机通知/短信自动追踪 (原 auto_track / auto_track_confirmed 均收敛于此)
SOURCE_IMPORT = 'import'                  # CSV / Excel 批量文件导入
SOURCE_API = 'api'                        # 外部接入 / 自动化脚本 / 开放接口写入
SOURCE_NLP = 'nlp'                        # 自然语言智能解析输入
SOURCE_RECURRING = 'recurring'            # 固定收支计划自动/手动生成
SOURCE_SPLIT_BILL = 'split_bill'          # AA 分账确认记账
SOURCE_RECONCILIATION = 'reconciliation'  # 账户对账与余额校准生成的平账记录
SOURCE_UNKNOWN = 'unknown'                # 未知/老旧历史记录（无法可靠回溯时不妄测）

VALID_SOURCES = {
    SOURCE_MANUAL,
    SOURCE_NOTIFICATION,
    SOURCE_IMPORT,
    SOURCE_API,
    SOURCE_NLP,
    SOURCE_RECURRING,
    SOURCE_SPLIT_BILL,
    SOURCE_RECONCILIATION,
    SOURCE_UNKNOWN
}

# 已实际接入的通知渠道定义（按实际接入方式定义，不列未支持的渠道）
CHANNEL_DEFINITIONS = {
    'maybank': {
        'id': 'maybank',
        'name': 'Maybank MAE',
        'icon': 'fa-building-columns',
        'color': '#f59e0b',
        'short_label': 'Maybank'
    },
    'tng': {
        'id': 'tng',
        'name': "Touch 'n Go eWallet",
        'icon': 'fa-wallet',
        'color': '#0284c7',
        'short_label': 'TNG'
    },
    'grab': {
        'id': 'grab',
        'name': 'GrabPay',
        'icon': 'fa-car-side',
        'color': '#10b981',
        'short_label': 'Grab'
    },
    'public_bank': {
        'id': 'public_bank',
        'name': 'Public Bank (MyPB)',
        'icon': 'fa-landmark',
        'color': '#ef4444',
        'short_label': 'Public Bank'
    },
    'shopee': {
        'id': 'shopee',
        'name': 'ShopeePay',
        'icon': 'fa-bag-shopping',
        'color': '#f97316',
        'short_label': 'Shopee'
    },
    'bank_card': {
        'id': 'bank_card',
        'name': '银行卡动账短信',
        'icon': 'fa-credit-card',
        'color': '#6366f1',
        'short_label': '卡短信'
    },
    'generic': {
        'id': 'generic',
        'name': '手机通知/短信',
        'icon': 'fa-bell',
        'color': '#64748b',
        'short_label': '通知'
    }
}


def normalize_source(raw_source: Optional[str]) -> str:
    """
    统一来源标准化。
    - 历史数据中 auto_track 或 auto_track_confirmed 归纳为 notification
    - 空值、None 或未识别的值归为 unknown，杜绝将历史未标记记录主观臆测为 manual
    """
    if not raw_source or not str(raw_source).strip():
        return SOURCE_UNKNOWN
    s = str(raw_source).strip().lower()
    if s in ('auto_track', 'auto_track_confirmed', 'notification'):
        return SOURCE_NOTIFICATION
    if s in ('unknown', 'legacy', 'none', 'null'):
        return SOURCE_UNKNOWN
    if s in VALID_SOURCES:
        return s
    return SOURCE_UNKNOWN


def format_source_display(
    source: Optional[str],
    channel: Optional[str] = None,
    lang: str = 'zh'
) -> Dict[str, Any]:
    """
    计算并在界面中呈现低调、清晰的记录来源元数据胶囊。
    包含来源分类、渠道图标、渠道短标签与无障碍说明。
    """
    from core.i18n import t

    norm_source = normalize_source(source)
    chan_id = (channel or '').strip().lower()
    chan_info = CHANNEL_DEFINITIONS.get(chan_id)

    # 1. 基础来源标签与图标
    labels = {
        SOURCE_MANUAL: (t('records.src_manual', '手动记录', lang=lang), '✍️', 'fa-pen-to-square', '#64748b'),
        SOURCE_NOTIFICATION: (t('records.src_notification', '手机通知', lang=lang), '📱', 'fa-bell', '#0f766e'),
        SOURCE_IMPORT: (t('records.src_import', '文件导入', lang=lang), '📁', 'fa-file-import', '#d97706'),
        SOURCE_API: (t('records.src_api', 'API 接入', lang=lang), '⚙️', 'fa-code', '#4f46e5'),
        SOURCE_NLP: (t('records.src_nlp', '智能记账', lang=lang), '🤖', 'fa-wand-magic-sparkles', '#8b5cf6'),
        SOURCE_RECURRING: (t('records.src_recurring', '固定收支', lang=lang), '🔁', 'fa-repeat', '#0284c7'),
        SOURCE_SPLIT_BILL: (t('records.src_split_bill', 'AA分账', lang=lang), '👥', 'fa-user-group', '#ec4899'),
        SOURCE_RECONCILIATION: (t('records.src_reconciliation', '对账平账', lang=lang), '⚖️', 'fa-scale-balanced', '#059669'),
        SOURCE_UNKNOWN: (t('records.src_unknown', '未知/历史记录', lang=lang), '❓', 'fa-clock-rotate-left', '#94a3b8'),
    }

    base_label, emoji, fa_icon, color = labels.get(norm_source, labels[SOURCE_UNKNOWN])

    # 2. 如果是手机通知且拥有明确渠道，提取渠道专属短标签
    if norm_source == SOURCE_NOTIFICATION and chan_info:
        badge_text = chan_info['short_label']
        badge_icon = chan_info['icon']
        badge_color = chan_info['color']
        full_label = f"{t('records.src_notification', '手机通知', lang=lang)} · {chan_info['name']}"
    else:
        badge_text = base_label
        badge_icon = fa_icon
        badge_color = color
        full_label = base_label

    return {
        'source': norm_source,
        'source_label': base_label,
        'channel': chan_id if chan_info else None,
        'channel_label': chan_info['name'] if chan_info else None,
        'badge_text': badge_text,
        'badge_icon': badge_icon,
        'badge_emoji': emoji,
        'badge_color': badge_color,
        'full_label': full_label,
        'is_notification': norm_source == SOURCE_NOTIFICATION,
        'is_import': norm_source == SOURCE_IMPORT,
        'is_manual': norm_source == SOURCE_MANUAL
    }


def record_import_batch(
    user_id: Any,
    filename: str,
    row_count: int = 0,
    file_type: Optional[str] = None,
    db: Any = None
) -> str:
    """
    在数据库创建一次导入批次记录，返回唯一的 batch_id。
    遵守用户隔离，供后续导入交易关联，确保可追溯性。
    """
    if not db or not user_id:
        raise ValueError("Missing database connection or user_id")

    clean_filename = (filename or 'statement.csv').strip()
    # 仅保留纯文件名，剥离任何路径前缀
    if '/' in clean_filename:
        clean_filename = clean_filename.split('/')[-1]
    if '\\' in clean_filename:
        clean_filename = clean_filename.split('\\')[-1]

    batch_id = f"imp_{date.today().strftime('%Y%m%d')}_{uuid.uuid4().hex[:8]}"
    now_iso = datetime.now().isoformat()

    db.execute('''
        INSERT INTO import_batches (id, user_id, filename, file_type, row_count, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
    ''', (batch_id, str(user_id), clean_filename, file_type or 'csv', int(row_count), now_iso))
    db.commit()

    return batch_id


def get_import_batch_info(user_id: Any, batch_id: str, db: Any = None) -> Optional[Dict[str, Any]]:
    """查询指定导入批次信息，严格校验归属"""
    if not db or not user_id or not batch_id:
        return None
    row = db.execute(
        "SELECT id, user_id, filename, file_type, row_count, created_at FROM import_batches WHERE id = ? AND user_id = ?",
        (str(batch_id), str(user_id))
    ).fetchone()
    return dict(row) if row else None


def get_transaction_source_details(user_id: Any, tx_id: int, db: Any = None) -> Optional[Dict[str, Any]]:
    """
    获取某笔交易完整的来源追溯证据详情：
    - 原始来源与通知渠道
    - 关联导入批次文件（若为导入）
    - 来源参考哈希（不暴露敏感密码与私钥）
    - 关联证据（若包含合并记录）
    - 资金账户当前映射（清晰区分资金账户与通知 App）
    - 最后修改时间与方式（保证与原始来源分离）
    """
    if not db or not user_id or not tx_id:
        return None

    row = db.execute('''
        SELECT t.id, t.user_id, t.date, t.type, t.category, t.amount, t.note, t.tags,
               t.source, t.channel, t.import_batch_id, t.source_ref, t.created_at,
               t.updated_at, t.last_edit_source, t.merged_info, t.account_id,
               a.name AS account_name, a.type AS account_type
        FROM transactions t
        LEFT JOIN accounts a ON t.account_id = a.id AND a.user_id = t.user_id
        WHERE t.id = ? AND t.user_id = ?
    ''', (tx_id, str(user_id))).fetchone()

    if not row:
        return None

    tx_dict = dict(row)
    source_disp = format_source_display(tx_dict.get('source'), tx_dict.get('channel'))

    # 解析合并来源证据
    merged_evidence = []
    if tx_dict.get('merged_info'):
        try:
            m_data = json.loads(tx_dict['merged_info'])
            if isinstance(m_data, list):
                for it in m_data:
                    chan = it.get('channel')
                    chan_info = CHANNEL_DEFINITIONS.get(chan, {})
                    merged_evidence.append({
                        'merged_at': it.get('merged_at'),
                        'pending_id': it.get('pending_id'),
                        'channel': chan,
                        'channel_name': chan_info.get('name', chan or '手机通知'),
                        'amount': it.get('amount'),
                        'note': it.get('note') or '',
                        'reason': it.get('reason') or '疑似重复合并入账'
                    })
        except Exception as e:
            logger.debug("Failed to parse merged_info for tx %s: %s", tx_id, e)

    # 导入批次信息
    batch_info = None
    if tx_dict.get('import_batch_id'):
        batch_info = get_import_batch_info(user_id, tx_dict['import_batch_id'], db=db)

    return {
        'id': tx_dict['id'],
        'amount': float(tx_dict['amount'] or 0.0),
        'type': tx_dict['type'],
        'category': tx_dict['category'],
        'note': tx_dict['note'] or '',
        'date': tx_dict['date'],
        'account_id': tx_dict['account_id'],
        'account_name': tx_dict['account_name'] or '未指定账户',
        'account_type': tx_dict['account_type'] or '',
        'source': source_disp['source'],
        'source_label': source_disp['source_label'],
        'source_icon': source_disp['badge_icon'],
        'source_emoji': source_disp['badge_emoji'],
        'channel': source_disp['channel'],
        'channel_label': source_disp['channel_label'],
        'full_label': source_disp['full_label'],
        'import_batch_id': tx_dict.get('import_batch_id'),
        'import_batch': batch_info,
        'source_ref': tx_dict.get('source_ref'),
        'created_at': tx_dict['created_at'],
        'updated_at': tx_dict.get('updated_at'),
        'last_edit_source': tx_dict.get('last_edit_source'),
        'is_edited': bool(tx_dict.get('updated_at')),
        'has_merged_evidence': len(merged_evidence) > 0,
        'merged_evidence': merged_evidence
    }


def get_supported_sources_for_filter(lang: str = 'zh') -> List[Dict[str, str]]:
    """返回供用户在 Web 与原生伴侣端筛选的实际接入来源列表"""
    from core.i18n import t
    return [
        {'id': 'all', 'label': t('common.all', '全部来源', lang=lang), 'icon': 'fa-asterisk'},
        {'id': SOURCE_MANUAL, 'label': t('records.src_manual', '手动记录', lang=lang), 'icon': 'fa-pen-to-square'},
        {'id': SOURCE_NOTIFICATION, 'label': t('records.src_notification', '手机通知', lang=lang), 'icon': 'fa-bell'},
        {'id': SOURCE_IMPORT, 'label': t('records.src_import', '文件导入', lang=lang), 'icon': 'fa-file-import'},
        {'id': SOURCE_API, 'label': t('records.src_api', 'API 接入', lang=lang), 'icon': 'fa-code'},
        {'id': SOURCE_NLP, 'label': t('records.src_nlp', '智能记账', lang=lang), 'icon': 'fa-wand-magic-sparkles'},
        {'id': SOURCE_RECURRING, 'label': t('records.src_recurring', '固定收支', lang=lang), 'icon': 'fa-repeat'},
        {'id': SOURCE_SPLIT_BILL, 'label': t('records.src_split_bill', 'AA分账', lang=lang), 'icon': 'fa-user-group'},
        {'id': SOURCE_RECONCILIATION, 'label': t('records.src_reconciliation', '对账平账', lang=lang), 'icon': 'fa-scale-balanced'},
        {'id': SOURCE_UNKNOWN, 'label': t('records.src_unknown', '未知/历史记录', lang=lang), 'icon': 'fa-clock-rotate-left'},
    ]


def get_supported_channels_for_filter() -> List[Dict[str, str]]:
    """返回供用户进一步二级筛选的实际通知渠道"""
    channels = [{'id': 'all', 'label': '全部渠道', 'short_label': '全部', 'icon': 'fa-asterisk'}]
    for _, defn in CHANNEL_DEFINITIONS.items():
        channels.append({
            'id': defn['id'],
            'label': defn['name'],
            'short_label': defn['short_label'],
            'icon': defn['icon']
        })
    return channels
