import re
import sys
import io

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

text = """GOOD MOOD KITCHEN
NO. 43, JALAN VERVEA 9, 14110, SIMPANG AMPAT, PULAU PINANG, MALAYSIA
REG NO: 202603068078 (MA0343696-P)
ORDER #TA067
Invoice no: 1559
Date: 09/09/2026 17:56
Cashier: Admin
Table pax: 1
Qty Item Price (MYR)
1 Lemon Chicken Rice Set 柠檬鸡丁饭 14.90
• TA
1 Luncheon Meat Fried Rice 午餐肉炒饭 11.90
(Takeaway) (11.90/ea)
• TA
2 Qty
Subtotal 26.80
Bill rounding 0.00
Total (MYR) 26.80
DUIT NOW 26.80
Change 0.00
Thank you for visiting us. We hope to see you again soon!
"""

sys.path.insert(0, '.')
from app import parse_receipt_text_to_items
import json

res = parse_receipt_text_to_items(text)
print("Parse Result:")
print(json.dumps(res, indent=2, ensure_ascii=False))
