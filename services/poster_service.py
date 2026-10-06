"""
Monthly Financial Poster Service.
Generates structured data, financial highlights, smart personality personas,
and witty monthly commentary for magazine-quality financial summary poster export.
Adheres to Explicit Parameters and Thin Controller architecture.
"""
from calendar import monthrange
from datetime import date
import logging
from typing import Dict, Any, Optional

from core.db import get_user_settings

logger = logging.getLogger(__name__)

MONTH_NAMES_EN = [
    "", "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
    "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
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


def get_monthly_poster_data(
    user_id: int,
    month_str: Optional[str] = None,
    lang: str = 'zh',
    db: Any = None
) -> Dict[str, Any]:
    """
    Compile comprehensive monthly summary data and achievements for poster generation.

    :param user_id: Current user ID.
    :param month_str: Month formatted as 'YYYY-MM'. If None, defaults to current month.
    :param lang: Language code ('zh', 'zh_TW', 'en', 'ms').
    :param db: Active SQLite/LibSQL database connection.
    :return: Rich dictionary containing financials, top categories, highlights, persona, and commentary.
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

        return {
            'ok': True,
            'month': month_str,
            'year': year_val,
            'month_num': month_val,
            'month_name_en': MONTH_NAMES_EN[month_val] if 1 <= month_val <= 12 else '',
            'month_name_zh': f"{year_val}年{month_val:02d}月",
            'days_in_month': days_in_month,
            'currency_symbol': currency_symbol,
            'lang': lang,
            'quote': QUOTES.get(lang, QUOTES['zh']),
            'metrics': {
                'total_income': round(total_income, 2),
                'total_expense': round(total_expense, 2),
                'regular_expense': round(regular_expense, 2),
                'savings_allocated': round(savings_allocated, 2),
                'net_balance': net_balance,
                'savings_rate': savings_rate,
                'tx_count': tx_count,
                'no_spend_days': no_spend_days,
                'avg_daily_expense': round(total_expense / max(evaluated_days, 1), 2)
            },
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
            }
        }
    except Exception as e:
        logger.error("Error generating monthly poster data for user %s, month %s: %s", user_id, month_str, e, exc_info=True)
        return {'ok': False, 'message': f'Failed to generate poster data: {e}'}
