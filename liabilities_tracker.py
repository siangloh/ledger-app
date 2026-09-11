"""
liabilities_tracker.py
负债、信用卡与分期付款追踪核心算法库 (Liabilities & Installments Tracker)
"""

from decimal import Decimal, ROUND_HALF_UP, ROUND_FLOOR
from datetime import datetime, date
import calendar
from typing import List, Dict, Any, Optional

CENTS = Decimal('0.01')


def round_money(val: Decimal) -> Decimal:
    """标准财务四舍五入到分 (0.01)"""
    return val.quantize(CENTS, rounding=ROUND_HALF_UP)


def add_months_clamped(orig_date: date, months_to_add: int, prefer_day: Optional[int] = None) -> date:
    """
    智能月份推进器：正确处理月末 28/29/30/31 日边界问题。
    例如：指定 prefer_day 为 31 号时：
    1月31日 + 1个月 => 2月28/29日；再 + 1个月 => 3月31日。
    """
    target_day = prefer_day if prefer_day is not None else orig_date.day
    total_months = orig_date.year * 12 + (orig_date.month - 1) + months_to_add
    new_year = total_months // 12
    new_month = (total_months % 12) + 1
    
    max_days = calendar.monthrange(new_year, new_month)[1]
    actual_day = min(target_day, max_days)
    return date(new_year, new_month, actual_day)


def generate_amortization_schedule(
    total_amount: float,
    tenure_months: int,
    start_date: str,
    interest_rate: float = 0.0,
    method: str = "zero_interest"
) -> List[Dict[str, Any]]:
    """
    生成完整的逐期还款排程数组。
    
    :param total_amount: 原始总本金
    :param tenure_months: 总分期期数
    :param start_date: 首期扣款日期 (YYYY-MM-DD)
    :param interest_rate: 年化利率百分比 (例如 4.25 代表 4.25%)
    :param method: 'zero_interest'(0%免息分期), 'flat_rate'(平息法/车贷), 'reducing_balance'(等额本息/房贷)
    :return: 逐期排程明细列表
    """
    if tenure_months <= 0:
        raise ValueError("Tenure months must be greater than 0")
    
    principal_dec = Decimal(str(total_amount))
    rate_dec = Decimal(str(interest_rate)) / Decimal('100')
    start_dt = datetime.strptime(start_date, "%Y-%m-%d").date()
    target_day = start_dt.day

    schedule = []
    remaining_balance = principal_dec

    # 1. 免息分期付款 (0% EPP): 无利息，本金等分，除不尽尾差注入首期
    if method == "zero_interest" or rate_dec == 0:
        base_monthly = (principal_dec / tenure_months).quantize(CENTS, rounding=ROUND_FLOOR)
        first_installment = principal_dec - (base_monthly * (tenure_months - 1))

        for i in range(1, tenure_months + 1):
            due_date = add_months_clamped(start_dt, i - 1, prefer_day=target_day)
            curr_principal = first_installment if i == 1 else base_monthly
            remaining_balance = round_money(remaining_balance - curr_principal)
            
            if i == tenure_months:
                remaining_balance = Decimal('0.00')

            schedule.append({
                "period": i,
                "due_date": due_date.strftime("%Y-%m-%d"),
                "principal": float(curr_principal),
                "interest": 0.00,
                "total_amount": float(curr_principal),
                "remaining_balance": float(remaining_balance)
            })
        return schedule

    # 2. 车贷平息法 (Flat Rate): 总利息一开始直接按总年限乘死，每月等额还本金+固定利息
    elif method == "flat_rate":
        years = Decimal(str(tenure_months)) / Decimal('12')
        total_interest = round_money(principal_dec * rate_dec * years)
        monthly_interest = round_money(total_interest / tenure_months)
        base_principal = (principal_dec / tenure_months).quantize(CENTS, rounding=ROUND_FLOOR)
        first_principal = principal_dec - (base_principal * (tenure_months - 1))

        for i in range(1, tenure_months + 1):
            due_date = add_months_clamped(start_dt, i - 1, prefer_day=target_day)
            curr_principal = first_principal if i == 1 else base_principal
            remaining_balance = round_money(remaining_balance - curr_principal)
            if i == tenure_months:
                remaining_balance = Decimal('0.00')

            schedule.append({
                "period": i,
                "due_date": due_date.strftime("%Y-%m-%d"),
                "principal": float(curr_principal),
                "interest": float(monthly_interest),
                "total_amount": float(curr_principal + monthly_interest),
                "remaining_balance": float(remaining_balance)
            })
        return schedule

    # 3. 等额本息 / 减少余额法 (Reducing Balance - 常见于房贷/个人按揭)
    elif method == "reducing_balance":
        monthly_rate = rate_dec / Decimal('12')
        factor = (Decimal('1') + monthly_rate) ** tenure_months
        monthly_payment = round_money(principal_dec * (monthly_rate * factor) / (factor - Decimal('1')))

        for i in range(1, tenure_months + 1):
            due_date = add_months_clamped(start_dt, i - 1, prefer_day=target_day)
            curr_interest = round_money(remaining_balance * monthly_rate)
            
            if i == tenure_months:
                curr_principal = remaining_balance
                total_pmt = round_money(curr_principal + curr_interest)
                remaining_balance = Decimal('0.00')
            else:
                curr_principal = round_money(monthly_payment - curr_interest)
                remaining_balance = round_money(remaining_balance - curr_principal)

            schedule.append({
                "period": i,
                "due_date": due_date.strftime("%Y-%m-%d"),
                "principal": float(curr_principal),
                "interest": float(curr_interest),
                "total_amount": float(monthly_payment if i < tenure_months else total_pmt),
                "remaining_balance": float(remaining_balance)
            })
        return schedule

    else:
        raise NotImplementedError(f"Unsupported calculation method: {method}")


def get_monthly_cashflow_events(
    db_conn,
    user_id: str,
    target_year: int,
    target_month: int
) -> Dict[str, Any]:
    """
    聚合指定月份内所有的刚性还款流（固定贷款扣款 + 信用卡分期当期入账），
    输出升序排列的统一事件列表与汇总指标。
    """
    month_prefix = f"{target_year:04d}-{target_month:02d}"
    events = []
    total_rigid_payment = Decimal('0.00')
    upcoming_releases = []

    # A. 提取当月所有活跃贷款扣款 (Loans)
    loan_rows = db_conn.execute("""
        SELECT l.id, l.title, l.monthly_payment, l.due_day, l.paid_periods, l.tenure_months, 
               l.remaining_balance, l.loan_amount, a.name as account_name
        FROM loans l
        LEFT JOIN accounts a ON l.debit_account_id = a.id
        WHERE l.user_id = ? AND l.status = 'active'
    """, (user_id,)).fetchall()

    for row in loan_rows:
        max_d = calendar.monthrange(target_year, target_month)[1]
        day = min(row['due_day'], max_d)
        event_date = f"{month_prefix}-{day:02d}"
        
        amt = Decimal(str(row['monthly_payment']))
        total_rigid_payment += amt
        curr_period = (row['paid_periods'] or 0) + 1
        tenure = row['tenure_months'] or 360
        is_last = (curr_period >= tenure)

        events.append({
            "id": row['id'],
            "eventDate": event_date,
            "day": day,
            "title": row['title'],
            "type": "固定贷款",
            "accountId": row['account_name'] or "主账户",
            "amount": float(amt),
            "status": "[本月完结 🎉]" if is_last else "自动扣款",
            "periodProgress": f"{curr_period}/{tenure}",
            "remainingBalance": float(row['remaining_balance'] or 0.0),
            "totalAmount": float(row['loan_amount'] or 0.0)
        })

    # B. 提取当月活跃免息分期 (Installments)
    inst_rows = db_conn.execute("""
        SELECT i.id, i.title, i.monthly_amount, i.total_amount, i.paid_periods, i.tenure_months, 
               i.first_due_date, a.name as card_name, a.due_day as card_due_day
        FROM installments i
        LEFT JOIN accounts a ON i.account_id = a.id
        WHERE i.user_id = ? AND i.status = 'active'
    """, (user_id,)).fetchall()

    for row in inst_rows:
        try:
            first_dt = datetime.strptime(row['first_due_date'], "%Y-%m-%d").date()
        except Exception:
            first_dt = date(target_year, target_month, 1)

        diff_months = (target_year - first_dt.year) * 12 + (target_month - first_dt.month)
        tenure = row['tenure_months'] or 12
        
        if 0 <= diff_months < tenure:
            curr_period = diff_months + 1
            due_day = row['card_due_day'] or first_dt.day
            max_d = calendar.monthrange(target_year, target_month)[1]
            day = min(due_day, max_d)
            event_date = f"{month_prefix}-{day:02d}"

            amt = Decimal(str(row['monthly_amount']))
            total_rigid_payment += amt
            is_last = (curr_period == tenure)
            remaining_periods = tenure - curr_period
            remaining_bal = max(0.0, float(Decimal(str(row['total_amount'])) - (Decimal(str(row['monthly_amount'])) * curr_period)))

            events.append({
                "id": row['id'],
                "eventDate": event_date,
                "day": day,
                "title": row['title'],
                "type": "免息分期(EPP)",
                "accountId": row['card_name'] or "信用卡",
                "amount": float(amt),
                "status": "[本月完结 🎉]" if is_last else "随卡账单还款",
                "periodProgress": f"{curr_period}/{tenure}",
                "remainingBalance": remaining_bal,
                "totalAmount": float(row['total_amount'])
            })

            # 计算预计结清年月
            settle_date = add_months_clamped(first_dt, tenure - 1)
            upcoming_releases.append({
                "title": row['title'],
                "monthly_amount": float(amt),
                "remaining_periods": remaining_periods,
                "settle_month": settle_date.strftime("%Y-%m")
            })

    # 按扣款日升序排列 (1日 -> 31日)
    events.sort(key=lambda x: x['eventDate'])

    # 按预计结清时间排序，取最近释放的现金流点
    upcoming_releases.sort(key=lambda x: x['remaining_periods'])

    return {
        "month": month_prefix,
        "events": events,
        "totalRigidPayment": float(total_rigid_payment),
        "upcomingReleases": upcoming_releases[:3]
    }


def sync_installments_to_monthly_statement(db_conn, current_date_str: str) -> Dict[str, Any]:
    """
    定时/触发同步任务：
    到达当月账单还款周期时，将分期款记入系统主账本 transactions 表挂账，并递增已还期数。
    当期数达到总期数时，自动标记为 'completed'。
    """
    curr_dt = datetime.strptime(current_date_str, "%Y-%m-%d").date()
    curr_month_str = curr_dt.strftime("%Y-%m")
    
    synced_count = 0
    completed_count = 0

    active_installments = db_conn.execute("""
        SELECT * FROM installments 
        WHERE status = 'active' AND (last_synced_month IS NULL OR last_synced_month != ?)
    """, (curr_month_str,)).fetchall()

    for item in active_installments:
        first_dt = datetime.strptime(item['first_due_date'], "%Y-%m-%d").date()
        target_day = first_dt.day
        due_day_clamped = min(target_day, calendar.monthrange(curr_dt.year, curr_dt.month)[1])

        # 到达或超过扣款日时执行扣款流水生成
        if curr_dt.day >= due_day_clamped:
            new_paid = (item['paid_periods'] or 0) + 1
            is_done = (new_paid >= item['tenure_months'])
            new_status = 'completed' if is_done else 'active'

            # 1. 在 transactions 表中写入支出流水
            db_conn.execute("""
                INSERT INTO transactions (
                    user_id, date, type, group_name, category, amount, note, source, created_at
                ) VALUES (?, ?, 'expense', '固定还款', '分期还款', ?, ?, 'installment_auto', ?)
            """, (
                item['user_id'],
                current_date_str,
                item['monthly_amount'],
                f"{item['title']} (第 {new_paid}/{item['tenure_months']} 期)",
                datetime.now().isoformat()
            ))

            # 2. 更新 installments 状态
            db_conn.execute("""
                UPDATE installments 
                SET paid_periods = ?, status = ?, last_synced_month = ?
                WHERE id = ?
            """, (new_paid, new_status, curr_month_str, item['id']))

            synced_count += 1
            if is_done:
                completed_count += 1

    db_conn.commit()
    return {
        "ok": True,
        "syncedRecords": synced_count,
        "completedInstallments": completed_count
    }
