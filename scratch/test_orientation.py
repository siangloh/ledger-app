import os
import sys
import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

engine = RapidOCR()
img_path = r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg"

print("Image exists:", os.path.exists(img_path))
pil_img = Image.open(img_path).convert('RGB')
print("Image size (w, h):", pil_img.size)

for angle in [0, 90, 180, 270]:
    rotated = pil_img.rotate(angle, expand=True) if angle > 0 else pil_img
    res, _ = engine(np.array(rotated))
    if not res:
        print(f"Angle {angle}: No result")
        continue
    
    # Calculate box width vs box height
    h_boxes = 0
    v_boxes = 0
    avg_score = sum(item[2] for item in res) / len(res)
    for box, text, score in res:
        # box is 4 points: [[x0, y0], [x1, y1], [x2, y2], [x3, y3]]
        # width approx abs(box[1][0] - box[0][0])
        # height approx abs(box[2][1] - box[1][1])
        w = np.linalg.norm(np.array(box[1]) - np.array(box[0]))
        h = np.linalg.norm(np.array(box[3]) - np.array(box[0]))
        if w > h * 1.2:
            h_boxes += 1
        elif h > w * 1.2:
            v_boxes += 1
            
    print(f"Angle {angle}°: {len(res)} boxes | Horiz: {h_boxes} | Vert: {v_boxes} | Avg score: {avg_score:.3f}")
    sample_texts = [item[1] for item in res[:5]]
    print(f"   Samples: {sample_texts}")
