import re

test_lines = [
    "¥t Pap Mocha 4 .95",
    "Vt Pap Mocha 4.95",
    "Vt Pap Mocha $4.95",
    "Vt Pap Mocha $ 4.95",
    "Vt Pap Mocha 4.95 :",
    "Vt Pap Mocha 4.95 B",
    "Vt Pap Mocha 4.95 .",
    "1 Hot Latte RM 12.00",
    "Cake (Slice) 15.50 -",
    "Subtotal - $4 ,9",
    "Total . $4.95",
    "1912003. Draper: 2. Reg: 2",
    "SAUX Card x3228 New Balance: 37.48 :"
]

exclude_patterns = [
    r'\b(?:subtotal|sub-total)\b',
    r'\b(?:total|grand total|net total|amount due)\b',
    r'\b(?:tax|sst|gst|service charge|svc charge|svc fee|service tax)\b',
    r'\b(?:cash|change|change due|rounding|round adj)\b',
    r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|sbux|saux)\b',
    r'\b(?:balance|new balance|prev balance)\b',
    r'\b(?:invoice|receipt|bill\s*no|table|date|tel|phone|drawer|draper|reg|cashier|server|chk|check\s*closed)\b',
    r'\b(?:terminal|merchant|auth|approval|ref|tng|grabpay|boost|alipay|wechat)\b',
    r'\b(?:items?\s*count|item\s*count|total\s*qty|qty\s*total)\b',
    r'^[x*\-_=+#\s\d]+$',
    r'\b[x*]{4,}\b'
]

for line in test_lines:
    clean_line = re.sub(r'(\d+)\s*[.,]\s*(\d+)', r'\1.\2', line)
    clean_line = re.sub(r'¥([A-Za-z])', r'V\1', clean_line)
    lower = clean_line.lower()

    if any(re.search(pat, lower) for pat in exclude_patterns):
        print(f"EXCLUDED: {line}")
        continue

    m_item = re.search(r'^(.*?)(?:(?:RM|MYR|\$|€|£|¥)\s*([0-9]+(?:\.[0-9]{1,2})?)|(?<=\s)([0-9]+\.[0-9]{1,2}))\s*[:*#BTA-Za-z"?\.\-\s]*$', clean_line, re.IGNORECASE)
    if m_item:
        name_raw = m_item.group(1).strip(' -:\t#$*¥“"\'|.,;')
        name_raw = re.sub(r'^[^\w\u4e00-\u9fa5]+', '', name_raw).strip()
        price_str = m_item.group(2) or m_item.group(3)
        price_val = float(price_str) if price_str else 0.0
        print(f"ITEM: '{name_raw}' -> {price_val}")
    else:
        print(f"NOT MATCHED: {line}")
