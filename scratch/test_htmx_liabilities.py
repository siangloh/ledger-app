import requests, re

s = requests.Session()
r_login = s.get('http://localhost:5000/login')
m = re.search(r'name="csrf_token" value="([^"]+)"', r_login.text)
csrf = m.group(1) if m else ''
s.post('http://localhost:5000/login', data={'username': 'admin', 'password': 'password123', 'csrf_token': csrf})

# Test with HX-Request
r = s.get('http://localhost:5000/liabilities', headers={'HX-Request': 'true'})
print("Status:", r.status_code)
print("Contains app-header:", '<header class="app-header"' in r.text)
print("Contains mobileBottomNav:", 'id="mobileBottomNav"' in r.text)
print("Starts with:", r.text.strip()[:100])
