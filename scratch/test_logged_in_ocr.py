import requests, re

s = requests.Session()
r_login = s.get('http://localhost:5000/login')
m = re.search(r'name="csrf_token" value="([^"]+)"', r_login.text)
csrf = m.group(1) if m else ''
resp = s.post('http://localhost:5000/login', data={'username': 'admin', 'password': 'password123', 'csrf_token': csrf}, allow_redirects=True)
print('Login URL:', resp.url)

with open(r'C:\Users\USER\.gemini\antigravity-ide\brain\74a50b90-c066-4827-885c-1475323da909\.user_uploaded\media_1789028152298.jpg', 'rb') as f:
    r = s.post('http://localhost:5000/split-bill/ocr-upload', files={'file': ('receipt.jpg', f, 'image/jpeg')})
    print('OCR status:', r.status_code)
    try:
        data = r.json()
        print('Items:', [(i['name'], i['price']) for i in data.get('data', {}).get('items', [])])
        print('Total:', data.get('data', {}).get('total'))
    except Exception as e:
        print('JSON decode error:', e, r.text[:200])
