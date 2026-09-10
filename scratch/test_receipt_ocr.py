"""
Verification & Benchmark Suite for Split-Bill Receipt OCR
Tests:
1. Image Preprocessing with aspect-ratio awareness (narrow/long receipts vs standard).
2. End-to-End parsing accuracy across 3 distinct real-world receipt formats:
   - Sample A: Malaysian Mamak / Kopitiam receipt (Roti Canai, Teh Tarik, Nasi Lemak)
   - Sample B: Supermarket receipt (narrow, tall, barcode, multiple grocery items)
   - Sample C: Dining restaurant receipt with 10% Service Charge & 6% SST
3. Graceful degradation on unreadable/blank images.
4. Security & Isolation audit: Zero external LLM/Vision API calls.
"""

import os
import sys
import io
import time
from PIL import Image, ImageDraw, ImageFont, ImageFilter

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

# Add root project path
ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app, preprocess_receipt_image_for_ocr, parse_receipt_text_to_items, get_tesseract_cmd

print("=" * 70)
print("1. VERIFYING OCR ENGINE DISCOVERY & ISOLATION")
print("=" * 70)

tess_bin = get_tesseract_cmd()
print(f"Detected Tesseract Binary: {tess_bin or 'Not found locally (will run inside Docker on Render)'}")

# Audit app.py split_bill_ocr_upload for zero external API calls
import inspect
from app import split_bill_ocr_upload
src = inspect.getsource(split_bill_ocr_upload)
forbidden_terms = ['gemini', 'openai', 'deepseek', 'ollama', 'anthropic', 'genai', 'requests.post']
violations = [term for term in forbidden_terms if term in src.lower()]
if violations:
    print(f"FAILED: Found forbidden external API calls: {violations}")
    sys.exit(1)
else:
    print("PASS: Zero external LLM / Vision API calls detected in OCR upload endpoint.")

print("\n" + "=" * 70)
print("2. IMAGE PREPROCESSING WITH ASPECT RATIO AWARENESS")
print("=" * 70)

# Test 1: Standard aspect ratio image (e.g. 1200 x 1600, ratio 1.33)
std_img = Image.new('RGB', (1200, 1600), color=(245, 245, 240))
proc_std = preprocess_receipt_image_for_ocr(std_img)
print(f"Standard Receipt (1200x1600) -> Preprocessed: {proc_std.size}, Mode: {proc_std.mode}")
assert proc_std.size[0] <= 1800 and proc_std.size[1] <= 1800
assert proc_std.mode == 'L'

# Test 2: Ultra-long narrow supermarket receipt (e.g. 800 x 2800, ratio 3.5)
long_img = Image.new('RGB', (800, 2800), color=(250, 250, 250))
proc_long = preprocess_receipt_image_for_ocr(long_img)
print(f"Long Supermarket Receipt (800x2800, ratio 3.5) -> Preprocessed: {proc_long.size}, Mode: {proc_long.mode}")
# Width should be anchored at 1100px so vertical character resolution is preserved
assert proc_long.size[0] == 1100
assert proc_long.size[1] > 1800  # Should NOT be crushed to 1800px!
assert proc_long.size[1] <= 4000
print("PASS: Long receipt width anchored at 1100px without vertical crushing.")

print("\n" + "=" * 70)
print("3. STRUCTURED PARSING BENCHMARK ACROSS 3 RECEIPT FORMATS")
print("=" * 70)

# Format 1: Mamak / Kopitiam receipt
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

res_mamak = parse_receipt_text_to_items(mamak_text)
print("\n--- [Format 1: Mamak / Kopitiam Bill] ---")
print(f"Extracted Items Count: {len(res_mamak['items'])}")
for it in res_mamak['items']:
    print(f"  • {it['quantity']}x {it['name']} : RM {it['price']:.2f}")
print(f"Subtotal: RM {res_mamak['subtotal']:.2f}, Total: RM {res_mamak['total']:.2f}")
assert len(res_mamak['items']) == 5
assert res_mamak['total'] == 27.40
print("PASS: Format 1 parsed 100% accurately.")

# Format 2: Supermarket / Grocery receipt (long, multi-item)
supermarket_text = """
99 SPEEDMART S/B (280012-A)
PASAR MINI 99 SPEEDMART
TEL: 03-88889999
--------------------------------
DUTCH LADY MILK 1L      RM 7.90
GARDENIA TOAST BREAD    RM 3.20
JACOBS CRACKER 700G     RM 11.50
INDOMIE MI GORENG 5S    RM 5.80
COCA COLA CAN 320ML     RM 2.40
EGGS GRADE A 10S        RM 8.40
--------------------------------
ITEMS COUNT: 6
SUBTOTAL:               RM 39.20
TOTAL AMOUNT:           RM 39.20
VISA CARD:              RM 39.20
"""

res_super = parse_receipt_text_to_items(supermarket_text)
print("\n--- [Format 2: Supermarket / Grocery Receipt] ---")
print(f"Extracted Items Count: {len(res_super['items'])}")
for it in res_super['items']:
    print(f"  • {it['quantity']}x {it['name']} : RM {it['price']:.2f}")
print(f"Subtotal: RM {res_super['subtotal']:.2f}, Total: RM {res_super['total']:.2f}")
assert len(res_super['items']) == 6
assert res_super['total'] == 39.20
print("PASS: Format 2 parsed 100% accurately.")

# Format 3: Dining Restaurant with Service Charge & SST
dining_text = """
THE GRAND BISTRO KL
TAX INVOICE: INV-2026-9901
DATE: 10/09/2026 13:45
--------------------------------
WAGYU BEEF BURGER     RM 48.00
TRUFFLE FRIES         RM 22.00
SEAFOOD AGLIO OLIO    RM 38.00
MATCHA LATTE          RM 16.00
ICE LEMON TEA         RM 12.00
--------------------------------
SUBTOTAL              RM 136.00
SERVICE CHARGE (10%)  RM 13.60
SST (6%)              RM 8.98
GRAND TOTAL           RM 158.58
--------------------------------
PAID BY MYDEBIT
"""

res_dining = parse_receipt_text_to_items(dining_text)
print("\n--- [Format 3: Dining Restaurant with SVC & SST] ---")
print(f"Extracted Items Count: {len(res_dining['items'])}")
for it in res_dining['items']:
    print(f"  • {it['quantity']}x {it['name']} : RM {it['price']:.2f}")
print(f"Subtotal: RM {res_dining['subtotal']:.2f}")
print(f"Service Charge (10%): RM {res_dining['service_charge']:.2f}")
print(f"SST (6%): RM {res_dining['tax']:.2f}")
print(f"Grand Total: RM {res_dining['total']:.2f}")
assert len(res_dining['items']) == 5
assert res_dining['service_charge'] == 13.60
assert res_dining['tax'] == 8.98
assert res_dining['total'] == 158.58
print("PASS: Format 3 parsed 100% accurately including Service Charge and SST.")

print("\n" + "=" * 70)
print("4. ENDPOINT FALLBACK & DEGRADATION TEST")
print("=" * 70)

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    # Test uploading a blank solid color image (OCR returns empty text)
    blank_buf = io.BytesIO()
    Image.new('RGB', (400, 400), color=(128, 128, 128)).save(blank_buf, format='JPEG')
    blank_buf.seek(0)
    
    resp = client.post('/split-bill/ocr-upload', data={'file': (blank_buf, 'test_blank.jpg')}, content_type='multipart/form-data')
    assert resp.status_code == 200
    json_data = resp.get_json()
    print(f"Blank image response: {json_data}")
    assert json_data['ok'] is False
    assert '未识别到清晰' in json_data['message'] or '粘贴' in json_data['message']
    print("PASS: Graceful degradation returns friendly message without 500 crash.")

print("\nALL VERIFICATION TESTS COMPLETED SUCCESSFULLY!")
