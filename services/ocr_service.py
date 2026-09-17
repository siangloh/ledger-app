import re
import logging

logger = logging.getLogger(__name__)

_rapid_ocr_engine = None


def get_rapid_ocr():
    global _rapid_ocr_engine
    if _rapid_ocr_engine is None:
        try:
            from rapidocr_onnxruntime import RapidOCR
            _rapid_ocr_engine = RapidOCR(
                use_angle_cls=True,
                det_unclip_ratio=1.9,
                det_db_box_thresh=0.35
            )
            logger.info("RapidOCR engine initialized successfully with use_angle_cls=True and receipt-optimized params")
        except Exception as e:
            logger.warning("RapidOCR engine unavailable: %s", e)
            _rapid_ocr_engine = False
    return _rapid_ocr_engine if _rapid_ocr_engine is not False else None


def cluster_ocr_blocks_to_lines(ocr_result):
    """按垂直坐标与水平坐标几何对齐同一行文本（品名在左，单价在右）"""
    if not ocr_result:
        return ""
    blocks = []
    for box, text, score in ocr_result:
        cy = (box[0][1] + box[2][1]) / 2
        cx = (box[0][0] + box[1][0]) / 2
        h = abs(box[2][1] - box[0][1])
        blocks.append({'cx': cx, 'cy': cy, 'h': h, 'text': text.strip()})

    blocks.sort(key=lambda b: b['cy'])
    rows = []
    for b in blocks:
        merged = False
        for r in rows:
            avg_cy = sum(item['cy'] for item in r) / len(r)
            avg_h = sum(item['h'] for item in r) / len(r)
            if abs(b['cy'] - avg_cy) < max(12.0, avg_h * 0.7):
                r.append(b)
                merged = True
                break
        if not merged:
            rows.append([b])

    merged_lines = []
    for r in rows:
        r.sort(key=lambda item: item['cx'])
        merged_lines.append(' '.join(item['text'] for item in r))

    return '\n'.join(merged_lines)


def get_ocr_orientation_stats(ocr_res, img_h):
    """分析 OCR 识别框的长宽比与底部结算关键词位置，评估当前图片的朝向是否为正立"""
    if not ocr_res:
        return {'horiz': 0, 'vert': 0, 'footer_bottom': 0, 'footer_top': 0, 'count': 0}
    horiz = 0
    vert = 0
    footer_bottom = 0
    footer_top = 0
    footer_keywords = [
        'total', 'subtotal', 'sub-total', 'grand total', 'net total', 'change', 'rounding',
        'duitnow', 'cash', 'card', 'visa', 'mastercard', 'thank', 'scan', 'pos', 'powered',
        'feedme', 'tax', 'service', 'balance', '合计', '总计', '小计', '实收', '找零', '谢谢',
        'お会計', '合計', '합계'
    ]
    import numpy as np

    for box, text, score in ocr_res:
        w = np.linalg.norm(np.array(box[1]) - np.array(box[0]))
        h = np.linalg.norm(np.array(box[3]) - np.array(box[0]))
        cy = (box[0][1] + box[2][1]) / 2
        if w > h * 1.15:
            horiz += 1
        elif h > w * 1.15:
            vert += 1

        t_lower = text.lower()
        if any(k in t_lower for k in footer_keywords):
            if cy > img_h * 0.45:
                footer_bottom += 1
            else:
                footer_top += 1

    return {
        'horiz': horiz,
        'vert': vert,
        'footer_bottom': footer_bottom,
        'footer_top': footer_top,
        'count': len(ocr_res)
    }


def preprocess_receipt_for_ocr(pil_img):
    """
    针对热敏纸小票的轻量预处理管道：
    1. 动态自适应缩放（防止字号过小导致漏检）
    2. 灰度化 + CLAHE（限制对比度自适应直方图均衡化，消除阴影并增强文字反差）
    3. 轻度保边去噪（避免热敏纸噪点被误检为标点）
    """
    import cv2
    import numpy as np

    img = np.array(pil_img)

    if len(img.shape) == 3:
        if img.shape[2] == 4:
            img = cv2.cvtColor(img, cv2.COLOR_RGBA2BGR)
        else:
            img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
    else:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)

    h, w = img.shape[:2]

    min_side = min(h, w)
    if min_side < 1000 and min_side > 0:
        scale = 1200.0 / min_side
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced_gray = clahe.apply(gray)

    denoised = cv2.bilateralFilter(enhanced_gray, d=5, sigmaColor=50, sigmaSpace=50)
    final_img = cv2.cvtColor(denoised, cv2.COLOR_GRAY2BGR)
    return final_img


def score_receipt_orientation(ocr_res):
    """
    通过真实小票关键词 + 高置信度英文词汇打分，彻底杜绝乱码假阳性
    """
    if not ocr_res:
        return -999, 0

    ANCHOR_WORDS = [
        r'TOTAL', r'MYR', r'RM', r'SUBTOTAL', r'CHANGE',
        r'INVOICE', r'TABLE', r'DATE', r'ORDER', r'CASHIER',
        r'ROUNDING', r'ITEM', r'QTY', r'PRICE', r'TAX',
        r'RECEIPT', r'CHECK', r'AMOUNT', r'PAYMENT'
    ]

    total_score = 0
    anchor_hits = 0
    valid_word_count = 0

    for item in ocr_res:
        text = str(item[1]).strip().upper()
        confidence = float(item[2])

        if confidence < 0.6:
            continue

        for pattern in ANCHOR_WORDS:
            if re.search(r'\b' + pattern + r'\b', text) or pattern in text:
                anchor_hits += 1
                total_score += 50
                break

        if re.search(r'[A-Z]{3,}', text) or re.search(r'[\u4e00-\u9fa5]{2,}', text):
            valid_word_count += 1
            total_score += 5

        gibberish_count = len(re.findall(r'[=\|%~£§©«»_\\<>]', text))
        total_score -= gibberish_count * 15

    return total_score, anchor_hits


def parse_receipt_text_to_items(raw_text):
    """
    全球通用小票解析引擎 (Global Universal Receipt Engine)
    支持美欧、中日韩、东南亚等多国货币符号、国际数字格式、多语种税制与小费/服务费结构。
    """
    if not raw_text:
        return {'items': [], 'subtotal': 0.0, 'service_charge': 0.0, 'tax': 0.0, 'discount': 0.0, 'rounding': 0.0, 'total': 0.0, 'currency_symbol': 'RM'}

    # 1. 货币符号自适应探测
    currency_symbol = 'RM'
    if re.search(r'\b(?:RM|MYR)\b', raw_text, re.IGNORECASE):
        currency_symbol = 'RM'
    elif re.search(r'(?:S\$|\bSGD\b|GST\s*REG)', raw_text, re.IGNORECASE):
        currency_symbol = 'S$'
    elif re.search(r'(?:€|\bEUR\b|TTC|TVA|HT\b)', raw_text):
        currency_symbol = '€'
    elif re.search(r'(?:£|\bGBP\b)', raw_text):
        currency_symbol = '£'
    elif re.search(r'(?:¥|円|\bJPY\b|お会計|消費税)', raw_text):
        currency_symbol = '¥'
    elif re.search(r'(?:₩|원|\bKRW\b|결제|부가세)', raw_text):
        currency_symbol = '₩'
    elif re.search(r'(?:฿|\bTHB\b)', raw_text):
        currency_symbol = '฿'
    elif re.search(r'(?:Rp|\bIDR\b)', raw_text):
        currency_symbol = 'Rp'
    elif re.search(r'(?:₫|\bVND\b)', raw_text):
        currency_symbol = '₫'
    elif re.search(r'(?:NT\$|\bTWD\b)', raw_text):
        currency_symbol = 'NT$'
    elif re.search(r'(?:HK\$|\bHKD\b)', raw_text):
        currency_symbol = 'HK$'
    elif re.search(r'\$', raw_text):
        currency_symbol = '$'
    elif re.search(r'[\u4e00-\u9fa5]', raw_text):
        currency_symbol = '¥' if ('¥' in raw_text or '元' in raw_text or '微信' in raw_text or '支付宝' in raw_text) else 'RM'

    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]

    items = []
    subtotal = 0.0
    service_charge = 0.0
    service_rate = 0.0
    tax = 0.0
    tax_rate = 0.0
    discount = 0.0
    rounding = 0.0
    total = 0.0

    exclude_payment_patterns = [
        r'\b(?:cash|change|change\s*due|tendered|due)\b',
        r'\b(?:card|cards|visa|mastercard|amex|mydebit|debit|credit|nets|eftpos)\b',
        r'\b(?:tng|touch\s*[\'’]?n\s*go|grabpay|boost|alipay|wechat|duit\s*now|duitnow|paypay|line\s*pay|kakaopay|promptpay)\b',
        r'\b(?:carte\s*bancaire|rendu|barzahlung|kartenzahlung|r[uü]ckgeld|efectivo|cambio|contanti|resto)\b',
        r'(?:现金|找零|实收|找回|微信支付|支付宝|扫码支付|刷卡|お釣り|預り|クレジット|電子マネー|決済|현금|거스름돈|신용카드|tunai|baki|kembalian)'
    ]

    exclude_header_noise = [
        r'\b(?:invoice|receipt|bill\s*no|table|date|time|tel|phone|drawer|reg|cashier|server|chk|check\s*closed)\b',
        r'\b(?:terminal|merchant|auth|approval|ref|pax|order|order\s*#|siret|gst\s*reg|gst\s*no|co\s*no)\b',
        r'\b(?:items?\s*count|item\s*count|total\s*qty|qty\s*total|qty\s*item|price\s*\(myr\))\b',
        r'\b(?:thank\s*you|please\s*come|merci|danke|terima\s*kasih|arigato|grazia)\b',
        r'(?:单号|台号|客数|收银员|时间|品名|数量|金额|谢谢惠顾|欢迎再次光临|毎度ありがとうございます|またのお越しを|감사합니다|テーブル|人数|レジ|レシート)',
        r'^[x*\-_=+#\s\d|.:/]+$',
        r'\b[x*]{4,}\b'
    ]

    address_keywords = [
        'road', 'street', 'avenue', 'boulevard', 'jalan', 'lorong', 'lane', 'park', 'block',
        'blvd', 'ave', 'st.', 'rd.', 'singapore', 'new york', 'penang', 'paris', 'tokyo',
        '区', '路', '街', '号', '巷', '道', '市', '省'
    ]

    def normalize_numbers(raw_str):
        s = raw_str
        s = re.sub(r'(\d+),(\d{2})(?:\s*(?:€|EUR|\b))', r'\1.\2', s)
        s = re.sub(r'(\d+),(\d{3})\b', r'\1\2', s)
        s = re.sub(r'(\d+)\s*\.\s*(\d+)', r'\1.\2', s)
        s = re.sub(r'¥([A-Za-z])', r'V\1', s)
        return s

    for line in lines:
        clean_line = normalize_numbers(line)
        lower = clean_line.lower()

        is_sc_line = (any((re.search(r'\b' + re.escape(k) + r'\b', lower) is not None) if len(k) <= 3 else (k in lower) for k in ['tip', 'gratuity', 'pourboire', 'trinkgeld', 'service charge', 'svc charge', 'svc chg', 'service fee', 'sc']) or
                      any(k in clean_line for k in ['服务费', '服務費', 'お通し', '席料', '봉사료']))
        if is_sc_line and not any(k in clean_line for k in ['茶位', '调料']):
            m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
            if m_pct:
                service_rate = float(m_pct.group(1))
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                service_charge = float(amounts[-1])
            continue

        if any(k in lower for k in ['sst', 'gst', 'service tax', 'gov tax', 'sales tax', 'vat', 'tva', 'mwst', 'ust', 'iva', 'tax']) or any(k in clean_line for k in ['消费税', '消費税', '增值税', '税费', '税额', '内税', '外税', '부가세']):
            if 'total' not in lower and 'subtotal' not in lower and '合计' not in clean_line and '小计' not in clean_line and '小計' not in clean_line:
                m_pct = re.search(r'([0-9]+(?:\.[0-9]+)?)\s*%', clean_line)
                if m_pct:
                    tax_rate = float(m_pct.group(1))
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    tax = float(amounts[-1])
                continue

        if any(k in lower for k in ['rounding', 'bill rounding', 'round adj', 'rnd']) or '抹零' in clean_line or '舍入' in clean_line:
            m_rnd = re.search(r'([-+]?\s*[0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if m_rnd:
                rnd_val = float(m_rnd.group(1).replace(' ', ''))
                if abs(rnd_val) <= 1.0:
                    rounding = rnd_val
            continue

        if any(k in lower for k in ['discount', 'promo', 'voucher', 'rebate', 'remise', 'rabatt', 'descuento']) or any(k in clean_line for k in ['优惠', '折扣', '满减', '抵扣', '割引', '値引', '할인']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                discount = float(amounts[-1])
            continue

        if any(k in lower for k in ['subtotal', 'sub-total', 'total ht', 'zwischensumme', 'sous-total', 'net amount']) or any(k in clean_line for k in ['小计', '小計', '消费小计', '小 計']):
            amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
            if amounts:
                subtotal = float(amounts[-1])
            continue

        if any(k in lower for k in ['grand total', 'net total', 'total amount', 'amount due', 'total payable', 'amount payable', 'total ttc', 'gesamtbetrag', 'endbetrag', 'importe total', 'totale', 'total', 'jumlah']) or any(k in clean_line for k in ['合计', '总计', '实付', '实收', '应收', '结算', 'お会計', '合計金額', '合計', '합계', '결제금액', '총금액']):
            if 'total ht' not in lower and 'subtotal' not in lower and '消费小计' not in clean_line:
                amounts = re.findall(r'([0-9]+(?:\.[0-9]{1,2})?)\b', clean_line)
                if amounts:
                    total = float(amounts[-1])
                continue

        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_payment_patterns):
            continue
        if any(re.search(pat, clean_line, re.IGNORECASE) for pat in exclude_header_noise):
            continue

        if any(kw in lower for kw in address_keywords):
            if re.search(r'\b\d{4,6}\b\s*$', clean_line):
                continue

        if re.search(r'[0-9]+\.?[0-9]*\s*/\s*ea\b', lower):
            continue

        price_pattern = re.compile(
            r'(?:RM|MYR|\$|S\$|€|EUR|£|GBP|¥|円|₩|원|฿|Rp|₫)\s*[0-9]+(?:\.[0-9]{1,2})?'
            r'|(?<![0-9.])[0-9]+\.[0-9]{1,2}(?![0-9])',
            re.IGNORECASE
        )
        price_matches = list(price_pattern.finditer(clean_line))
        m_item = price_matches[-1] if price_matches else None
        if m_item:
            name_raw = clean_line[:m_item.start()].strip(' -:\t#$*¥€£“"\'|.,;，、\\/<>=~_+`')
            name_raw = re.sub(r'^(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'\s*(?:RM|MYR|\$|S\$|€|£|¥|円|₩)\s*$', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?(?:Takeaway|TA|Dine[- ]in)[)）]?\s*(?:\([0-9.]+/ea\))?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = re.sub(r'^[（(]?[0-9.]+/ea[)）]?\s*', '', name_raw, flags=re.IGNORECASE)
            name_raw = name_raw.strip(' -:\t#$*¥“"\'|.,;，、\\/<>=~_+`')

            num_match = re.search(r'[0-9]+(?:\.[0-9]{1,2})?', m_item.group())
            price_val = float(num_match.group()) if num_match else 0.0

            if currency_symbol in ['$', '€', '£', 'RM', 'S$'] and price_val > 5000:
                continue

            has_valid_word = bool(re.search(r'[a-zA-Z]{2,}', name_raw) or re.search(r'[\u4e00-\u9fa5\u3040-\u30ff\uac00-\ud7af]', name_raw))
            if not has_valid_word:
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

                name_raw = re.sub(
                    r'\s+(?:RM|MYR|\$|S\$|€|£|¥|円|₩)?\s*[0-9]+\.[0-9]{2}\s*$',
                    '',
                    name_raw,
                    flags=re.IGNORECASE
                ).strip(' -:\t#$*')

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


def encode_cv2_image_to_base64(cv2_img, quality=85):
    """将 OpenCV 图像数组快速压缩编码为 Base64 Data URL 字符串"""
    if cv2_img is None:
        return ""
    import cv2
    import base64
    try:
        success, enc_buf = cv2.imencode('.jpg', cv2_img, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if success:
            return "data:image/jpeg;base64," + base64.b64encode(enc_buf).decode('utf-8')
    except Exception as e:
        logger.warning("encode_cv2_image_to_base64 failed: %s", e)
    return ""


def get_preprocessed_receipt_preview(pil_img, angle=0):
    """
    仅执行图像预处理管线（自适应旋转、灰度化、CLAHE对比度均衡、双边滤波保边降噪），
    返回处理后的 numpy 数组、Base64 Data URL 字符串与处理元数据。
    用于在送入 OCR 引擎前直接预览和诊断图像处理质量。
    """
    from PIL import Image

    if angle == 90:
        target_img = pil_img.transpose(Image.Transpose.ROTATE_90)
    elif angle == 180:
        target_img = pil_img.transpose(Image.Transpose.ROTATE_180)
    elif angle == 270:
        target_img = pil_img.transpose(Image.Transpose.ROTATE_270)
    else:
        target_img = pil_img

    proc_arr = preprocess_receipt_for_ocr(target_img)
    h, w = proc_arr.shape[:2]
    meta = {
        'rotation': angle,
        'width': w,
        'height': h,
        'filters': [
            '自适应尺寸缩放 (Min 1200px)',
            '灰度化转换 (Grayscale)',
            'CLAHE 自适应局部对比度增强 (ClipLimit=2.0)',
            '双边保边滤波去噪 (BilateralFilter)'
        ]
    }
    b64 = encode_cv2_image_to_base64(proc_arr)
    return proc_arr, b64, meta


def smart_orient_receipt_ocr(pil_img, engine):
    """
    暴力 4 方向评测，杜绝任何提前退出的假阳性。
    在执行预处理（缩放、灰度化、CLAHE、去噪）后且在送入 OCR 识别文字前，
    捕获最终送审图像并返回 Base64 预览图与处理元数据。
    """
    from PIL import Image
    import numpy as np

    angle_candidates = [
        (0, pil_img),
        (270, pil_img.transpose(Image.Transpose.ROTATE_270)),
        (180, pil_img.transpose(Image.Transpose.ROTATE_180)),
        (90, pil_img.transpose(Image.Transpose.ROTATE_90))
    ]

    best_angle = 0
    best_res = None
    best_proc_arr = None
    max_score = -99999

    logger.info("========== [OCR Orientation Debugging] ==========")
    for angle, img in angle_candidates:
        proc_arr = preprocess_receipt_for_ocr(img)
        res, _ = engine(proc_arr)
        if not res:
            res, _ = engine(np.array(img.convert('RGB')))

        score, anchors = score_receipt_orientation(res)
        log_line = f"Angle {angle:3d}° -> Score: {score:5d} | Anchors Hit: {anchors} | Blocks: {len(res) if res else 0}"
        logger.info(log_line)

        if score > max_score or best_proc_arr is None:
            max_score = score
            best_angle = angle
            best_res = res
            best_proc_arr = proc_arr

        if anchors >= 2:
            confirm_line = f">> Confirmed upright angle: {angle}° with {anchors} anchors."
            logger.info(confirm_line)
            best_angle = angle
            best_res = res
            best_proc_arr = proc_arr
            break

    summary_line = f"========== Final Pick: {best_angle}° (Score: {max_score}) ==========\n"
    logger.info(summary_line)

    preprocessed_b64 = encode_cv2_image_to_base64(best_proc_arr)
    meta = {
        'rotation': best_angle,
        'width': int(best_proc_arr.shape[1]) if best_proc_arr is not None else 0,
        'height': int(best_proc_arr.shape[0]) if best_proc_arr is not None else 0,
        'filters': [
            '自适应尺寸缩放 (Min 1200px)',
            '灰度化转换 (Grayscale)',
            'CLAHE 自适应局部对比度增强 (ClipLimit=2.0)',
            '双边保边滤波降噪 (BilateralFilter)'
        ]
    }

    if not best_res:
        return [], "", {'items': [], 'total': 0.0}, best_angle, preprocessed_b64, meta

    raw_text = cluster_ocr_blocks_to_lines(best_res)
    parsed = parse_receipt_text_to_items(raw_text)

    return best_res, raw_text, parsed, best_angle, preprocessed_b64, meta
