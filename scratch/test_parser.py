"""
优化全球通用小票解析函数
"""
import re

def parse_global_receipt(text):
    if not text:
        return {'items': [], 'subtotal': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'total': 0.0, 'currency_symbol': 'RM'}

    # 1. 货币符号自适应探测
    currency_symbol = 'RM'
    if re.search(r'\b(?:RM|MYR)\b', text, re.IGNORECASE):
        currency_symbol = 'RM'
    elif re.search(r'(?:S\$|\bSGD\b|GST\s*REG)', text, re.IGNORECASE):
        currency_symbol = 'S$'
    elif re.search(r'(?:€|\bEUR\b|TTC|TVA|HT\b)', text):
        currency_symbol = '€'
    elif re.search(r'(?:£|\bGBP\b)', text):
        currency_symbol = '£'
    elif re.search(r'(?:¥|円|\bJPY\b|お会計|消費税)', text):
        currency_symbol = '¥'
    elif re.search(r'(?:₩|원|\bKRW\b|결제|부가세)', text):
        currency_symbol = '₩'
    elif re.search(r'(?:฿|\bTHB\b)', text):
        currency_symbol = '฿'
    elif re.search(r'(?:Rp|\bIDR\b)', text):
        currency_symbol = 'Rp'
    elif re.search(r'(?:₫|\bVND\b)', text):
        currency_symbol = '₫'
    elif re.search(r'(?:NT\$|\bTWD\b)', text):
        currency_symbol = 'NT$'
    elif re.search(r'(?:HK\$|\bHKD\b)', text):
        currency_symbol = 'HK$'
    elif re.search(r'\$', text):
        currency_symbol = '$'
    elif re.search(r'[\u4e00-\u9fa5]', text):
        currency_symbol = '¥' if ('¥' in text or '元' in text or '微信' in text or '支付宝' in text) else 'RM'

    lines = [line.strip() for line in text.split('\n') if line.strip()]

    items = []
    subtotal = 0.0
    service_charge = 0.0
    service_rate = 0.0
    tax = 0.0
    tax_rate = 0.0
    discount = 0.0
    rounding = 0.0
    total = 0.0

    # 常见支付与结算方式（必须排除，不能当作消费菜品）
    exclude_payment_patterns = [
        r'\b(?:cash|change|change\s*due|tendered|due)\b',
        r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|nets|eftpos)\b',
        r'\b(?:tng|touch\s*[\'’]?n\s*go|grabpay|boost|alipay|wechat|duit\s*now|duitnow|paypay|line\s*pay|kakaopay|promptpay)\b',
        r'\b(?:carte\s*bancaire|rendu|barzahlung|kartenzahlung|r[uü]ckgeld|efectivo|cambio|contanti|resto)\b',
        r'(?:现金|找零|实收|找回|微信支付|支付宝|扫码支付|刷卡|お釣り|預り|クレジット|電子マネー|決済|현금|거스름돈|신용카드|tunai|baki|kembalian)'
    ]

    # 票头、地址、邮编、流水号、桌号、问候语等非商品行
    exclude_header_noise = [
        r'\b(?:invoice|receipt|bill\s*no|table|date|time|tel|phone|drawer|reg|cashier|server|chk|check\s*closed)\b',
        r'\b(?:terminal|merchant|auth|approval|ref|pax|order|order\s*#|siret|gst\s*reg|gst\s*no|co\s*no)\b',
        r'\b(?:items?\s*count|item\s*count|total\s*qty|qty\s*total|qty\s*item|price\s*\(myr\))\b',
        r'\b(?:thank\s*you|please\s*come|merci|danke|terima\s*kasih|arigato|grazia)\b',
        r'(?:单号|台号|客数|收银员|时间|品名|数量|金额|谢谢惠顾|欢迎再次光临|毎度ありがとうございます|またのお越しを|감사합니다|テーブル|人数|レジ|レシート)',
        r'^[x*\-_=+#\s\d|.:/]+$',
        r'\b[x*]{4,}\b'
    ]

    # 地址与邮编特征 (防止将 New York NY 10010 或 Singapore 329801 误认为商品)
    address_keywords = [
        'road', 'street', 'avenue', 'boulevard', 'jalan', 'lorong', 'lane', 'park', 'block',
        'blvd', 'ave', 'st.', 'rd.', 'singapore', 'new york', 'penang', 'paris', 'tokyo',
        '区', '路', '街', '号', '巷', '道', '市', '省'
    ]

    def normalize_numbers(raw_str):
        s = raw_str
        # 欧洲逗号小数：6,40 € 或 23,55 -> 6.40, 23.55
        s = re.sub(r'(\d+),(\d{2})(?:\s*(?:€|EUR|\b))', r'\1.\2', s)
        # 常见千分位：1,480 或 1,480.00 -> 1480 或 1480.00
        s = re.sub(r'(\d+),(\d{3})\b', r'\1\2', s)
        # 异常空格小数：4 .95 -> 4.95
        s = re.sub(r'(\d+)\s*\.\s*(\d+)', r'\1.\2', s)
        return s

    for line in lines:
        clean_line = normalize_numbers(line)
        lower = clean_line.lower()

        # 1. 匹配小费与服务费 (Tip, Gratuity, Service Charge, Svc Chg, SC, 服务费)
        # 排除包含“茶位/调料”且带有数量的单品行（如“自助调料+茶位 3 ¥ 24.00”应是商品）
        is_sc_line = (any(k in lower for k in ['tip', 'gratuity', 'pourboire', 'trinkgeld', 'service charge', 'svc charge', 'svc chg', 'service fee', 'sc']) or
                      any(k in clean_line for k in ['服务费', '服務費', 'お通し', '席料', '봉사료']))
        if is_sc_line and not any(k in clean_line for k in ['茶位', '调料']):
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                service_charge = float(amounts[-1])
            continue

        # 2. 匹配政府税 / 增值税 / VAT / GST / SST / TVA / MwSt / IVA / 消费税 / 부가세
        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'sales tax', 'vat', 'tva', 'mwst', 'ust', 'iva', 'tax']) or any(k in clean_line for k in ['消费税', '消費税', '增值税', '税费', '税额', '内税', '外税', '부가세']):
            if 'total' not in lower and 'subtotal' not in lower and '合计' not in clean_line and '小计' not in clean_line and '小計' not in clean_line:
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    tax = float(amounts[-1])
                continue

        # 3. 匹配抹零与舍入 (Rounding, Bill Rounding, Rnd, 抹零)
        if any(k in lower for k in ['rounding', 'bill rounding', 'round adj', 'rnd']) or '抹零' in clean_line or '舍入' in clean_line:
            m_rnd = re.search(r'([-+]?\s*[0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if m_rnd:
                rounding = float(m_rnd.group(1).replace(' ', ''))
            continue

        # 4. 匹配优惠与折扣 (Discount, Promo, Voucher, Rebate, 优惠, 折扣, 满减, 割引, 할인)
        if any(k in lower for k in ['discount', 'promo', 'voucher', 'rebate', 'remise', 'rabatt', 'descuento']) or any(k in clean_line for k in ['优惠', '折扣', '满减', '抵扣', '割引', '値引', '할인']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                discount = float(amounts[-1])
            continue

        # 5. 匹配小计 Subtotal / Total HT / Zwischensumme / 小计 / 小計 / 消费小计
        if any(k in lower for k in ['subtotal', 'sub-total', 'total ht', 'zwischensumme', 'sous-total', 'net amount']) or any(k in clean_line for k in ['小计', '小計', '消费小计', '小 計']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                subtotal = float(amounts[-1])
            continue

        # 6. 匹配总金额 Total / Grand Total / Total TTC / Gesamtbetrag / 合计 / 总计 / 实付 / お会計 / 合計金額 / 결제금액 / Jumlah
        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'amount due', 'total payable', 'amount payable', 'total ttc', 'gesamtbetrag', 'endbetrag', 'importe total', 'totale', 'total', 'jumlah']) or any(k in clean_line for k in ['合计', '总计', '实付', '实收', '应收', '结算', 'お会計', '合計金額', '合計', '합계', '결제금액', '총금액']):
            if 'total ht' not in lower and 'subtotal' not in lower and '消费小计' not in clean_line:
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    total = float(amounts[-1])
                continue

        # 7. 排除支付方式行与噪声行
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_payment_patterns):
            continue
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_header_noise):
            continue

        # 8. 排除地址行中的邮编识别 (如 NY 10010, Singapore 329801)
        if any(kw in lower for kw in address_keywords):
            # 地址行若结尾为 4~6 位纯数字邮编，坚决跳过
            if re.search(r'\b\d{4,6}\b\s*$', clean_line):
                continue

        # 9. 提取常规单品行
        m_item = re.search(
            r'^(.*?)(?:(?:RM|MYR|\$|S\$|€|EUR|£|GBP|¥|円|₩|원|฿|Rp|₫)\s*([0-9]+(?:\.[0-9]{1,2})?)|(?<=\s)([0-9]+(?:\.[0-9]{1,2})?))\s*(?:€|EUR|円|¥|원|฿|Rp|₫|B|TA|Takeaway|\(?\d+\.?\d*/ea\)?|[#*.,;:\-\s])*$',
            clean_line,
            re.IGNORECASE
        )
        if m_item:
            name_raw = m_item.group(1).strip(' -:\t#$*¥€£“"\'|.,;')
            name_raw = re.sub(r'^(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'\s*(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*$', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?(?:Takeaway|TA|Dine[- ]in)[)）]?\s*(?:\([0-9.]+/ea\))?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?[0-9.]+/ea[)）]?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = name_raw.strip(' -:\t#$*¥“"\'|.,;')
            
            price_str = m_item.group(2) or m_item.group(3)
            price_val = float(price_str) if price_str else 0.0

            # 过滤非商品的邮编或过大非单品数字 (非 JPY/KRW/VND/IDR 币种时，单品价格通常不会超过 5000)
            if currency_symbol in ['$', '€', '£', 'RM', 'S$'] and price_val > 5000:
                continue

            if name_raw and len(name_raw) >= 2 and price_val > 0:
                qty = 1
                m_qty_prefix = re.match(r'^(\d+)\s*[xX*]?\s+(.*)$', name_raw)
                m_qty_suffix = re.search(r'^(.*?)\s+(\d+)\s*$', name_raw)
                if m_qty_prefix:
                    qty = int(m_qty_prefix.group(1))
                    name_raw = m_qty_prefix.group(2).strip(' -:\t#$*')
                elif m_qty_suffix and len(m_qty_suffix.group(1)) >= 2:
                    qty = int(m_qty_suffix.group(2))
                    name_raw = m_qty_suffix.group(1).strip(' -:\t#$*')

                items.append({
                    'name': name_raw,
                    'price': price_val,
                    'quantity': qty
                })

    # 若未找到 subtotal，则从 items 求和
    calc_subtotal = sum(i['price'] for i in items)
    if subtotal == 0:
        subtotal = round(calc_subtotal, 2)

    # 比例换算
    if service_charge == 0 and service_rate > 0 and subtotal > 0:
        service_charge = round(subtotal * (service_rate / 100), 2)
    if tax == 0 and tax_rate > 0 and subtotal > 0:
        tax = round((subtotal + service_charge) * (tax_rate / 100), 2)

    if total == 0:
        total = round(subtotal - discount + service_charge + tax + rounding, 2)

    return {
        'items': items,
        'subtotal': subtotal,
        'service_charge': service_charge,
        'tax': tax,
        'discount': discount,
        'rounding': rounding,
        'total': total,
        'currency_symbol': currency_symbol
    }
