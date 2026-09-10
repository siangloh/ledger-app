"""
Verification suite for Split-Bill Receipt OCR.

HONESTY NOTE (read this before trusting any "PASS" below):
This script does NOT prove real-world OCR accuracy. Sections 1, 4 and 5 are
genuine end-to-end tests (they render text onto an actual image and send it
through the real /split-bill/ocr-upload endpoint, exercising Tesseract for
real). But rendered, computer-drawn text is still not the same as a photo of
a physical receipt — it has no blur, no skew beyond what we rotate on purpose,
no uneven lighting, no thermal-paper fading, no crumpling. A "PASS" here only
means "the OCR + parsing pipeline works end-to-end on clean synthetic text
images and the rotation-retry logic behaves correctly." It does NOT mean the
feature is verified accurate on real phone photos of real receipts — that
still requires testing with actual photographed receipts, which this script
cannot substitute for.
"""

import os
import sys
import io
import time
from PIL import Image, ImageDraw, ImageFont

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)

from app import app, preprocess_receipt_image_for_ocr, parse_receipt_text_to_items, get_tesseract_cmd


def render_receipt_image(text, width=900):
    """Render plain text onto a white image using a real font, so it can be
    sent through actual Tesseract OCR — unlike calling parse_receipt_text_to_items()
    directly on hand-typed text, which never touches the OCR engine at all."""
    lines = [l for l in text.strip('\n').split('\n')]
    font = ImageFont.load_default(size=26)
    line_height = 34
    height = line_height * len(lines) + 40
    img = Image.new('RGB', (width, height), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    y = 20
    for line in lines:
        draw.text((20, y), line, fill=(0, 0, 0), font=font)
        y += line_height
    return img


def run_ocr_via_real_endpoint(client, img, filename='receipt.jpg'):
    """Send an image through the actual HTTP endpoint (not by calling internal
    functions directly), so this exercises the exact code path a real upload
    would hit — including the rotation-retry logic, error handling, and JSON
    response shape."""
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=92)
    buf.seek(0)
    resp = client.post(
        '/split-bill/ocr-upload',
        data={'file': (buf, filename)},
        content_type='multipart/form-data'
    )
    return resp.get_json()


print("=" * 70)
print("1. OCR ENGINE DISCOVERY & ZERO-EXTERNAL-API AUDIT")
print("=" * 70)

tess_bin = get_tesseract_cmd()
print(f"Detected Tesseract Binary: {tess_bin or 'Not found locally (expected to run inside Docker on Render)'}")

import inspect
from app import split_bill_ocr_upload
src = inspect.getsource(split_bill_ocr_upload)
forbidden_terms = ['gemini', 'openai', 'deepseek', 'ollama', 'anthropic', 'genai', 'requests.post']
violations = [term for term in forbidden_terms if term in src.lower()]
if violations:
    print(f"FAILED: Found forbidden external API calls: {violations}")
    sys.exit(1)
print("PASS: Zero external LLM / Vision API calls detected in the OCR upload endpoint's source.")

print("\n" + "=" * 70)
print("2. IMAGE PREPROCESSING SIZE LOGIC (synthetic blank canvases — dimensions only, NOT an accuracy test)")
print("=" * 70)

std_img = Image.new('RGB', (1200, 1600), color=(245, 245, 240))
proc_std = preprocess_receipt_image_for_ocr(std_img)
print(f"Standard aspect ratio (1200x1600) -> Preprocessed: {proc_std.size}, Mode: {proc_std.mode}")
assert proc_std.size[0] <= 1800 and proc_std.size[1] <= 1800
assert proc_std.mode == 'L'

long_img = Image.new('RGB', (800, 2800), color=(250, 250, 250))
proc_long = preprocess_receipt_image_for_ocr(long_img)
print(f"Long/narrow aspect ratio (800x2800) -> Preprocessed: {proc_long.size}, Mode: {proc_long.mode}")
assert proc_long.size[0] == 1100
assert proc_long.size[1] > 1800
assert proc_long.size[1] <= 4000
print("PASS: resize math behaves as designed. (This proves nothing about OCR accuracy — no text was ever drawn on these images.)")

print("\n" + "=" * 70)
print("3. STRUCTURED PARSING LOGIC ON CLEAN, HAND-TYPED TEXT (regex parser only — bypasses OCR entirely)")
print("=" * 70)
print("This section tests parse_receipt_text_to_items() directly against perfectly clean text.")
print("It tells us the regex parser works on ideal input. It says nothing about how well it")
print("holds up against real OCR output, which is noisy (misread characters, dropped decimals,")
print("merged/split lines). Section 4 below is the real test.")

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
print(f"\nClean-text parse -> {len(res_mamak['items'])} items, total RM {res_mamak['total']:.2f}")
assert len(res_mamak['items']) == 5
assert res_mamak['total'] == 27.40
print("PASS (clean text only — see caveat above).")

print("\n" + "=" * 70)
print("4. REAL END-TO-END TEST: rendered image -> actual HTTP endpoint -> real Tesseract OCR -> parser")
print("=" * 70)
print("This is the first section that actually calls Tesseract. Still synthetic (computer-drawn")
print("text, not a camera photo), but it's a genuine improvement over section 3: nothing here is")
print("hand-fed to the parser — the text has to survive real OCR recognition first.")

samples = {
    'Mamak/Kopitiam': mamak_text,
    'Supermarket': """
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
SUBTOTAL:               RM 39.20
TOTAL AMOUNT:           RM 39.20
""",
    'Dining (SVC+SST)': """
THE GRAND BISTRO KL
TAX INVOICE: INV-2026-9901
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
""",
}

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    real_ocr_results = {}
    for name, text in samples.items():
        img = render_receipt_image(text)
        t0 = time.time()
        result = run_ocr_via_real_endpoint(client, img)
        elapsed = time.time() - t0
        real_ocr_results[name] = result
        item_count = len(result.get('data', {}).get('items') or []) if result.get('ok') else 0
        print(f"\n--- {name} (rendered image, real OCR pass, {elapsed:.2f}s) ---")
        print(f"  ok={result.get('ok')}  items_recognized={item_count}  total={result.get('data', {}).get('total') if result.get('ok') else 'N/A'}")
        if not result.get('ok'):
            print(f"  message={result.get('message')}")

print("\nReal-OCR summary: this only tells you whether the pipeline recognizes clean, computer-")
print("rendered receipt text end-to-end and how long that takes. It does NOT establish accuracy")
print("on real camera photos (lighting, blur, skew, thermal-paper fading, creases). Testing against")
print("actual photographed receipts is still required before trusting this feature in production.")

print("\n" + "=" * 70)
print("5. ROTATION-RETRY LOGIC: landscape-oriented photo should still recover the correct text")
print("=" * 70)

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    upright_img = render_receipt_image(mamak_text)
    sideways_img = upright_img.rotate(90, expand=True)  # simulate a landscape-held-phone photo of a portrait receipt
    t0 = time.time()
    result = run_ocr_via_real_endpoint(client, sideways_img, filename='sideways.jpg')
    elapsed = time.time() - t0
    item_count = len(result.get('data', {}).get('items') or []) if result.get('ok') else 0
    print(f"Rotated 90 degrees -> ok={result.get('ok')}  items_recognized={item_count}  time={elapsed:.2f}s")
    print("Compare this timing against section 4's upright Mamak/Kopitiam result above — the rotation")
    print("path should exit as soon as it finds a usable candidate, not always burn through all 3 angles.")

print("\n" + "=" * 70)
print("6. ENDPOINT FALLBACK ON A GENUINELY UNREADABLE IMAGE (blank canvas, no text at all)")
print("=" * 70)

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['username'] = 'admin'
        sess['user_id'] = 1

    blank_buf = io.BytesIO()
    Image.new('RGB', (400, 400), color=(128, 128, 128)).save(blank_buf, format='JPEG')
    blank_buf.seek(0)
    resp = client.post('/split-bill/ocr-upload', data={'file': (blank_buf, 'test_blank.jpg')}, content_type='multipart/form-data')
    assert resp.status_code == 200
    json_data = resp.get_json()
    print(f"Blank image response: {json_data}")
    assert json_data['ok'] is False
    print("PASS: graceful degradation, no 500 crash on unreadable input.")

print("\n" + "=" * 70)
print("SUMMARY")
print("=" * 70)
print("Sections 1, 2, 3 and 6 passed and are meaningful for what they each claim (see their headers).")
print("Sections 4 and 5 are the closest this script gets to a real accuracy test, but still use")
print("computer-rendered text, not real photographs. REAL receipt photos from an actual phone camera")
print("(different lighting, paper condition, angles) still need to be tested by a human before this")
print("feature's real-world accuracy can be considered verified.")
