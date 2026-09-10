import os
import sys
import io

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app

for fn in ["media_1789028152298.jpg", "media_1789018148055.jpg"]:
    img_path = os.path.join(r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded", fn)
    print(f"\n=================== Testing {fn} ===================")
    if not os.path.exists(img_path):
        print("File does not exist")
        continue

    with open(img_path, 'rb') as f:
        img_bytes = f.read()

    with app.test_client() as client:
        with client.session_transaction() as sess:
            sess['logged_in'] = True
            sess['username'] = 'admin'
            sess['user_id'] = 1

        resp = client.post(
            '/split-bill/ocr-upload',
            data={'file': (io.BytesIO(img_bytes), fn)},
            content_type='multipart/form-data'
        )
        print("Response status:", resp.status_code)
        import json
        data = json.loads(resp.get_data(as_text=True))
        print("Rotation applied:", data.get('rotation_applied'))
        print("Parsed data:", data.get('data'))

