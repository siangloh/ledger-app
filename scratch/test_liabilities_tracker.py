import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from liabilities_tracker import generate_amortization_schedule, add_months_clamped
from datetime import date

def test_tail_cents():
    print("Testing 1000 / 3 EPP tail cents...")
    res = generate_amortization_schedule(1000.00, 3, "2026-01-15", 0.0, "zero_interest")
    assert len(res) == 3
    assert res[0]['principal'] == 333.34, f"Period 1 expected 333.34, got {res[0]['principal']}"
    assert res[1]['principal'] == 333.33
    assert res[2]['principal'] == 333.33
    total = sum(r['principal'] for r in res)
    assert round(total, 2) == 1000.00
    assert res[2]['remaining_balance'] == 0.00
    print("[PASS] Tail cents test passed: 333.34 + 333.33 + 333.33 = 1000.00")

def test_month_end_clamp():
    print("Testing month end 31st clamp...")
    res = generate_amortization_schedule(300.00, 3, "2026-01-31", 0.0, "zero_interest")
    assert res[0]['due_date'] == "2026-01-31"
    assert res[1]['due_date'] == "2026-02-28", f"Feb expected 2026-02-28, got {res[1]['due_date']}"
    assert res[2]['due_date'] == "2026-03-31", f"Mar expected 2026-03-31, got {res[2]['due_date']}"
    print("[PASS] Month-end clamp test passed: 01-31 -> 02-28 -> 03-31")

def test_reducing_balance():
    print("Testing reducing balance mortgage (50,000, 4.25%, 12 periods)...")
    res = generate_amortization_schedule(50000.00, 12, "2026-01-05", 4.25, "reducing_balance")
    assert len(res) == 12
    assert res[11]['remaining_balance'] == 0.00, f"Expected 0.00, got {res[11]['remaining_balance']}"
    assert res[0]['interest'] > res[11]['interest'], "Interest must decrease over time"
    assert res[0]['principal'] < res[11]['principal'], "Principal must increase over time"
    print(f"[PASS] Reducing balance test passed: Monthly ~{res[0]['total_amount']}, Final balance = 0.00")

def test_flat_rate_car_loan():
    print("Testing car loan flat rate (80,000, 3.2%, 60 periods)...")
    res = generate_amortization_schedule(80000.00, 60, "2026-01-10", 3.2, "flat_rate")
    assert len(res) == 60
    assert res[59]['remaining_balance'] == 0.00
    print(f"[PASS] Car loan flat rate passed: Monthly = {res[0]['total_amount']}, Final balance = 0.00")

if __name__ == '__main__':
    test_tail_cents()
    test_month_end_clamp()
    test_reducing_balance()
    test_flat_rate_car_loan()
    print("ALL TESTS PASSED SUCCESSFULLY!")
