import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_probe')
os.makedirs('/tmp/ledger_probe', exist_ok=True)
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from app import parse_receipt_text_to_items

raw = """F oob MOOD KITCHEN
Eo GOOD MOOD KITCHEN
REG NO: 202603068078 (MAD343696-P) :
43, JALAN VERVEA 9, 14110, SIMPANG AMPAT, PULAU
EF PINANG, MALAYSIA
role no: 1565 ORDER
Date: 09/09/2026 17.56
{ Cashier: Admin #TA067
_ Table pax: 1
[Qty tem Price (MYR) |
1 Lemon Chicken Rice Set F7{ii3 T 15 14.90 ，
ue (Takeaway) (14.90/ea) i
*TA &
1 Luncheon Meat Fried Rice 午餐 内 炒饭 11.90 起
(Takeaway) (11.90/ea) fo
"TA &
Z% qty
Subtotal 26.80 |
Blll rounding 0.00 by
Total (MYR) 26.80 |
DUIT NOW 26.80 §
Change 0.00 |"""

parsed = parse_receipt_text_to_items(raw)
print("--- items ---")
for it in parsed['items']:
    print(f"  {it['quantity']}x {it['name']!r} @ {it['price']}")
print(f"subtotal={parsed['subtotal']} tax={parsed['tax']} service_charge={parsed['service_charge']} rounding={parsed['rounding']} discount={parsed['discount']} total={parsed['total']}")

items_sum = sum(i['price'] for i in parsed['items'])
print(f"\nitems_sum={items_sum}  vs subtotal={parsed['subtotal']}  vs total={parsed['total']}")
print(f"balanced? items_sum == subtotal: {abs(items_sum - parsed['subtotal']) < 0.01}")

assert len(parsed['items']) == 2, f"expected 2 items, got {len(parsed['items'])}: {parsed['items']}"
assert abs(parsed['subtotal'] - 26.80) < 0.01
assert abs(parsed['total'] - 26.80) < 0.01
assert abs(items_sum - parsed['subtotal']) < 0.01, "items no longer sum to the printed subtotal"
prices = sorted(round(i['price'], 2) for i in parsed['items'])
assert prices == [11.90, 14.90], f"unexpected prices: {prices}"
print("\nPASS: real Good Mood Kitchen receipt (user-supplied, 2026-09-10) now parses to 2 balanced items.")
print("NOTE: item names still contain OCR noise (e.g. garbled Chinese characters misread by the")
print("OCR engine) — that's an OCR-accuracy limitation, not something this text-parsing layer can")
print("fully clean up. The business-critical numbers (item count, prices, subtotal/total match) are")
print("now correct, which is what actually determines whether the split-bill balance page works.")
