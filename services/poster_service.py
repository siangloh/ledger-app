"""
Monthly Financial Poster Service.
Generates structured data, financial highlights, smart personality personas,
deep period comparisons (MoM / YoY), and actionable tailored savings recommendations.
Adheres to Explicit Parameters and Thin Controller architecture.
"""
from calendar import monthrange
from datetime import date
import logging
from typing import Dict, Any, Optional, List

from core.db import get_user_settings
from core.utils import shift_month

logger = logging.getLogger(__name__)

MONTH_NAMES_EN = [
    "", "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
    "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
]

MONTH_NAMES_MS = [
    "", "JANUARI", "FEBRUARI", "MAC", "APRIL", "MEI", "JUN",
    "JULAI", "OGOS", "SEPTEMBER", "OKTOBER", "NOVEMBER", "DISEMBER"
]

PERSONA_DATA: Dict[str, Dict[str, Any]] = {
    'fresh_start': {
        'badge': '🌱 Fresh Start',
        'zh': {'title': '静候启程者', 'commentary': '本月暂无收支记录，随时可以开始记下生活的第一笔开销。'},
        'zh_TW': {'title': '靜候啟程者', 'commentary': '本月暫無收支記錄，隨時可以開始記下生活的第一筆開銷。'},
        'en': {'title': 'Fresh Starter', 'commentary': 'No transactions recorded yet this month. Ready whenever you begin!'},
        'ms': {'title': 'Permulaan Baru', 'commentary': 'Tiada rekod perbelanjaan bulan ini. Bersedia bila-bila masa!'},
    },
    'master_saver': {
        'badge': '🏆 Master Saver',
        'zh': {'title': '储蓄大师', 'commentary': '储蓄率高达 {rate}%！远超平均线，严谨自律的财富沉淀为你构筑了极高的安全垫。'},
        'zh_TW': {'title': '儲蓄大師', 'commentary': '儲蓄率高達 {rate}%！遠超平均線，嚴謹自律的財富沉澱為你構筑了極高的安全墊。'},
        'en': {'title': 'Master Saver', 'commentary': 'Savings rate reached {rate}%! Exceptional discipline building solid wealth security.'},
        'ms': {'title': 'Pakar Penabung', 'commentary': 'Kadar simpanan mencapai {rate}%! Disiplin luar biasa membina keselamatan kewangan.'},
    },
    'wealth_builder': {
        'badge': '⭐ Wealth Builder',
        'zh': {'title': '黄金自律者', 'commentary': '保持了健康的 {rate}% 储蓄率，消费克制有度，财富正在稳步向上复利增长。'},
        'zh_TW': {'title': '黃金自律者', 'commentary': '保持了健康的 {rate}% 儲蓄率，消費克制有度，財富正在穩步向上複利增長。'},
        'en': {'title': 'Wealth Builder', 'commentary': 'Maintained a healthy {rate}% savings rate. Mindful spending accelerates steady growth.'},
        'ms': {'title': 'Pembina Kekayaan', 'commentary': 'Mengekalkan kadar simpanan {rate}% yang sihat. Perbelanjaan terkawal menjana pertumbuhan berterusan.'},
    },
    'balanced': {
        'badge': '⚖️ Balanced Life',
        'zh': {'title': '稳健生活家', 'commentary': '收支平衡良好，在享受当下生活乐趣的同时兼顾了未来的财富积累。'},
        'zh_TW': {'title': '穩健生活家', 'commentary': '收支平衡良好，在享受當下生活樂趣的同時兼顧了未來的財富積累。'},
        'en': {'title': 'Balanced Living', 'commentary': 'Healthy balance between enjoying life today and building security for tomorrow.'},
        'ms': {'title': 'Gaya Hidup Seimbang', 'commentary': 'Keseimbangan harmoni antara menikmati kehidupan dan menyimpan untuk masa depan.'},
    },
    'break_even': {
        'badge': '🛡️ Break-Even Pro',
        'zh': {'title': '收支平衡达人', 'commentary': '本月收支恰好持平，没有产生额外负债。下月可以尝试挑战存下 10% 的小目标！'},
        'zh_TW': {'title': '收支平衡達人', 'commentary': '本月收支恰好持平，沒有產生額外負債。下月可以嘗試挑戰存下 10% 的小目標！'},
        'en': {'title': 'Break-Even Pro', 'commentary': 'Broke even with zero extra debt. Challenge yourself to a 10% savings goal next month!'},
        'ms': {'title': 'Pakar Keseimbangan', 'commentary': 'Imbang tanpa beban hutang baharu. Sasarkan 10% simpanan untuk bulan depan!'},
    },
    'explorer': {
        'badge': '✨ Life Explorer',
        'zh': {'title': '体验探索者', 'commentary': '本月生活体验充实（净支出超出 {curr} {deficit}），偶有大额开销是常态，适时复盘即可回归正轨。'},
        'zh_TW': {'title': '體驗探索者', 'commentary': '本月生活體驗充實（淨支出超出 {curr} {deficit}），偶有大額開銷是常態，適時複盤即可回歸正軌。'},
        'en': {'title': 'Life Explorer', 'commentary': 'Invested richly in life experiences this month (deficit {curr} {deficit}). Periodic reviews bring easy balance.'},
        'ms': {'title': 'Peneroka Kehidupan', 'commentary': 'Bulan penuh pengalaman bermakna (defisit {curr} {deficit}). Semakan berkala mengembalikan kestabilan.'},
    }
}

QUOTES: Dict[str, str] = {
    'zh': '理性规划每一笔收支，让未来的自己拥有更多从容与选择。',
    'zh_TW': '理性規劃每一筆收支，讓未來的自己擁有更多從容與選擇。',
    'en': 'Mindful spending today creates boundless freedom tomorrow.',
    'ms': 'Pengurusan kewangan berhemah membina kebebasan masa depan.'
}

NO_SPEND_COMPLIMENTS: Dict[str, str] = {
    'zh': ' 达成 {days} 天零消费自律日，表现亮眼！',
    'zh_TW': ' 達成 {days} 天零消費自律日，表現亮眼！',
    'en': ' Achieved {days} zero-spend days, impressive self-control!',
    'ms': ' Mencapai {days} hari sifar perbelanjaan, pencapaian hebat!'
}

NEEDS_KEYWORDS = {
    '餐饮', '食品', '买菜', '超市', '房租', '房贷', '水电', '公用事业', '交通',
    '加油', '医疗', '健康', '保险', '药房', '账单',
    'food', 'groceries', 'supermarket', 'rent', 'mortgage', 'utilities',
    'transport', 'fuel', 'petrol', 'medical', 'insurance', 'bills',
    'makanan', 'runcit', 'pasar', 'sewa', 'utiliti', 'minyak', 'perubatan'
}


def _query_month_metrics(user_id: int, month_str: str, db: Any) -> Dict[str, Any]:
    """快速聚合某个月份的汇总指标（收入、支出、净结余、储蓄率、记录笔数）"""
    try:
        y, m = map(int, month_str.split('-'))
        days = monthrange(y, m)[1]
    except Exception:
        today = date.today()
        y, m = today.year, today.month
        month_str = f"{y:04d}-{m:02d}"
        days = monthrange(y, m)[1]

    start_d = f"{month_str}-01"
    end_d = f"{month_str}-{days:02d}"

    rows = db.execute('''
        SELECT amount, type, category
        FROM transactions
        WHERE user_id = ? AND date BETWEEN ? AND ?
    ''', (user_id, start_d, end_d)).fetchall()

    income = 0.0
    expense = 0.0
    cat_expenses: Dict[str, float] = {}

    for r in rows:
        amt = float(r['amount'] or 0.0)
        t = r['type']
        if t == 'income':
            income += amt
        elif t == 'expense':
            expense += amt
            c = r['category'] or '其他'
            cat_expenses[c] = cat_expenses.get(c, 0.0) + amt

    net = round(income - expense, 2)
    rate = round((net / income * 100), 1) if income > 0 else 0.0

    return {
        'month': month_str,
        'year': y,
        'month_num': m,
        'total_income': round(income, 2),
        'total_expense': round(expense, 2),
        'net_balance': net,
        'savings_rate': rate,
        'tx_count': len(rows),
        'category_expenses': cat_expenses
    }


def _calculate_comparison(
    current: Dict[str, Any],
    baseline: Dict[str, Any]
) -> Dict[str, Any]:
    """计算当月相对于基准月的差值与百分比变动（环比或同比）"""
    has_data = baseline['tx_count'] > 0 or baseline['total_income'] > 0 or baseline['total_expense'] > 0
    if not has_data:
        return {
            'has_data': False,
            'month': baseline['month'],
            'income_diff': 0.0,
            'income_pct': 0.0,
            'expense_diff': 0.0,
            'expense_pct': 0.0,
            'net_diff': 0.0,
            'savings_rate_diff': 0.0
        }

    cur_inc = current['total_income']
    base_inc = baseline['total_income']
    inc_diff = round(cur_inc - base_inc, 2)
    inc_pct = round((inc_diff / base_inc * 100), 1) if base_inc > 0 else (100.0 if cur_inc > 0 else 0.0)

    cur_exp = current['total_expense']
    base_exp = baseline['total_expense']
    exp_diff = round(cur_exp - base_exp, 2)
    exp_pct = round((exp_diff / base_exp * 100), 1) if base_exp > 0 else (100.0 if cur_exp > 0 else 0.0)

    net_diff = round(current['net_balance'] - baseline['net_balance'], 2)
    rate_diff = round(current['savings_rate'] - baseline['savings_rate'], 1)

    return {
        'has_data': True,
        'month': baseline['month'],
        'income_diff': inc_diff,
        'income_pct': inc_pct,
        'expense_diff': exp_diff,
        'expense_pct': exp_pct,
        'net_diff': net_diff,
        'savings_rate_diff': rate_diff
    }


def _generate_savings_strategies(
    metrics: Dict[str, Any],
    top_categories: List[Dict[str, Any]],
    currency_symbol: str,
    lang: str
) -> List[Dict[str, Any]]:
    """基于用户收支真实数据生成定制化储蓄策略与实操方案"""
    strategies: List[Dict[str, Any]] = []

    income = metrics.get('total_income', 0.0)
    expense = metrics.get('total_expense', 0.0)
    savings_rate = metrics.get('savings_rate', 0.0)
    no_spend_days = metrics.get('no_spend_days', 0)

    top_cat = top_categories[0] if top_categories else None

    # 1. 50/30/20 经典资产分配法则诊断
    if lang == 'en':
        s1_title = 'The 50/30/20 Budgeting Rule'
        if savings_rate >= 20:
            s1_badge = 'Healthy Savings'
            s1_desc = f"Your current savings rate is {savings_rate}%, beating the classic 20% benchmark! Consider investing excess funds into high-yield deposits or diversified funds."
        else:
            s1_badge = 'Opportunity to Boost'
            s1_desc = f"Current savings rate is {savings_rate}%. Aim to reserve 20% of your earnings ({currency_symbol} {round(income * 0.20, 2):,.2f}) into savings first before spending."
    elif lang == 'ms':
        s1_title = 'Peraturan Belanjawan 50/30/20'
        if savings_rate >= 20:
            s1_badge = 'Simpanan Cemerlang'
            s1_desc = f"Kadar simpanan anda {savings_rate}%, melepasi piawaian 20%! Pertimbangkan untuk melabur lebihan wang ke dalam simpanan tetap atau dana selamat."
        else:
            s1_badge = 'Peluang Tambah Simpanan'
            s1_desc = f"Kadar simpanan semasa adalah {savings_rate}%. Sasarkan untuk mengasingkan 20% ({currency_symbol} {round(income * 0.20, 2):,.2f}) ke dalam akaun simpanan sebaik sahaja gaji masuk."
    elif lang == 'zh_TW':
        s1_title = '50/30/20 經典資產配置法則'
        if savings_rate >= 20:
            s1_badge = '儲蓄表現優異'
            s1_desc = f"本月儲蓄率達 {savings_rate}%，超過了 20% 的黃金基準！可將穩健沉澱的盈餘轉入高息定存或指數定投，享受長期複利。"
        else:
            s1_badge = '有待優化提升'
            s1_desc = f"目前儲蓄率為 {savings_rate}%。建議將收入的 20%（約 {currency_symbol} {round(income * 0.20, 2):,.2f}）優先劃入專屬儲蓄池，守護安全墊。"
    else:
        s1_title = '50/30/20 经典资产配置法则'
        if savings_rate >= 20:
            s1_badge = '储蓄表现优异'
            s1_desc = f"本月储蓄率达 {savings_rate}%，超过了 20% 的黄金基准！可将稳健沉淀的盈余转入高息定存或指数定投，享受长期复利。"
        else:
            s1_badge = '有待优化提升'
            s1_desc = f"目前储蓄率为 {savings_rate}%。建议将收入的 20%（约 {currency_symbol} {round(income * 0.20, 2):,.2f}）优先划入专属储蓄池，守护安全垫。"

    strategies.append({
        'id': 'rule_50_30_20',
        'icon': '📊',
        'title': s1_title,
        'badge': s1_badge,
        'detail': s1_desc,
        'est_saving': f"{currency_symbol} {round(income * 0.20, 2):,.2f}" if income > 0 else "20%"
    })

    # 2. 最大支出分类专项节流方案
    if top_cat and top_cat['amount'] > 0:
        c_name = top_cat['name']
        c_amt = top_cat['amount']
        c_pct = top_cat['percentage']
        target_save = round(c_amt * 0.20, 2)

        if lang == 'en':
            s2_title = f"Optimize Top Category: {c_name}"
            s2_badge = f"{c_pct}% of Expenses"
            s2_desc = f"'{c_name}' accounted for {currency_symbol} {c_amt:,.2f} ({c_pct}%). Cutting non-essential splurges by 20% could free up {currency_symbol} {target_save:,.2f} monthly."
        elif lang == 'ms':
            s2_title = f"Optimumkan Kategori Utama: {c_name}"
            s2_badge = f"{c_pct}% Perbelanjaan"
            s2_desc = f"'{c_name}' mencatatkan {currency_symbol} {c_amt:,.2f} ({c_pct}%). Mengurangkan perbelanjaan tidak perlu sebanyak 20% dapat menjimatkan {currency_symbol} {target_save:,.2f} sebulan."
        elif lang == 'zh_TW':
            s2_title = f"頭號開支專項節流：【{c_name}】"
            s2_badge = f"佔總支出 {c_pct}%"
            s2_desc = f"【{c_name}】本月支出 {currency_symbol} {c_amt:,.2f}（佔比 {c_pct}%）。若實行針對性節制（如自煮或延時購買），節省 20% 即可月增 {currency_symbol} {target_save:,.2f} 儲蓄！"
        else:
            s2_title = f"头号开支专项节流：【{c_name}】"
            s2_badge = f"占总支出 {c_pct}%"
            s2_desc = f"【{c_name}】本月支出 {currency_symbol} {c_amt:,.2f}（占比 {c_pct}%）。若实行针对性节制（如自煮或延时购买），节省 20% 即可月增 {currency_symbol} {target_save:,.2f} 储蓄！"

        strategies.append({
            'id': 'top_cat_optimization',
            'icon': '🎯',
            'title': s2_title,
            'badge': s2_badge,
            'detail': s2_desc,
            'est_saving': f"{currency_symbol} {target_save:,.2f}"
        })

    # 3. 先存后花原则 (Pay Yourself First)
    auto_transfer_amt = round(income * 0.15, 2) if income > 0 else round(expense * 0.15, 2)
    if auto_transfer_amt < 100:
        auto_transfer_amt = 200.0

    if lang == 'en':
        s3_title = "Automated 'Pay Yourself First'"
        s3_badge = "Mindset Shift"
        s3_desc = f"Set up an automatic recurring transfer of {currency_symbol} {auto_transfer_amt:,.2f} to a separate vault on payday. Live on whatever remains rather than saving what is left."
    elif lang == 'ms':
        s3_title = "Prinsip 'Bayar Diri Sendiri Dulu'"
        s3_badge = "Disiplin Wang"
        s3_desc = f"Tetapkan pindahan automatik {currency_symbol} {auto_transfer_amt:,.2f} ke akaun simpanan berasingan pada hari gaji. Hidup dengan baki wang, bukannya menyimpan baki belanja."
    elif lang == 'zh_TW':
        s3_title = "自動化「先存後花」心法"
        s3_badge = "思維翻轉"
        s3_desc = f"在每月發薪日當天，立即自動劃扣 {currency_symbol} {auto_transfer_amt:,.2f} 至專門的儲蓄/定存賬戶。將「花剩才存」變為「存完才是預算」。"
    else:
        s3_title = "自动化「先存后花」心法"
        s3_badge = "思维翻转"
        s3_desc = f"在每月发薪日当天，立即自动划扣 {currency_symbol} {auto_transfer_amt:,.2f} 至专门的储蓄/定存账户。将「花剩才存」变为「存完才是预算」。"

    strategies.append({
        'id': 'pay_yourself_first',
        'icon': '🏦',
        'title': s3_title,
        'badge': s3_badge,
        'detail': s3_desc,
        'est_saving': f"{currency_symbol} {auto_transfer_amt:,.2f}"
    })

    # 4. 零支出日挑战 (No-Spend Day Streak)
    target_no_spend = max(no_spend_days + 3, 8)
    if lang == 'en':
        s4_title = "No-Spend Day Streak Challenge"
        s4_badge = f"Current: {no_spend_days} Days"
        s4_desc = f"You achieved {no_spend_days} zero-spend days this month. Challenge yourself to {target_no_spend} days next month by batch-prepping meals and avoiding impulse convenience buys."
    elif lang == 'ms':
        s4_title = "Cabaran Hari Sifar Belanja"
        s4_badge = f"Semasa: {no_spend_days} Hari"
        s4_desc = f"Anda capai {no_spend_days} hari sifar perbelanjaan bulan ini. Cabar diri untuk capai {target_no_spend} hari bulan depan dengan menyediakan makanan sendiri lebih awal."
    elif lang == 'zh_TW':
        s4_title = "零消費自律日進階挑戰"
        s4_badge = f"本月已達成: {no_spend_days} 天"
        s4_desc = f"本月已實現 {no_spend_days} 天零支出！下月可嘗試挑戰 {target_no_spend} 天零消費日，集中採購食材、自備咖啡，能有效減少零星微額開銷。"
    else:
        s4_title = "零消费自律日进阶挑战"
        s4_badge = f"本月已达成: {no_spend_days} 天"
        s4_desc = f"本月已实现 {no_spend_days} 天零支出！下月可尝试挑战 {target_no_spend} 天零消费日，集中采购食材、自备咖啡，能有效减少零星微额开销。"

    strategies.append({
        'id': 'no_spend_challenge',
        'icon': '🗓️',
        'title': s4_title,
        'badge': s4_badge,
        'detail': s4_desc,
        'est_saving': f"{target_no_spend} Days"
    })

    return strategies


def get_monthly_poster_data(
    user_id: int,
    month_str: Optional[str] = None,
    lang: str = 'zh',
    db: Any = None
) -> Dict[str, Any]:
    """
    Compile comprehensive monthly summary data, achievements, MoM/YoY comparisons,
    and smart personalized savings recommendations for poster and deep-analysis report export.

    :param user_id: Current user ID.
    :param month_str: Month formatted as 'YYYY-MM'. If None, defaults to current month.
    :param lang: Language code ('zh', 'zh_TW', 'en', 'ms').
    :param db: Active SQLite/LibSQL database connection.
    :return: Rich dictionary containing financials, top categories, highlights, persona, MoM/YoY, and savings recommendations.
    """
    if not db or not user_id:
        return {'ok': False, 'message': 'Missing database connection or user ID'}

    if not month_str:
        month_str = date.today().strftime('%Y-%m')

    if lang not in ('zh', 'zh_TW', 'en', 'ms'):
        lang = 'zh'

    try:
        year_val, month_val = map(int, month_str.split('-'))
    except (ValueError, AttributeError):
        today = date.today()
        year_val, month_val = today.year, today.month
        month_str = f"{year_val:04d}-{month_val:02d}"

    days_in_month = monthrange(year_val, month_val)[1]
    start_date = f"{month_str}-01"
    end_date = f"{month_str}-{days_in_month:02d}"

    currency_symbol = 'RM'
    try:
        settings = get_user_settings(user_id, db=db)
        currency_symbol = settings.get('currency_symbol', 'RM')
    except Exception as e:
        logger.debug("Failed to get user currency settings: %s", e)

    try:
        # 1. 抓取该月份的所有收支明细
        rows = db.execute('''
            SELECT id, date, type, group_name, category, amount, note, from_savings
            FROM transactions
            WHERE user_id = ? AND date BETWEEN ? AND ?
            ORDER BY date ASC, id ASC
        ''', (user_id, start_date, end_date)).fetchall()

        total_income = 0.0
        total_expense = 0.0
        regular_expense = 0.0
        savings_allocated = 0.0
        main_income = 0.0
        side_income = 0.0

        needs_expense = 0.0
        wants_expense = 0.0

        category_expenses: Dict[str, float] = {}
        daily_expense_totals: Dict[str, float] = {f"{month_str}-{d:02d}": 0.0 for d in range(1, days_in_month + 1)}
        max_expense = {'amount': 0.0, 'note': '', 'category': '', 'date': ''}

        tx_count = len(rows)

        for r in rows:
            amt = float(r['amount'] or 0.0)
            tx_type = r['type']
            tx_date = r['date']
            cat = r['category'] or '其他'
            note = (r['note'] or '').strip()

            if tx_type == 'income':
                total_income += amt
                gn = r['group_name'] or 'main'
                if gn == 'side':
                    side_income += amt
                else:
                    main_income += amt
            elif tx_type == 'expense':
                total_expense += amt
                if r['from_savings']:
                    savings_allocated += amt
                else:
                    regular_expense += amt

                category_expenses[cat] = category_expenses.get(cat, 0.0) + amt
                if tx_date in daily_expense_totals:
                    daily_expense_totals[tx_date] += amt

                # 50/30/20 刚需与品质开支区分
                c_lower = cat.lower()
                if any(kw in c_lower for kw in NEEDS_KEYWORDS):
                    needs_expense += amt
                else:
                    wants_expense += amt

                if amt > max_expense['amount']:
                    max_expense = {
                        'amount': round(amt, 2),
                        'note': note or cat,
                        'category': cat,
                        'date': tx_date
                    }
            elif tx_type == 'savings':
                savings_allocated += amt

        # 净储蓄与储蓄率计算
        net_balance = round(total_income - total_expense, 2)
        savings_rate = round((net_balance / total_income * 100), 1) if total_income > 0 else 0.0

        # 计算零支出天数 (No-spend days)
        today_iso = date.today().isoformat()
        evaluated_days = days_in_month
        if month_str == today_iso[:7]:
            evaluated_days = min(date.today().day, days_in_month)

        no_spend_days = 0
        for d in range(1, evaluated_days + 1):
            d_str = f"{month_str}-{d:02d}"
            if daily_expense_totals.get(d_str, 0.0) == 0.0:
                no_spend_days += 1

        # 支出分类排行（前 5 项）
        sorted_cats = sorted(category_expenses.items(), key=lambda x: x[1], reverse=True)
        top_categories = []
        for cname, camt in sorted_cats[:5]:
            pct = round((camt / total_expense * 100), 1) if total_expense > 0 else 0.0
            top_categories.append({
                'name': cname,
                'amount': round(camt, 2),
                'percentage': pct
            })

        top_cat_name = top_categories[0]['name'] if top_categories else ('无支出' if lang.startswith('zh') else 'None')

        # 智能理财人格判定 (Financial Persona)
        if total_income == 0 and total_expense == 0:
            p_key = 'fresh_start'
        elif savings_rate >= 50:
            p_key = 'master_saver'
        elif savings_rate >= 30:
            p_key = 'wealth_builder'
        elif savings_rate >= 10:
            p_key = 'balanced'
        elif net_balance >= 0:
            p_key = 'break_even'
        else:
            p_key = 'explorer'

        p_info = PERSONA_DATA[p_key]
        p_lang_dict = p_info.get(lang, p_info['zh'])
        persona_title = p_lang_dict['title']
        persona_badge = p_info['badge']

        commentary_template = p_lang_dict['commentary']
        deficit_val = f"{abs(net_balance):,.2f}"
        commentary = commentary_template.format(
            rate=savings_rate,
            deficit=deficit_val,
            curr=currency_symbol
        )

        if no_spend_days >= 8 and tx_count > 0:
            comp_tmpl = NO_SPEND_COMPLIMENTS.get(lang, NO_SPEND_COMPLIMENTS['zh'])
            commentary += comp_tmpl.format(days=no_spend_days)

        current_summary = {
            'month': month_str,
            'year': year_val,
            'month_num': month_val,
            'total_income': round(total_income, 2),
            'total_expense': round(total_expense, 2),
            'net_balance': net_balance,
            'savings_rate': savings_rate,
            'tx_count': tx_count
        }

        # 2. 环比与同比数据计算 (MoM / YoY Comparison)
        prev_month_str = shift_month(month_str, -1)
        prev_year_month_str = shift_month(month_str, -12)

        prev_month_metrics = _query_month_metrics(user_id, prev_month_str, db)
        prev_year_metrics = _query_month_metrics(user_id, prev_year_month_str, db)

        mom_comparison = _calculate_comparison(current_summary, prev_month_metrics)
        yoy_comparison = _calculate_comparison(current_summary, prev_year_metrics)

        # 3. 50/30/20 结构拆解
        needs_ratio = round((needs_expense / total_income * 100), 1) if total_income > 0 else 0.0
        wants_ratio = round((wants_expense / total_income * 100), 1) if total_income > 0 else 0.0

        metrics_dict = {
            'total_income': round(total_income, 2),
            'total_expense': round(total_expense, 2),
            'regular_expense': round(regular_expense, 2),
            'savings_allocated': round(savings_allocated, 2),
            'net_balance': net_balance,
            'savings_rate': savings_rate,
            'tx_count': tx_count,
            'no_spend_days': no_spend_days,
            'avg_daily_expense': round(total_expense / max(evaluated_days, 1), 2),
            'needs_expense': round(needs_expense, 2),
            'wants_expense': round(wants_expense, 2),
            'needs_ratio': needs_ratio,
            'wants_ratio': wants_ratio
        }

        # 4. 生成专属储蓄建议
        savings_strategies = _generate_savings_strategies(
            metrics=metrics_dict,
            top_categories=top_categories,
            currency_symbol=currency_symbol,
            lang=lang
        )

        month_display_title = (
            f"{MONTH_NAMES_EN[month_val]} {year_val}" if lang == 'en'
            else f"{MONTH_NAMES_MS[month_val]} {year_val}" if lang == 'ms'
            else f"{year_val}年{month_val:02d}月"
        )

        return {
            'ok': True,
            'month': month_str,
            'year': year_val,
            'month_num': month_val,
            'month_name_en': MONTH_NAMES_EN[month_val] if 1 <= month_val <= 12 else '',
            'month_name_ms': MONTH_NAMES_MS[month_val] if 1 <= month_val <= 12 else '',
            'month_name_zh': f"{year_val}年{month_val:02d}月",
            'month_display_title': month_display_title,
            'days_in_month': days_in_month,
            'currency_symbol': currency_symbol,
            'lang': lang,
            'quote': QUOTES.get(lang, QUOTES['zh']),
            'metrics': metrics_dict,
            'income_breakdown': {
                'main': round(main_income, 2),
                'side': round(side_income, 2),
                'side_ratio': round((side_income / total_income * 100), 1) if total_income > 0 else 0.0
            },
            'top_categories': top_categories,
            'top_category': top_cat_name,
            'max_expense': max_expense,
            'persona': {
                'key': p_key,
                'title': persona_title,
                'badge': persona_badge,
                'commentary': commentary
            },
            'comparison': {
                'mom': mom_comparison,
                'yoy': yoy_comparison
            },
            'savings_strategies': savings_strategies
        }
    except Exception as e:
        logger.error("Error generating monthly poster data for user %s, month %s: %s", user_id, month_str, e, exc_info=True)
        return {'ok': False, 'message': f'Failed to generate poster data: {e}'}
