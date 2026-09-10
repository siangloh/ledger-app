import os
import sys
import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)
from scratch.test_smart_ocr import smart_ocr_receipt

img_path = r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg"
im = Image.open(img_path).convert('RGB')
upright = im.rotate(90, expand=True)

engine = RapidOCR()
_, text, parsed = smart_ocr_receipt(upright, engine)
print("Upright test result:")
print("Items:", [(it['name'], it['price']) for it in parsed['items']])
print("Total:", parsed['total'])
