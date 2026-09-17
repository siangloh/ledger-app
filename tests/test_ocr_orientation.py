from PIL import Image


def _fake_engine():
    """Mimics the RapidOCR callable interface: engine(np_array) -> (results, extra).
    Always returns a falsy first result, forcing smart_orient_receipt_ocr to fall
    back to `engine(np.array(img.convert('RGB')))` - the exact line that referenced
    an undefined `np` name before this module imported numpy locally."""
    calls = {"count": 0}

    def engine(_arr):
        calls["count"] += 1
        if calls["count"] % 2 == 1:
            return None, None
        return (
            [
                [[[0, 0], [10, 0], [10, 10], [0, 10]], "TOTAL", 0.95],
                [[[0, 20], [10, 20], [10, 30], [0, 30]], "RM 10.00", 0.95],
            ],
            None,
        )

    return engine, calls


def test_smart_orient_receipt_ocr_numpy_fallback_does_not_raise_nameerror(flask_app):
    """Regression test: app.py used np.array(...) inside smart_orient_receipt_ocr
    without importing numpy in that scope, which raised NameError whenever RapidOCR
    returned no results on the first (preprocessed) pass for a given rotation."""
    engine, calls = _fake_engine()
    img = Image.new("RGB", (40, 40), color="white")

    result, raw_text, parsed, angle, preprocessed_b64, meta = flask_app.smart_orient_receipt_ocr(img, engine)

    assert calls["count"] >= 2, "the numpy-array fallback branch must actually run"
    assert angle == 0
    assert result and len(result) == 2
    assert parsed is not None
    assert preprocessed_b64.startswith("data:image/jpeg;base64,")
    assert meta["width"] > 0 and meta["height"] > 0
    assert "filters" in meta


def test_clockwise_rotation_transpose_mapping():
    """Verify get_rotated_pil_image correctly maps clockwise angles to Pillow transpose."""
    from services.ocr_service import get_rotated_pil_image
    im = Image.new("RGB", (10, 20), color="white")
    im.putpixel((0, 0), (255, 0, 0))  # Top-left red pixel

    # 90° clockwise: red pixel moves from (0, 0) to top-right (19, 0)
    r90 = get_rotated_pil_image(im, 90)
    assert r90.size == (20, 10)
    assert r90.getpixel((19, 0)) == (255, 0, 0)

    # 180°: red pixel moves to bottom-right (9, 19)
    r180 = get_rotated_pil_image(im, 180)
    assert r180.size == (10, 20)
    assert r180.getpixel((9, 19)) == (255, 0, 0)

    # 270° clockwise (90° ccw): red pixel moves to bottom-left (0, 9)
    r270 = get_rotated_pil_image(im, 270)
    assert r270.size == (20, 10)
    assert r270.getpixel((0, 9)) == (255, 0, 0)


def test_spatial_layout_scoring_distinguishes_inverted_receipt():
    """Verify that an upside-down receipt (footer on top, header on bottom) receives severe negative penalty."""
    from services.ocr_service import score_receipt_orientation

    # Normal upright receipt: Header at y=100 (top), Footer at y=900 (bottom) of 1000px height
    upright_boxes = [
        [[[50, 80], [350, 80], [350, 120], [50, 120]], "INVOICE #1234 DATE: 2026-09-17", 0.95],
        [[[50, 140], [350, 140], [350, 180], [50, 180]], "ORDER CASHIER ADMIN TABLE 5", 0.95],
        [[[50, 850], [350, 850], [350, 890], [50, 890]], "TOTAL MYR 58.00", 0.95],
        [[[50, 910], [350, 910], [350, 950], [50, 950]], "CHANGE 0.00 DUITNOW PAID", 0.95],
    ]
    upright_score, anchors, h_ratio, f_top, h_btm = score_receipt_orientation(upright_boxes, img_h=1000, return_details=True)

    # Inverted (180° upside-down) receipt: Footer at y=100 (top), Header at y=900 (bottom)
    inverted_boxes = [
        [[[50, 80], [350, 80], [350, 120], [50, 120]], "TOTAL MYR 58.00", 0.95],
        [[[50, 140], [350, 140], [350, 180], [50, 180]], "CHANGE 0.00 DUITNOW PAID", 0.95],
        [[[50, 850], [350, 850], [350, 890], [50, 890]], "INVOICE #1234 DATE: 2026-09-17", 0.95],
        [[[50, 910], [350, 910], [350, 950], [50, 950]], "ORDER CASHIER ADMIN TABLE 5", 0.95],
    ]
    inverted_score, inv_anchors, inv_h_ratio, inv_f_top, inv_h_btm = score_receipt_orientation(inverted_boxes, img_h=1000, return_details=True)

    assert upright_score > 0, "Upright receipt should have positive score"
    assert inverted_score < 0, "Inverted receipt should have negative score due to spatial penalties"
    assert upright_score > inverted_score + 500, "Upright must decisively beat inverted layout"
    assert f_top == 0 and h_btm == 0
    assert inv_f_top > 0 and inv_h_btm > 0


def test_smart_orient_forced_angle():
    """Verify that forced_angle bypasses orientation detection and applies specified angle."""
    from services.ocr_service import smart_orient_receipt_ocr
    engine, _ = _fake_engine()
    img = Image.new("RGB", (40, 40), color="white")

    _, _, _, angle, _, meta = smart_orient_receipt_ocr(img, engine, forced_angle=270)
    assert angle == 270
    assert meta["rotation"] == 270


