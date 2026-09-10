import os
import sys
import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)
from app import parse_receipt_text_to_items

def get_boxes_stats(ocr_res, img_h):
    if not ocr_res:
        return {'horiz': 0, 'vert': 0, 'footer_at_bottom': 0, 'footer_at_top': 0, 'score': 0}
    horiz = 0
    vert = 0
    footer_bottom = 0
    footer_top = 0
    footer_keywords = ['total', 'subtotal', 'change', 'rounding', 'duitnow', 'cash', 'card', 'thank', 'scan', 'pos', 'powered', 'feedme']
    
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
            if cy > img_h * 0.5:
                footer_bottom += 1
            else:
                footer_top += 1
                
    return {
        'horiz': horiz,
        'vert': vert,
        'footer_at_bottom': footer_bottom,
        'footer_at_top': footer_top,
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

engine = RapidOCR()
img_path = r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg"
pil_img = Image.open(img_path).convert('RGB')

for angle in [0, 90, 180, 270]:
    test_img = pil_img.rotate(angle, expand=True) if angle > 0 else pil_img
    res, _ = engine(np.array(test_img))
    stats = get_boxes_stats(res, test_img.height)
    raw_text, parsed = cluster_and_parse(res)
    print(f"--- Rotation {angle}° ---")
    print(f"Stats: {stats}")
    print(f"Parsed items: {len(parsed['items'])}, Total: {parsed['total']}")
    if parsed['items']:
        print(f"Items: {[it['name'] + ': ' + str(it['price']) for it in parsed['items']]}")
