import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_probe')
os.makedirs('/tmp/ledger_probe', exist_ok=True)
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from app import parse_receipt_text_to_items

raw = """a Lo CE
REG NO: F02605088578 (MAGS Autwa EL
NO 43, JALAN VERVEA 9, 14110, SIMPANG AMPAT, PULAU #0
setae GRILL LCOS
Invoice no: 1559 ORDER ty
Date: 09/09/2026 17.56 Hz i
Cashier: Admin #TAQ67 ©
Table pax: 1
Qty Item Price (MYR)
1 Lemon Chicken Rice Set f7{#13 71% 14.90
EB # (Takeaway) (14.90/ea)
"TA
Al Luncheon Meat Fried Rice 4 #& P1445 11.90
(Takeaway) (11.90/ea)
- TA ENR i.
z oy it
Subtotal a be
Blil rounding : .
Total (MYR) SS
DUIT Now 0.00
Change )
Change 800 EE"""

parsed = parse_receipt_text_to_items(raw)
print("--- items ---")
for it in parsed['items']:
    print(f"  {it['quantity']}x {it['name']!r} @ {it['price']}")
print(f"subtotal={parsed['subtotal']} service_charge={parsed['service_charge']} tax={parsed['tax']} "
      f"discount={parsed['discount']} rounding={parsed['rounding']} total={parsed['total']}")
items_sum = sum(i['price'] for i in parsed['items'])
print(f"items_sum={items_sum}  balanced vs subtotal? {abs(items_sum - parsed['subtotal']) < 0.01}")
print(f"balanced vs total? {abs(items_sum - parsed['total']) < 0.01}")
