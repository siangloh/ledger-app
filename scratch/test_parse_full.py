import re

text = """STARBUCKS Store #19208
“2 11302 Euclid Avenue
Cleveland, OH (216) 229-U7 物
| CHK 664290
120772014 06:43 PM
1912003. Draper: 2. Reg: 2
¥t Pap Mocha 4 .95
Sbux Card 4.95
AXXXXXXANKAXGZ28
Subtotal - $4 ,9
Total . $4.95
12/07/2014 06:43 PM
SAUX Card x3228 New Balance: 37.48 :
Card is registered."""

def parse_receipt_improved(raw_text):
    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
    items = []
    subtotal = 0.0
    service_charge = 0.0
    service_rate = 0.0
    tax = 0.0
    tax_rate = 0.0
    total = 0.0

    exclude_patterns = [
        r'\b(?:subtotal|sub-total)\b',
        r'\b(?:total|grand total|net total|amount due)\b',
        r'\b(?:tax|sst|gst|service charge|svc charge|svc fee|service tax)\b',
        r'\b(?:cash|change|change due|rounding|round adj)\b',
        r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|sbux)\b',
        r'\b(?:balance|new balance|prev balance)\b',
        r'\b(?:invoice|receipt|bill no|table|date|tel|phone|drawer|reg:|chk |check closed)\b',
        r'\b(?:terminal|merchant|auth|approval|ref:|tng|grabpay|boost|alipay|wechat)\b',
        r'^[x*\-_=+#\s\d]+$',
        r'\b[x*]{4,}\b'
    ]

    for line in lines:
        # 1. 规范化数字中的空格和逗号: e.g. "4 .95" -> "4.95", "4 , 95" -> "4.95", "4,95" -> "4.95"
        clean_line = re.sub(r'(\d+)\s*[.,]\s*(\d+)', r'\1.\2', line)
        lower = clean_line.lower()

        # 匹配服务费 Service Charge / SVC
        if any(k in lower for k in ['service charge', 'svc charge', 'svc chg', 'service fee']):
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            amounts = re.findall(r'([0-9]+\.[0-9]{1,2})\b', clean_line)
            if amounts:
                service_charge = float(amounts[-1])
            continue

        # 匹配政府税 / SST / GST / TAX
        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'tax']):
            if 'total' not in lower and 'subtotal' not in lower:
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                amounts = re.findall(r'([0-9]+\.[0-9]{1,2})\b', clean_line)
                if amounts:
                    tax = float(amounts[-1])
                continue

        # 匹配小计 Subtotal
        if 'subtotal' in lower or 'sub-total' in lower:
            amounts = re.findall(r'([0-9]+\.[0-9]{1,2})\b', clean_line)
            if amounts:
                subtotal = float(amounts[-1])
            continue

        # 匹配总计 Total
        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'total', 'amount due']):
            amounts = re.findall(r'([0-9]+\.[0-9]{1,2})\b', clean_line)
            if amounts:
                total = float(amounts[-1])
            continue

        # 排除干扰行
        if any(re.search(pat, lower) for pat in exclude_patterns):
            continue

        # 提取商品行:
        # 支持: "Item Name 12.50", "Item Name RM12.50", "Item Name $4.95", "1x Item Name 4.95", "¥t Pap Mocha 4.95"
        # 允许行尾可能附带杂散符号 (如 " 4.95 :" 或 " 4.95 B" 等税标志)
        m_item = re.search(r'^(.*?)(?:RM|MYR|\$|€|£|¥)?\s*([0-9]+\.[0-9]{1,2})\s*[:*#BTA-Za-z]?\s*$', clean_line, re.IGNORECASE)
        if m_item:
            name_raw = m_item.group(1).strip(' -:\t#$*¥“"\'|.,;')
            price_val = float(m_item.group(2))
            # 去掉开头残留的特殊符号
            name_raw = re.sub(r'^[^\w\u4e00-\u9fa5]+', '', name_raw).strip()
            # 如果以单字母或缩写开头如 "t Pap Mocha" 或 "¥t Pap Mocha"
            # 过滤名称过短或纯数字
            if name_raw and len(name_raw) >= 2 and price_val > 0:
                qty = 1
                m_qty = re.match(r'^(\d+)\s*[xX*]?\s+(.*)$', name_raw)
                if m_qty:
                    qty = int(m_qty.group(1))
                    name_raw = m_qty.group(2).strip(' -:\t#$*')
                items.append({
                    'name': name_raw,
                    'price': price_val,
                    'quantity': qty
                })

    calc_subtotal = sum(i['price'] for i in items)
    if subtotal == 0:
        subtotal = round(calc_subtotal, 2)

    if service_charge == 0 and service_rate > 0 and subtotal > 0:
        service_charge = round(subtotal * (service_rate / 100), 2)
    if tax == 0 and tax_rate > 0 and subtotal > 0:
        tax = round((subtotal + service_charge) * (tax_rate / 100), 2)

    if total == 0:
        total = round(subtotal + service_charge + tax, 2)

    return {
        'items': items,
        'subtotal': subtotal,
        'service_charge': service_charge,
        'tax': tax,
        'total': total
    }

print("Improved Parse Output:")
import json
print(json.dumps(parse_receipt_improved(text), indent=2, ensure_ascii=False))
