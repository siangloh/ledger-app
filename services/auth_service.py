# -*- coding: utf-8 -*-
"""
用户认证与密码安全服务 (Auth & Security Service)
职责：
  - 用户名/邮箱规范性与密码强度校验
  - 邮箱掩码处理 (Email Masking)
  - 密码重置 OTP 令牌生命周期管理（防暴力猜解、频次限流、过期作废）
  - 密码重置业务执行
"""

import re
import uuid
import secrets
import logging
from datetime import datetime, timedelta
from werkzeug.security import generate_password_hash

from services.email_service import send_otp_email

logger = logging.getLogger(__name__)


def mask_email(email: str) -> str:
    """对邮箱地址执行脱敏掩码，如 alexander.dev@gmail.com -> al***ev@gmail.com"""
    if not email or '@' not in email:
        return email or ''
    name, domain = email.split('@', 1)
    if len(name) <= 2:
        masked_name = name[0] + '*' if name else '*'
    elif len(name) <= 4:
        masked_name = name[0] + '**' + name[-1]
    else:
        masked_name = name[:2] + '***' + name[-2:]
    return f"{masked_name}@{domain}"


def validate_password_complexity(password: str) -> tuple[bool, str]:
    """
    统一校验密码复杂度规范：
    - 至少 6 个字符
    - 包含至少一个大写字母 (A-Z)
    - 包含至少一个小写字母 (a-z)
    - 包含至少一个数字 (0-9)
    - 包含至少一个特殊符号
    """
    if not password or len(password) < 6:
        return False, "密码长度至少需要 6 个字符"
    if not re.search(r'[A-Z]', password):
        return False, "密码需包含至少一个大写字母 (A-Z)"
    if not re.search(r'[a-z]', password):
        return False, "密码需包含至少一个小写字母 (a-z)"
    if not re.search(r'[0-9]', password):
        return False, "密码需包含至少一个数字 (0-9)"
    if not re.search(r'[^a-zA-Z0-9]', password):
        return False, "密码需包含至少一个特殊符号（如 !@#$%^&* 等）"
    return True, "密码复杂度符合要求"


def request_password_reset_otp(identifier: str, db, is_dev: bool = False) -> tuple[bool, str, dict]:
    """
    申请密码重置 OTP 验证码。
    :param identifier: 用户名或绑定的注册邮箱
    :param db: 数据库连接对象
    :param is_dev: 是否为开发调试模式
    :return: (is_success, message, payload_dict)
    """
    clean_id = (identifier or '').strip()
    if not clean_id:
        return False, "请输入用户名或绑定的邮箱地址", {}

    # 查询对应用户（支持按用户名或按绑定的邮箱定位）
    user = db.execute(
        "SELECT id, username, email FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)",
        (clean_id, clean_id)
    ).fetchone()

    if not user:
        return False, "未找到该用户名或关联的邮箱账号", {}

    target_email = (user['email'] or '').strip()
    if not target_email or '@' not in target_email:
        return False, f"账号「{user['username']}」尚未绑定安全邮箱，无法通过邮件 OTP 找回密码，请联系管理员", {}

    # 频次限流保护（60秒防刷重发）
    recent_req = db.execute(
        "SELECT created_at FROM password_resets WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
        (user['id'],)
    ).fetchone()
    if recent_req and recent_req['created_at']:
        try:
            last_dt = datetime.fromisoformat(recent_req['created_at'])
            if (datetime.now() - last_dt).total_seconds() < 60:
                left_sec = int(60 - (datetime.now() - last_dt).total_seconds())
                return False, f"验证码发送过于频繁，请等待 {left_sec} 秒后再试", {}
        except Exception as e:
            logger.debug("Parse last OTP created_at skipped: %s", e)

    # 废弃此用户先前未使用的 OTP
    try:
        db.execute("UPDATE password_resets SET used = 2 WHERE user_id = ? AND used = 0", (user['id'],))
    except Exception as e:
        logger.debug("Invalidate previous OTPs skipped: %s", e)

    # 生成安全 6 位数字 OTP
    otp_code = f"{secrets.randbelow(900000) + 100000}"
    expires_dt = datetime.now() + timedelta(minutes=10)
    reset_id = str(uuid.uuid4())
    now_str = datetime.now().isoformat()

    db.execute(
        """
        INSERT INTO password_resets (id, user_id, email, otp_code, expires_at, attempts, used, created_at)
        VALUES (?, ?, ?, ?, ?, 0, 0, ?)
        """,
        (reset_id, user['id'], target_email, otp_code, expires_dt.isoformat(), now_str)
    )
    db.commit()

    # 发送 OTP 邮件
    send_ok, send_status = send_otp_email(
        target_email,
        otp_code,
        username=user['username'],
        expires_minutes=10,
        db=db
    )

    masked = mask_email(target_email)
    msg = f"验证码已成功发送至您的安全邮箱 {masked}，请在 10 分钟内输入。"

    payload = {
        'masked_email': masked,
        'email': target_email,
        'username': user['username'],
        'expires_in_seconds': 600
    }
    # 在非线上或模拟模式下，附带 otp 便于本地无 SMTP 环境极速测试
    if is_dev or send_status == 'simulated':
        payload['simulated_otp'] = otp_code
        msg += f" (本地开发测试提示: 验证码为 {otp_code})"

    return True, msg, payload


def verify_and_reset_password(identifier: str, otp_code: str, new_password: str, confirm_password: str, db) -> tuple[bool, str]:
    """
    校验 OTP 验证码并更新账户密码。
    :param identifier: 用户名或绑定的注册邮箱
    :param otp_code: 6 位数字验证码
    :param new_password: 新密码
    :param confirm_password: 确认新密码
    :param db: 数据库连接对象
    :return: (is_success, message)
    """
    clean_id = (identifier or '').strip()
    clean_otp = (otp_code or '').strip()

    if not clean_id:
        return False, "请输入用户名或绑定的邮箱地址"
    if not clean_otp or len(clean_otp) != 6:
        return False, "请输入 6 位有效数字验证码"
    if not new_password:
        return False, "请输入新密码"
    if new_password != confirm_password:
        return False, "两次输入的新密码不一致，请重新输入"

    # 密码复杂度校验
    pw_ok, pw_msg = validate_password_complexity(new_password)
    if not pw_ok:
        return False, pw_msg

    user = db.execute(
        "SELECT id, username, email FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)",
        (clean_id, clean_id)
    ).fetchone()
    if not user:
        return False, "未找到该用户账号"

    reset_record = db.execute(
        """
        SELECT id, otp_code, expires_at, attempts, used
        FROM password_resets
        WHERE user_id = ? AND used = 0
        ORDER BY created_at DESC LIMIT 1
        """,
        (user['id'],)
    ).fetchone()

    if not reset_record:
        return False, "未找到有效的重置申请记录，请重新获取验证码"

    # 防暴力枚举：超过 5 次失败直接作废该 OTP
    if reset_record['attempts'] >= 5:
        db.execute("UPDATE password_resets SET used = 2 WHERE id = ?", (reset_record['id'],))
        db.commit()
        return False, "该验证码尝试错误次数过多已作废，请重新申请获取验证码"

    # 过期判断
    try:
        exp_dt = datetime.fromisoformat(reset_record['expires_at'])
        if datetime.now() > exp_dt:
            db.execute("UPDATE password_resets SET used = 2 WHERE id = ?", (reset_record['id'],))
            db.commit()
            return False, "验证码已过期，请重新获取验证码"
    except Exception as e:
        logger.debug("Failed to parse expires_at: %s", e)

    # 验证码匹配检验
    if clean_otp != str(reset_record['otp_code']).strip():
        db.execute(
            "UPDATE password_resets SET attempts = attempts + 1 WHERE id = ?",
            (reset_record['id'],)
        )
        db.commit()
        remaining = 4 - reset_record['attempts']
        return False, f"验证码不正确（还剩 {max(0, remaining)} 次重试机会）"

    # 校验通过，重置密码
    new_hash = generate_password_hash(new_password)
    db.execute("UPDATE users SET password_hash = ? WHERE id = ?", (new_hash, user['id']))
    db.execute("UPDATE password_resets SET used = 1 WHERE id = ?", (reset_record['id'],))
    db.commit()

    logger.info("Successfully reset password for user '%s' (ID: %s) via OTP", user['username'], user['id'])
    return True, "密码已成功重置！请使用新密码进行登录。"
