import requests

s = requests.Session()
r = s.get('http://localhost:5000/liabilities')
print('Direct /liabilities count:', r.text.count('<header class="app-header"'))

r_hx = s.get('http://localhost:5000/liabilities', headers={'HX-Request': 'true'})
print('HX /liabilities count:', r_hx.text.count('<header class="app-header"'))
print('HX /liabilities starts with:', r_hx.text.strip()[:60])
