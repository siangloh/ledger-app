"""
Verification for the split-bill text-parsing path only.

Receipt OCR no longer runs on the server (moved to on-device Google ML Kit in
the Android app — see prompts/11). This script only tests what's still on the
server: parse_receipt_text_to_items() and the /split-bill/parse-text endpoint,
which now serves BOTH the manual-paste UI and the ML-Kit-recognized-text flow
from the Android app. No OCR, no image processing, no Tesseract involved here.
"""

import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app, parse_receipt_text_to_items

print("=" * 70)
print("1. parse_receipt_text_to_items() on clean text (regex logic only)")
print("=" * 70)

mamak_text = """
RESTORAN ALI MAJU
NO 12 JALAN TELAWAI BANGSAR
TABLE: 14
================================
1 ROTI CANAI         RM 2.20
2 ROTI TELUR         RM 7.00
1 TEH TARIK AIS      RM 3.50
1 MILO AIS           RM 4.20
1 MEE GORENG AYAM    RM 10.50
================================
SUBTOTAL             RM 27.40
ROUNDING             RM 0.00
TOTAL                RM 27.40
CASH                 RM 50.00
CHANGE               RM 22.60
THANK YOU PLEASE COME AGAIN
"""

res = parse_receipt_text_to_items(mamak_text)
print(f"Items: {len(res['items'])}, Subtotal: RM {res['subtotal']:.2f}, Total: RM {res['total']:.2f}")
assert len(res['items']) == 5
assert res['total'] == 27.40
print("PASS")

print("\n" + "=" * 70)
print("2. /split-bill/parse-text endpoint (this is what both the web manual-paste")
print("   box AND the Android ML-Kit-recognized-text flow call)")
print("=" * 70)

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    resp = client.post('/split-bill/parse-text', data={'text': mamak_text})
    assert resp.status_code == 200
    data = resp.get_json()
    print(f"ok={data['ok']}  items={len(data['data']['items'])}  total={data['data']['total']}")
    assert data['ok'] is True
    assert len(data['data']['items']) == 5

    # Empty text should be rejected cleanly, not crash
    resp2 = client.post('/split-bill/parse-text', data={'text': ''})
    assert resp2.status_code == 400
    print("PASS: valid text parses correctly, empty text rejected with 400 (no crash).")

print("\n" + "=" * 70)
print("3. Confirm the removed server-side OCR route is actually gone")
print("=" * 70)

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1
    resp = client.post('/split-bill/ocr-upload', data={})
    print(f"/split-bill/ocr-upload -> HTTP {resp.status_code} (expect 404, route no longer exists)")
    assert resp.status_code == 404
    print("PASS: server no longer exposes an OCR upload route.")

print("\nAll checks passed. Note: this only covers the text-parsing path — actual")
print("on-device OCR accuracy (ML Kit, in the Android app) is not something this")
print("Python script can test; that needs to be verified on a real device with")
print("real photographed receipts.")
