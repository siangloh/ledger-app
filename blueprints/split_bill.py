import io
import logging
from datetime import datetime, date
from PIL import Image, ImageOps
from flask import Blueprint, render_template, request, redirect, url_for, flash, jsonify

from core.db import get_db, get_current_user_id, bump_data_version
from core.auth import is_ajax_request
from core.utils import money_filter, check_and_record_budget_alerts
from services.ocr_service import (
    get_rapid_ocr, smart_orient_receipt_ocr, parse_receipt_text_to_items,
    get_preprocessed_receipt_preview
)
from core.extensions import csrf

logger = logging.getLogger(__name__)

split_bill_bp = Blueprint('split_bill', __name__)


@split_bill_bp.route('/split-bill', endpoint='split_bill_page')
def split_bill_page():
    """小票拍照 AA 分账页面"""
    return render_template('split_bill.html', today=date.today().isoformat())


@split_bill_bp.route('/split-bill/parse-text', methods=['POST'], endpoint='split_bill_parse_text')
@csrf.exempt
def split_bill_parse_text():
    """纯文本小票智能解析接口"""
    text = request.form.get('text', '').strip()
    if not text:
        return jsonify({'ok': False, 'message': '未提供小票内容'}), 400

    parsed = parse_receipt_text_to_items(text)
    return jsonify({'ok': True, 'data': parsed})


@split_bill_bp.route('/split-bill/ocr-preprocess-preview', methods=['POST'], endpoint='split_bill_ocr_preprocess_preview')
@csrf.exempt
def split_bill_ocr_preprocess_preview():
    """纯图像预处理预览接口（快速返回 CLAHE+降噪+纠偏后的送审图，无需耗时推理）"""
    file = request.files.get('file') or request.files.get('receipt_image')
    if not file or not file.filename:
        return jsonify({'ok': False, 'message': '未检测到上传的小票照片'}), 400

    try:
        angle = int(request.form.get('angle', 0))
    except (ValueError, TypeError):
        angle = 0

    try:
        img_bytes = file.read()
        pil_img = Image.open(io.BytesIO(img_bytes))
        try:
            pil_img = ImageOps.exif_transpose(pil_img)
        except Exception:
            pass
        if pil_img.mode != 'RGB':
            pil_img = pil_img.convert('RGB')

        proc_arr, b64, meta = get_preprocessed_receipt_preview(pil_img, angle=angle)

        if request.args.get('raw') == '1':
            import cv2
            _, buf = cv2.imencode('.jpg', proc_arr, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
            from flask import Response
            return Response(buf.tobytes(), mimetype='image/jpeg')

        return jsonify({
            'ok': True,
            'preprocessed_image': b64,
            'preprocessed_meta': meta
        })
    except Exception as e:
        logger.error("OCR preprocessing preview failed: %s", e)
        return jsonify({'ok': False, 'message': f'预处理失败: {str(e)}'}), 500


@split_bill_bp.route('/split-bill/ocr-upload', methods=['POST'], endpoint='split_bill_ocr_upload')
@csrf.exempt
def split_bill_ocr_upload():
    """本地 RapidOCR 深度学习小票识别接口（零云端依赖，支持全方向自适应纠偏与同行对齐，并暴露预处理送审图）"""
    file = request.files.get('file') or request.files.get('receipt_image')
    if not file or not file.filename:
        return jsonify({'ok': False, 'message': '未检测到上传的小票照片'}), 400

    engine = get_rapid_ocr()
    if not engine:
        return jsonify({'ok': False, 'message': '本地 RapidOCR 引擎未安装或初始化失败'}), 500

    try:
        img_bytes = file.read()
        pil_img = Image.open(io.BytesIO(img_bytes))
        try:
            pil_img = ImageOps.exif_transpose(pil_img)
        except Exception:
            pass
        if pil_img.mode != 'RGB':
            pil_img = pil_img.convert('RGB')

        result, raw_text, parsed, rot, preprocessed_b64, prep_meta = smart_orient_receipt_ocr(pil_img, engine)
        from flask import g
        sym = getattr(g, 'current_currency_symbol', 'RM') or 'RM'
        default_parsed = {
            'items': [],
            'subtotal': 0.0,
            'total': 0.0,
            'service_charge': 0.0,
            'tax': 0.0,
            'discount': 0.0,
            'rounding': 0.0,
            'currency_symbol': sym
        }
        if not result or not raw_text:
            return jsonify({
                'ok': False,
                'message': '未能识别出文字，请确保小票清晰平整',
                'data': default_parsed,
                'raw_text': '',
                'rotation_applied': rot,
                'engine': 'rapidocr',
                'preprocessed_image': preprocessed_b64,
                'preprocessed_meta': prep_meta
            }), 200

        if not parsed:
            parsed = default_parsed

        parsed['engine'] = 'rapidocr'
        parsed['orientation_corrected'] = bool(rot != 0)
        return jsonify({
            'ok': True,
            'data': parsed,
            'raw_text': raw_text,
            'rotation_applied': rot,
            'engine': 'rapidocr',
            'preprocessed_image': preprocessed_b64,
            'preprocessed_meta': prep_meta
        })
    except Exception as e:
        logger.error("RapidOCR recognition failed: %s", e)
        return jsonify({'ok': False, 'message': f'小票识别失败: {str(e)}'}), 500


@split_bill_bp.route('/split-bill/save-record', methods=['POST'], endpoint='split_bill_save_record')
def split_bill_save_record():
    """将 AA 分账中属于自己的部分一键存入主账本"""
    f = request.form
    try:
        amount = float(f.get('amount', 0))
    except ValueError:
        amount = 0

    if amount <= 0:
        if is_ajax_request():
            return jsonify({'ok': False, 'message': '记账金额必须大于 0'}), 400
        flash('记账金额必须大于 0', 'error')
        return redirect(url_for('split_bill_page'))

    user_id = get_current_user_id()
    db = get_db()
    now = datetime.now().isoformat()
    note = f.get('note', '').strip() or '聚餐 AA 分摊消费'
    tx_date = f.get('date') or date.today().isoformat()
    category = f.get('category') or '餐饮'

    db.execute(
        'INSERT INTO transactions (user_id, date, type, group_name, category, amount, note, source, created_at) '
        'VALUES (?,?,?,?,?,?,?,?,?)',
        (user_id, tx_date, 'expense', None, category, amount, note, 'split_bill', now)
    )
    db.commit()
    bump_data_version('split_bill', {'note': note, 'amount': amount, 'category': category, 'type': 'expense', 'user_id': user_id})

    budget_alert = check_and_record_budget_alerts(db, user_id, category, tx_date[:7])
    if budget_alert and not is_ajax_request():
        flash(budget_alert['message'], 'warning' if budget_alert['threshold'] < 100 else 'error')

    msg = f'已成功记入支出：{note} {money_filter(amount)}'
    if is_ajax_request():
        return jsonify({'ok': True, 'message': msg, 'budget_alert': budget_alert})
    flash(msg, 'success')
    return redirect(url_for('records'))
