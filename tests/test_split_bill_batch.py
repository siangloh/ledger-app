import io
from unittest.mock import patch
from PIL import Image
from services.ocr_service import (
    merge_multi_receipt_parsed_data,
    batch_smart_orient_receipt_ocr,
    smart_orient_receipt_ocr,
)


def test_merge_multi_receipt_parsed_data_empty():
    res = merge_multi_receipt_parsed_data([])
    assert res["items"] == []
    assert res["subtotal"] == 0.0
    assert res["total"] == 0.0


def test_merge_multi_receipt_parsed_data_single():
    single_res = [{
        "ok": True,
        "raw_text": "Lemon Chicken 15.00\nTotal 15.00",
        "data": {
            "items": [{"name": "Lemon Chicken", "price": 15.0}],
            "subtotal": 15.0,
            "service_charge": 0.0,
            "tax": 0.0,
            "rounding": 0.0,
            "total": 15.0,
            "currency_symbol": "RM"
        }
    }]
    res = merge_multi_receipt_parsed_data(single_res)
    assert len(res["items"]) == 1
    # Single receipt does not force prefix if only 1 receipt
    assert res["items"][0]["name"] == "Lemon Chicken"
    assert res["total"] == 15.0


def test_merge_multi_receipt_parsed_data_multiple():
    multi_res = [
        {
            "ok": True,
            "raw_text": "Meal 1:\nBurger 20.00\nTotal 20.00",
            "data": {
                "items": [{"name": "Burger", "price": 20.0}],
                "subtotal": 20.0,
                "service_charge": 2.0,
                "tax": 1.2,
                "rounding": 0.0,
                "total": 23.2,
                "currency_symbol": "RM"
            }
        },
        {
            "ok": True,
            "raw_text": "Meal 2:\nPizza 35.00\nCoke 5.00\nTotal 40.00",
            "data": {
                "items": [
                    {"name": "Pizza", "price": 35.0},
                    {"name": "Coke", "price": 5.0}
                ],
                "subtotal": 40.0,
                "service_charge": 0.0,
                "tax": 2.4,
                "rounding": -0.05,
                "total": 42.35,
                "currency_symbol": "RM"
            }
        }
    ]
    res = merge_multi_receipt_parsed_data(multi_res)
    assert len(res["items"]) == 3
    assert res["items"][0]["name"] == "[小票 1] Burger"
    assert res["items"][0]["receipt_index"] == 1
    assert res["items"][1]["name"] == "[小票 2] Pizza"
    assert res["items"][2]["name"] == "[小票 2] Coke"
    assert res["subtotal"] == 60.0
    assert res["service_charge"] == 2.0
    assert res["tax"] == 3.6
    assert res["rounding"] == -0.05
    assert res["total"] == 65.55
    assert "Meal 1:" in res["raw_text"]
    assert "Meal 2:" in res["raw_text"]


def test_smart_orient_early_exit_speedup():
    """Verify that when 0° orientation has high confidence and valid items/total,
    smart_orient_receipt_ocr early-exits without evaluating angles 90, 180, 270."""
    eval_angles = []

    def mock_engine(_arr):
        eval_angles.append(len(eval_angles))
        return (
            [
                [[[10, 20], [100, 20], [100, 40], [10, 40]], "RESTAURANT ABC", 0.98],
                [[[10, 60], [100, 60], [100, 80], [10, 80]], "CHICKEN RICE 12.00", 0.98],
                [[[10, 100], [100, 100], [100, 120], [10, 120]], "ICED TEA 3.50", 0.98],
                [[[10, 200], [100, 200], [100, 220], [10, 220]], "TOTAL 15.50", 0.98],
            ],
            None
        )

    img = Image.new("RGB", (300, 600), color="white")
    res, raw_text, parsed, angle, _, _ = smart_orient_receipt_ocr(img, mock_engine)

    assert angle == 0
    assert parsed and len(parsed["items"]) >= 2
    assert parsed["total"] == 15.50
    # Crucial speedup assertion: engine should only have been called for angle 0
    # (1 or 2 calls for 0° depending on fallback), NOT for 90, 180, 270 (which would be 4-8 calls).
    assert len(eval_angles) <= 2, f"Expected early exit on angle 0, got {len(eval_angles)} calls"


def test_batch_smart_orient_receipt_ocr_parallel():
    """Verify batch_smart_orient_receipt_ocr processes multiple images concurrently."""
    def mock_engine(_arr):
        return (
            [
                [[[10, 20], [100, 20], [100, 40], [10, 40]], "TOTAL 10.00", 0.95],
                [[[10, 60], [100, 60], [100, 80], [10, 80]], "ITEM A 10.00", 0.95],
            ],
            None
        )

    img1 = Image.new("RGB", (100, 100), color="white")
    img2 = Image.new("RGB", (100, 100), color="white")

    results = batch_smart_orient_receipt_ocr([img1, img2], mock_engine, max_workers=2)
    assert len(results) == 2
    assert results[0]["ok"] is True
    assert results[1]["ok"] is True
    assert results[0]["data"]["total"] == 10.00


def test_split_bill_ocr_upload_batch_api(logged_in_client):
    """Test /split-bill/ocr-upload-batch endpoint with multiple file uploads."""
    # Test 1: No files provided -> 400
    res = logged_in_client.post('/split-bill/ocr-upload-batch', data={})
    assert res.status_code == 400

    # Test 2: Upload multiple dummy images with mocked OCR engine
    dummy_img_io = io.BytesIO()
    Image.new('RGB', (80, 80), color='white').save(dummy_img_io, format='JPEG')
    dummy_bytes = dummy_img_io.getvalue()

    mock_batch_results = [
        {
            "ok": True,
            "raw_text": "Receipt 1\nFood A 10.00\nTotal 10.00",
            "data": {
                "items": [{"name": "Food A", "price": 10.0}],
                "subtotal": 10.0,
                "service_charge": 0.0,
                "tax": 0.0,
                "rounding": 0.0,
                "total": 10.0,
                "currency_symbol": "RM"
            },
            "rotation_applied": 0
        },
        {
            "ok": True,
            "raw_text": "Receipt 2\nDrink B 5.00\nTotal 5.00",
            "data": {
                "items": [{"name": "Drink B", "price": 5.0}],
                "subtotal": 5.0,
                "service_charge": 0.0,
                "tax": 0.0,
                "rounding": 0.0,
                "total": 5.0,
                "currency_symbol": "RM"
            },
            "rotation_applied": 0
        }
    ]

    with patch('blueprints.split_bill.get_rapid_ocr', return_value=lambda arr: (None, None)):
        with patch('blueprints.split_bill.batch_smart_orient_receipt_ocr', return_value=mock_batch_results):
            data = {
                'files': [
                    (io.BytesIO(dummy_bytes), 'r1.jpg'),
                    (io.BytesIO(dummy_bytes), 'r2.jpg')
                ]
            }
            res = logged_in_client.post(
                '/split-bill/ocr-upload-batch',
                data=data,
                content_type='multipart/form-data'
            )
            assert res.status_code == 200
            json_data = res.get_json()
            assert json_data['ok'] is True
            assert json_data['receipt_count'] == 2
            assert len(json_data['data']['items']) == 2
            assert json_data['data']['total'] == 15.0
            assert json_data['data']['items'][0]['name'] == '[小票 1] Food A'
            assert json_data['data']['items'][1]['name'] == '[小票 2] Drink B'
