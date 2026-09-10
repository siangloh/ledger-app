import os
import sys
import io

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app

img_path = r"C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789018148055.jpg"
print("Checking file exists:", os.path.exists(img_path))
print("File size:", os.path.getsize(img_path))

with open(img_path, 'rb') as f:
    img_bytes = f.read()

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    resp = client.post(
        '/split-bill/ocr-upload',
        data={'file': (io.BytesIO(img_bytes), 'receipt.jpg')},
        content_type='multipart/form-data'
    )
    print("Response status:", resp.status_code)
    print("Response headers:", dict(resp.headers))
    print("Response text:", resp.get_data(as_text=True)[:500])
