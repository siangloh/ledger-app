import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_probe')
os.makedirs('/tmp/ledger_probe', exist_ok=True)
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from app import parse_receipt_text_to_items

raw = """REG NO: 203603008578 (MAB 43096 »)
NO 43, JALAN VERVEA 9, 14110, SIMPANG AMPAT, PULAU
PINANG, MALAYSIA
Invoice no: 1559 ORDER
Date: 09/09/2026 17.56
Cashler: Admin #TA067
Table pax: 1
Qty Item Price (MYR)
1 Lemon Chicken Rice Set #133 J 1% 14.90
E# (Takeaway) (14.90/ea)
"TA
1 Luncheon Meat Fried Rice 5&4 1% 11.90
(Takeaway) (11.90/ea)
- TA
2 ly
Subtotal 26.80
Bll rounding 0.00
Total (MYR) 26.80
DUIT NOW 26.80
Change 0.00"""

parsed = parse_receipt_text_to_items(raw)
print("--- items ---")
for it in parsed['items']:
    print(f"  {it['quantity']}x {it['name']!r} @ {it['price']}")
print(f"subtotal={parsed['subtotal']} service_charge={parsed['service_charge']} tax={parsed['tax']} "
      f"discount={parsed['discount']} rounding={parsed['rounding']} total={parsed['total']}")
items_sum = sum(i['price'] for i in parsed['items'])
print(f"items_sum={items_sum}  balanced vs subtotal? {abs(items_sum - parsed['subtotal']) < 0.01}")
