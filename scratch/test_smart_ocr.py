import os
import sys
import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)
from app import parse_receipt_text_to_items

def get_orientation_stats(ocr_res, img_h):
    if not ocr_res:
        return {'horiz': 0, 'vert': 0, 'footer_bottom': 0, 'footer_top': 0}
    horiz = 0
    vert = 0
    footer_bottom = 0
    footer_top = 0
    footer_keywords = ['total', 'subtotal', 'change', 'rounding', 'duitnow', 'cash', 'card', 'thank', 'scan', 'pos', 'powered', 'feedme', 'tax', 'balance']
    
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

def cluster_and_parse(ocr_res):
    blocks = []
    for box, text, score in ocr_res:
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

    raw_text = '\n'.join(merged_lines)
    parsed = parse_receipt_text_to_items(raw_text)
    return raw_text, parsed

def smart_ocr_receipt(pil_img, engine):
    """
    智能方向自适应 OCR：
    如果图片是横置（侧向 90/270 度）或倒置（180 度），自动旋转纠正为正向。
    """
    # 1. 先在原图角度运行
    res0, _ = engine(np.array(pil_img))
    if not res0:
        return [], "", {'items': [], 'total': 0.0}
        
    stats0 = get_orientation_stats(res0, pil_img.height)
    raw_text0, parsed0 = cluster_and_parse(res0)
    
    # 判断是否已经是正常的水平正立图片
    # 如果横向文本框远多于纵向文本框，且已经解析出项目或底部关键字在下方
    if stats0['horiz'] > max(5, stats0['vert'] * 1.5) and (stats0['footer_bottom'] >= stats0['footer_top'] or len(parsed0['items']) > 0):
        return res0, raw_text0, parsed0
        
    # 如果纵向文本框多于横向文本框，说明图片是侧向拍摄（90度或270度）
    # 或者横向框虽多但倒立了（footer_top > footer_bottom * 2）
    candidates = []
    # 候选角度：如果纵向占多，测试 90 和 270；否则测试 180
    if stats0['vert'] >= stats0['horiz']:
        test_angles = [90, 270]
    else:
        test_angles = [180, 90, 270]
        
    for angle in test_angles:
        rot_img = pil_img.rotate(angle, expand=True)
        r_res, _ = engine(np.array(rot_img))
        if not r_res:
            continue
        r_stats = get_orientation_stats(r_res, rot_img.height)
        r_text, r_parsed = cluster_and_parse(r_res)
        # 评分：横向框多优先，footer在底部优先，能解析出单品和总额优先
        score = (
            (r_stats['horiz'] - r_stats['vert'] * 2) +
            (r_stats['footer_bottom'] - r_stats['footer_top']) * 6 +
            len(r_parsed['items']) * 15 +
            (20 if r_parsed['total'] > 0 else 0)
        )
        candidates.append((score, angle, r_res, r_text, r_parsed))
        
    if candidates:
        candidates.sort(key=lambda x: x[0], reverse=True)
        best = candidates[0]
        score0 = (
            (stats0['horiz'] - stats0['vert'] * 2) +
            (stats0['footer_bottom'] - stats0['footer_top']) * 6 +
            len(parsed0['items']) * 15 +
            (20 if parsed0['total'] > 0 else 0)
        )
        if best[0] > score0:
            print(f"Auto-rotated to {best[1]}° (score {best[0]} vs 0° score {score0})")
            return best[2], best[3], best[4]
            
    return res0, raw_text0, parsed0

engine = RapidOCR()
for p in [
    r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg",
    r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789018148055.jpg"
]:
    if os.path.exists(p):
        print("Testing file:", os.path.basename(p))
        im = Image.open(p).convert('RGB')
        _, text, parsed = smart_ocr_receipt(im, engine)
        print("Result total:", parsed['total'], "items count:", len(parsed['items']))
        for it in parsed['items']:
            print("  ", it['name'], it['price'])
