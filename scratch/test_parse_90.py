import os
import sys
import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)
from app import parse_receipt_text_to_items

engine = RapidOCR()
img_path = r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg"

pil_img = Image.open(img_path).convert('RGB')
rotated = pil_img.rotate(90, expand=True)

res, _ = engine(np.array(rotated))

# Group boxes into lines
blocks = []
for box, text, score in res:
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
print("=== MERGED LINES AT 90° ===")
print(raw_text)

parsed = parse_receipt_text_to_items(raw_text)
print("\n=== PARSED RESULT ===")
print(parsed)
