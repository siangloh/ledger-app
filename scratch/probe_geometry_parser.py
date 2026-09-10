"""Scratch probe: feed synthetic bounding-box OCR results (mimicking RapidOCR's
[box, text, score] output shape) into cluster_ocr_blocks_to_lines() +
parse_receipt_text_to_items() to find real bugs in the geometry-based row
reconstruction, before touching production code."""
import os
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_probe')
os.makedirs('/tmp/ledger_probe', exist_ok=True)

from app import cluster_ocr_blocks_to_lines, parse_receipt_text_to_items


def box(x0, y0, x1, y1):
    # RapidOCR box format: 4 corner points, clockwise from top-left
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]


def run_case(name, ocr_result):
    merged_text = cluster_ocr_blocks_to_lines(ocr_result)
    print(f"\n=== {name} ===")
    print("--- merged lines ---")
    print(merged_text)
    parsed = parse_receipt_text_to_items(merged_text)
    print("--- parsed items ---")
    for it in parsed['items']:
        print(f"  {it['quantity']}x {it['name']!r} @ {it['price']}")
    print(f"subtotal={parsed['subtotal']} total={parsed['total']}")
    return parsed


# Case 1: short vs. very long item name on separate rows, same y-scale.
# This is the exact scenario the user was worried about.
case1 = [
    (box(20, 100, 60, 120), "Tea", 0.9),
    (box(300, 100, 360, 120), "2.20", 0.9),
    (box(20, 140, 340, 160), "Nasi Lemak Ayam Goreng Special Set", 0.9),
    (box(400, 140, 460, 160), "18.90", 0.9),
]
p1 = run_case("Case 1: short name vs. very long name, same layout", case1)
assert len(p1['items']) == 2, f"expected 2 items, got {len(p1['items'])}"
names = {round(it['price'], 2): it['name'] for it in p1['items']}
assert names.get(2.20) == 'Tea', f"unexpected: {names}"
assert 'Nasi Lemak' in names.get(18.90, ''), f"unexpected: {names}"
print("PASS: long name did not break price detection.")

# Case 2: qty x unit-price x line-total all on one row (a very common real
# receipt layout this parser has NOT been explicitly tested against).
case2 = [
    (box(20, 100, 50, 120), "2", 0.9),
    (box(60, 100, 160, 120), "Roti Canai", 0.9),
    (box(250, 100, 300, 120), "1.10", 0.9),
    (box(400, 100, 460, 120), "2.20", 0.9),
]
p2 = run_case("Case 2: qty + unit price + line total on one row", case2)
assert len(p2['items']) == 1, f"expected 1 item, got {p2['items']}"
assert p2['items'][0]['name'] == 'Roti Canai', f"unit price leaked into name: {p2['items'][0]['name']!r}"
assert p2['items'][0]['price'] == 2.20, f"expected line total 2.20, got {p2['items'][0]['price']}"
assert p2['items'][0]['quantity'] == 2, f"expected qty 2, got {p2['items'][0]['quantity']}"
print("PASS: unit price no longer leaks into the item name; line total and quantity both correct.")

# Case 3: two rows close together in Y (tight line spacing) - stress-test the
# row-clustering threshold.
case3 = [
    (box(20, 100, 160, 118), "Teh Tarik", 0.9),
    (box(300, 101, 360, 119), "3.50", 0.9),
    (box(20, 122, 160, 140), "Milo Ais", 0.9),
    (box(300, 123, 360, 141), "4.20", 0.9),
]
p3 = run_case("Case 3: tightly spaced rows", case3)
assert len(p3['items']) == 2, f"expected 2 items (rows should NOT merge), got {len(p3['items'])}: {p3['items']}"
print("PASS: tightly spaced rows stayed separate.")
