import os
import sys

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app

starbucks_text = """STARBUCKS Store #19208
“2 11302 Euclid Avenue
Cleveland, OH (216) 229-U7 物
| CHK 664290
120772014 06:43 PM
1912003. Draper: 2. Reg: 2
¥t Pap Mocha 4 .95
Sbux Card 4.95
AXXXXXXANKAXGZ28
Subtotal - $4 ,9
Total . $4.95
12/07/2014 06:43 PM
SAUX Card x3228 New Balance: 37.48 :
Card is registered."""

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    resp = client.post('/split-bill/parse-text', data={'text': starbucks_text})
    print("Status:", resp.status_code)
    data = resp.get_json()
    print("Response JSON:", data)
    assert resp.status_code == 200
    assert data['ok'] is True
    items = data['data']['items']
    print(f"Parsed items ({len(items)}): {items}")
    assert len(items) == 1
    assert items[0]['price'] == 4.95
    assert data['data']['total'] == 4.95
    print("PASS: Starbucks receipt parsed successfully with 1 item and correct total!")
