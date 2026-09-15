import re
from datetime import date

INCOME_MAIN_KEYWORDS = ['工资', '薪资', '发薪', '奖金', '年终奖', '绩效']
INCOME_SIDE_KEYWORDS = ['副业', '自由职业', '兼职', '稿费', '私活', '投资', '理财', '分红', '利息', '外快']

INCOME_CATEGORY_KEYWORDS = {
    '工资': ['工资', '薪资', '发薪'],
    '奖金': ['奖金', '年终奖', '绩效'],
    '自由职业': ['自由职业', '稿费', '私活', '写作', '设计费'],
    '兼职': ['兼职', '外快'],
    '投资': ['投资', '理财', '分红', '利息'],
}

EXPENSE_CATEGORY_KEYWORDS = {
    '餐饮': [
        '吃', '饭', '餐', '外卖', '奶茶', '咖啡', '早饭', '午饭', '晚饭', '夜宵', '零食',
        'kfc', 'mcd', 'mcdonald', 'starbucks', 'zus', 'chagee', 'tealive', 'subway',
        'familymart', 'family mart', 'rotiboy', 'baker', 'kopitiam', 'restaurant',
        'nasi', 'cafe', 'food', 'din', 'bbq', 'sushi', 'pizza'
    ],
    '交通': [
        '打车', '地铁', '公交', '高铁', '火车', '机票', '油费', '停车', '交通', '出行',
        'petronas', 'shell', 'caltex', 'bhp', 'petron', 'grab', 'touch n go', 'tng rfid',
        'parking', 'tng reload', 'toll', 'rapidkl', 'mrt', 'lrt', 'airasia'
    ],
    '房租': ['房租', '租金', '物业费', 'rental', 'maintenance fee'],
    '购物': [
        '购物', '淘宝', '京东', '衣服', '超市', 'shopee', 'lazada', 'watsons', 'guardian',
        'uniqlo', 'lotus', 'aeon', 'jaya grocer', 'village grocer', 'mr diy', 'econsave',
        '99 speedmart', 'speedmart', 'donki', 'supermarket', 'mall'
    ],
    '娱乐': ['电影', '游戏', '娱乐', '唱歌', '旅游', '景点', 'steam', 'netflix', 'spotify', 'cinema', 'gsc', 'tgv'],
    '医疗': ['医院', '看病', '医疗', '体检', '药', 'clinic', 'hospital', 'pharmacy', 'dental'],
    '通讯': ['话费', '流量', '网费', '通讯', 'maxis', 'digi', 'celcom', 'umobile', 'unifi', 'tnb', 'air selangor'],
}

MERCHANT_CATEGORY_MAPPING = {
    # 交通加油
    'petronas': '交通', 'shell': '交通', 'caltex': '交通', 'bhp': '交通', 'petron': '交通',
    'ron95': '交通', 'ron97': '交通', 'petrol': '交通', 'fuel': '交通', 'diesel': '交通',
    'grab': '交通', 'touch n go': '交通', 'parking': '交通', 'toll': '交通', 'rapidkl': '交通',
    # 餐饮
    'familymart': '餐饮', 'family mart': '餐饮', 'kfc': '餐饮', 'mcdonald': '餐饮', 'mcd': '餐饮',
    'starbucks': '餐饮', 'zus': '餐饮', 'chagee': '餐饮', 'tealive': '餐饮', 'subway': '餐饮',
    'foodpanda': '餐饮', 'grabfood': '餐饮', 'kopitiam': '餐饮', 'restaurant': '餐饮', 'cafe': '餐饮',
    # 购物超市
    '99 speedmart': '购物', 'speedmart': '购物', 'lotus': '购物', 'aeon': '购物', 'watsons': '购物',
    'guardian': '购物', 'mr diy': '购物', 'shopee': '购物', 'lazada': '购物', 'jaya grocer': '购物',
    'village grocer': '购物', 'econsave': '购物', 'donki': '购物',
    # 水电通讯
    'tnb': '通讯', 'unifi': '通讯', 'maxis': '通讯', 'celcom': '通讯', 'digi': '通讯', 'umobile': '通讯'
}


from services.parsers import NotificationParserComposite

_default_composite = NotificationParserComposite()


def parse_auto_track_notification(raw_text):
    """
    解析来自 TnG eWallet / Maybank MAE / Public Bank (MyPB) / 银行短信 / 通知栏的文本。
    通过 NotificationParserComposite 策略责任链模式进行模块化调度与解耦。
    """
    if not raw_text or not str(raw_text).strip():
        return None
    parsed = _default_composite.parse(
        str(raw_text),
        merchant_mapping=MERCHANT_CATEGORY_MAPPING,
        category_keywords=EXPENSE_CATEGORY_KEYWORDS
    )
    return parsed.to_dict() if parsed else None


def parse_nlp_text(text):
    """从一句自然语言文本中解析出金额/类型/分组/分类，仅返回草稿，不直接入库。"""
    text = text.strip()
    warnings = []

    amount_matches = list(re.finditer(r'\d+(\.\d+)?', text))
    if not amount_matches:
        return None, ['未能识别出金额，请手动填写']
    m = amount_matches[-1]
    amount = float(m.group())
    remainder = (text[:m.start()] + text[m.end():]).strip()

    is_income = any(k in text for k in INCOME_MAIN_KEYWORDS + INCOME_SIDE_KEYWORDS)
    tx_type = 'income' if is_income else 'expense'

    group_name = None
    category = None

    if tx_type == 'income':
        group_name = 'side' if any(k in text for k in INCOME_SIDE_KEYWORDS) else 'main'
        for cat, kws in INCOME_CATEGORY_KEYWORDS.items():
            if any(k in text for k in kws):
                category = cat
                break
        if category is None:
            category = '工资' if group_name == 'main' else '自由职业'
            warnings.append('未能精确匹配收入子分类，已使用默认分类，请检查')
    else:
        for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
            if any(k in text for k in kws):
                category = cat
                break
        if category is None:
            category = '其他'
            warnings.append('未能匹配支出分类，已归为"其他"，请检查')

    if not remainder:
        remainder = text

    return {
        'date': date.today().isoformat(),
        'type': tx_type,
        'group_name': group_name,
        'category': category,
        'amount': amount,
        'note': remainder,
    }, warnings
