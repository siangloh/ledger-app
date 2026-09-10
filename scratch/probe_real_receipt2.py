import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_probe')
os.makedirs('/tmp/ledger_probe', exist_ok=True)
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from app import parse_receipt_text_to_items, app

raw = """2 GOOD MOOD KITCHEN
3 GOOD MOOD KITCHEN
REG NO: 202603008078 (MAD343696 1)
, JALAN VERVEA 9, 14110, SIMPANG AMPAT, PUL AL
PINANG, MALAYSIA
no: 1559 ORDER
Date: 09/09/2026 17.
Cashier: Admin #TA067
Table pax: 1
Fitna tu USE
Qty tem Price (MYR)
1 Lemon Chicken Rice Set 174#13 J i 14.90
xe (Takeaway) (14.90/ea)
1 Luncheon Meat Fried Rice 8 F 18 11.90
(Takeaway) (11.90/ea)
. TA
2 ay EEE
Subtotal
Blll rounding ge 8
Total (MYR) 26.80
DUIT NOW 26 80
Change 0 00"""

parsed = parse_receipt_text_to_items(raw)
print("--- items ---")
for it in parsed['items']:
    print(f"  {it['quantity']}x {it['name']!r} @ {it['price']}")
print(f"subtotal={parsed['subtotal']} total={parsed['total']}")

print("\n--- via /split-bill/parse-text endpoint (exactly what the button calls) ---")
with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1
    resp = client.post('/split-bill/parse-text', data={'text': raw})
    print(f"status={resp.status_code}")
    print(resp.get_json())
