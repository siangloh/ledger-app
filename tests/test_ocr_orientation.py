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

