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


def parse_auto_track_notification(raw_text):
    """
    解析来自 TnG eWallet / Maybank MAE / Public Bank (MyPB) / 银行短信 / 通知栏的文本。
    提取：金额 (RM)、商户名/接收方、时间、自动匹配分类。
    自动过滤：营销广告、信用卡/贷款推广、返现活动宣传、安全提醒、OTP/TAC验证码等非动账通知。
    """
    text = raw_text.strip()
    if not text:
        return None

    lower_text = text.lower()

    # 0. 强力过滤非动账类通知（营销推广、信用卡/贷款推销、返现活动宣传、抽奖、条款、OTP/TAC验证码、安全提醒等）
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
        # 电商优惠券与营销促销推送（如 Shopee / Lazada 等）
        'you just got a voucher', 'got a voucher', 'claim your voucher', 'claim voucher',
        'free shipping', '100% cashback', 'cashback, sehingga', 'sehingga rm',
        'check out in-store now', 'check out now', 'shop now', 'voucher inside'
    ]
    if any(k in lower_text for k in PROMO_AND_AD_KEYWORDS):
        return {'is_promo': True, 'reason': '命中营销推广活动或非动账安全词库'}

    # 0.1 识别并忽略钱包内部资金划转与充值（例如 TnG GO+ 自动收益转存、余额转入理财、电子钱包充值）
    INTERNAL_TRANSFER_KEYWORDS = [
        'into your go+ account', 'into your go+', 'cashed in', 'cash in successful',
        'reload successful', 'top up successful', 'top up into', 'reload into',
        '转存进入', '转入余额宝', '钱包充值成功'
    ]
    if any(k in lower_text for k in INTERNAL_TRANSFER_KEYWORDS):
        return {
            'is_internal_transfer': True,
            'reason': '钱包内部资金划转/充值（如 GO+ 转存），已自动忽略不记入财务收支'
        }

    # 1. 动账行为动词硬性检查（必须具备明确真实的财务收支动作，杜绝普通资讯/广告被误记账）
    is_expense = any(k in lower_text for k in [
        'paid', 'spent', 'payment to', 'payment of', 'payment successful', 'payment has been made',
        'deducted', 'debited', 'charged', 'transfer to', 'transferred to', 'transfer of',
        'purchase at', 'purchase of', 'withdrawal', 'withdrawn', 'duitnow qr', 'duitnow transfer to',
        '付款', '支出', '扣款', '转账给', '已支付', '买单', '消费', '成功支付', '成功转账', '成功扣款'
    ])

    is_refund = any(k in lower_text for k in [
        'payment refunded', 'refunded', 'refund of', 'refund', '退款', '撤销', '退回'
    ])

    is_income = is_refund or any(k in lower_text for k in [
        'received from', 'received', 'credited', 'deposit', 'salary', 'dividend',
        'duitnow transfer from', 'transfer from',
        '转入', '收款', '存入', '到账', '收到转账', '入账'
    ])

    # 若既不是明确的支出动词，也不是明确的收入动词，直接判定为非交易动账通知并忽略
    if not is_expense and not is_income:
        return None

    tx_type = 'income' if is_income and not is_expense else 'expense'

    # 2. 提取金额：支持 "RM 15.00", "RM15.50", "RM 1,250.00", "MYR 20", "15.00"
    amount = None
    # 优先匹配带 RM / MYR 的格式 (允许千分位逗号)
    m_rm = re.search(r'(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)', text, re.IGNORECASE)
    if m_rm:
        try:
            val_str = m_rm.group(1).replace(',', '')
            amount = float(val_str)
        except ValueError:
            amount = None

    if amount is None:
        # 回退提取普通数字（允许千分位）
        nums = list(re.finditer(r'\b([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\b', text))
        if nums:
            try:
                val_str = nums[-1].group(1).replace(',', '')
                amount = float(val_str)
            except ValueError:
                pass

    if not amount or amount <= 0:
        return None

    # 3. 提取商户 / 交易对手 / 项目（支持 for RON95 / to FamilyMart 等）
    merchant = ''
    m_to = re.search(r'(?:to|at|from|for|paid to|transfer to|payment to)\s+([A-Za-z0-9\u4e00-\u9fa5\s&\'\.\-_]{2,35})', text, re.IGNORECASE)
    if m_to:
        m_str = m_to.group(1).strip()
        # 清理后续干扰词如 on, via, using, ref, date, claim, cashback, voucher 等以及句号/换行
        m_cleaned = re.split(r'[\.\n\r]|\s+(?:on|via|ref|using|with|at|date|txid|claim|get|earn|earned|cashback|voucher|was|is|successful)\b', m_str, flags=re.IGNORECASE)[0]
        merchant = m_cleaned.strip(' .,-')

    if not merchant:
        # 尝试中文格式：“在【全家】消费”、“向【张三】转账”
        m_cn = re.search(r'(?:在|向)\s*([A-Za-z0-9\u4e00-\u9fa5\s&]{2,20})\s*(?:消费|转账|付款)', text)
        if m_cn:
            merchant = m_cn.group(1).strip()

    if not merchant:
        merchant = '自动追踪消费' if tx_type == 'expense' else '自动追踪入账'

    # 4. 自动归类分类 (Category)
    category = '其他'
    if tx_type == 'income':
        category = '其他'
        group_name = 'side'
    else:
        group_name = None
        # 优先通过商户名匹配映射表
        matched_cat = None
        m_lower = merchant.lower()
        for kw, cat in MERCHANT_CATEGORY_MAPPING.items():
            if kw in m_lower or kw in lower_text:
                matched_cat = cat
                break

        if not matched_cat:
            # 次优按通用支出分类关键词词库匹配
            for cat, kws in EXPENSE_CATEGORY_KEYWORDS.items():
                if any(k in m_lower or k in lower_text for k in kws):
                    matched_cat = cat
                    break

        category = matched_cat if matched_cat else '其他'

    # 5. 提取日期（若无法从文本中解析出 YYYY-MM-DD，则默认当前日期）
    tx_date = date.today().isoformat()
    m_date = re.search(r'(\d{4}[-/.]\d{1,2}[-/.]\d{1,2})', text)
    if m_date:
        try:
            d_str = m_date.group(1).replace('/', '-').replace('.', '-')
            # 格式化统一为 YYYY-MM-DD
            parts = d_str.split('-')
            tx_date = f'{int(parts[0]):04d}-{int(parts[1]):02d}-{int(parts[2]):02d}'
        except Exception:
            pass

    return {
        'date': tx_date,
        'type': tx_type,
        'group_name': group_name,
        'category': category,
        'amount': amount,
        'note': merchant,
        'is_refund': is_refund,
        'raw_text': text
    }


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
