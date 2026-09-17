import io
from PIL import Image
from services.ocr_service import get_preprocessed_receipt_preview


def test_get_preprocessed_receipt_preview_generates_valid_image():
    # Create a small dummy receipt image
    img = Image.new("RGB", (300, 400), color="white")
    proc_arr, b64, meta = get_preprocessed_receipt_preview(img, angle=90)

    assert proc_arr is not None
    assert meta["rotation"] == 90
    assert meta["width"] >= 1000 or meta["height"] >= 1000
    assert "filters" in meta
    assert len(meta["filters"]) == 4
    assert b64.startswith("data:image/jpeg;base64,")


def test_split_bill_ocr_preprocess_preview_endpoint(client):
    img = Image.new("RGB", (200, 300), color="white")
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    buf.seek(0)

    # 1. Test JSON response
    res = client.post(
        "/split-bill/ocr-preprocess-preview",
        data={"file": (buf, "test_receipt.jpg"), "angle": "0"},
        content_type="multipart/form-data"
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data["ok"] is True
    assert data["preprocessed_image"].startswith("data:image/jpeg;base64,")
    assert data["preprocessed_meta"]["rotation"] == 0
    assert data["preprocessed_meta"]["width"] > 0

    # 2. Test raw=1 JPEG response with a fresh buffer
    buf_raw = io.BytesIO()
    img.save(buf_raw, format="JPEG")
    buf_raw.seek(0)
    res_raw = client.post(
        "/split-bill/ocr-preprocess-preview?raw=1",
        data={"file": (buf_raw, "test_receipt.jpg")},
        content_type="multipart/form-data"
    )
    assert res_raw.status_code == 200
    assert res_raw.headers.get("Content-Type") == "image/jpeg"
    assert len(res_raw.data) > 0


def test_split_bill_ocr_upload_returns_preprocessed_image(client, monkeypatch):
    # Mock get_rapid_ocr in blueprints.split_bill
    def fake_engine(_arr):
        return (
            [
                [[[0, 0], [10, 0], [10, 10], [0, 10]], "TOTAL", 0.95],
                [[[0, 20], [10, 20], [10, 30], [0, 30]], "RM 25.00", 0.95],
            ],
            None,
        )

    import blueprints.split_bill as bp_mod
    monkeypatch.setattr(bp_mod, "get_rapid_ocr", lambda: fake_engine)

    img = Image.new("RGB", (300, 500), color="white")
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    buf.seek(0)

    res = client.post(
        "/split-bill/ocr-upload",
        data={"file": (buf, "receipt.jpg")},
        content_type="multipart/form-data"
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data["ok"] is True
    assert "preprocessed_image" in data
    assert data["preprocessed_image"].startswith("data:image/jpeg;base64,")
    assert "preprocessed_meta" in data
    assert data["preprocessed_meta"]["width"] > 0

