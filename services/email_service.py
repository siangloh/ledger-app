# -*- coding: utf-8 -*-
"""
邮件服务模块 (Email Service)
支持 SMTP / TLS / SSL 协议发送邮件通知与 OTP 验证码。
若环境未配置 SMTP 主机，则优雅转为模拟模式 (Simulated / Dev mode) 并记录日志，
保障本地开发与测试流水线稳定可用。
"""

import smtplib
import ssl
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formataddr, formatdate
import logging

from core.config import get_smtp_config

logger = logging.getLogger(__name__)


def send_email(to_email: str, subject: str, html_body: str, text_body: str = "", db=None) -> tuple[bool, str]:
    """
    通用邮件发送接口。
    :param to_email: 目标接收者邮箱地址
    :param subject: 邮件主题
    :param html_body: HTML 格式邮件正文
    :param text_body: 纯文本回退正文
    :param db: 数据库连接对象 (可选，用于读取动态 SMTP 配置)
    :return: (is_success, status_or_message)
    """
    if not to_email or '@' not in to_email:
        return False, "无效的接收者邮箱地址"

    cfg = get_smtp_config(db)
    smtp_host = (cfg.get('host') or '').strip()
    smtp_port = int(cfg.get('port') or 587)
    smtp_user = (cfg.get('user') or '').strip()
    smtp_password = (cfg.get('password') or '').strip()
    use_tls = bool(cfg.get('use_tls', True))
    use_ssl = bool(cfg.get('use_ssl', False))
    sender_name = (cfg.get('sender_name') or '我的记账本').strip()

    # 未配置 SMTP 时，优雅转为模拟模式
    if not smtp_host:
        logger.info(
            "[EMAIL SERVICE] SMTP host not configured. Simulated email to <%s> | Subject: '%s'",
            to_email,
            subject
        )
        return True, "simulated"

    sender_email = smtp_user or "no-reply@ledger-app.local"

    msg = MIMEMultipart('alternative')
    msg['Subject'] = subject
    msg['From'] = formataddr((sender_name, sender_email))
    msg['To'] = to_email
    msg['Date'] = formatdate(localtime=True)

    if text_body:
        msg.attach(MIMEText(text_body, 'plain', 'utf-8'))
    if html_body:
        msg.attach(MIMEText(html_body, 'html', 'utf-8'))

    try:
        if use_ssl:
            context = ssl.create_default_context()
            with smtplib.SMTP_SSL(smtp_host, smtp_port, context=context, timeout=12) as server:
                if smtp_user and smtp_password:
                    server.login(smtp_user, smtp_password)
                server.sendmail(sender_email, [to_email], msg.as_string())
        else:
            with smtplib.SMTP(smtp_host, smtp_port, timeout=12) as server:
                if use_tls:
                    context = ssl.create_default_context()
                    server.starttls(context=context)
                if smtp_user and smtp_password:
                    server.login(smtp_user, smtp_password)
                server.sendmail(sender_email, [to_email], msg.as_string())

        logger.info("[EMAIL SERVICE] Successfully sent email to <%s> | Subject: '%s'", to_email, subject)
        return True, "sent"
    except Exception as e:
        logger.error("[EMAIL SERVICE] Failed to send email to <%s>: %s", to_email, e, exc_info=True)
        return False, str(e)


def send_otp_email(to_email: str, otp_code: str, username: str = "", expires_minutes: int = 10, db=None) -> tuple[bool, str]:
    """
    发送密码重置 6 位数字 OTP 验证码邮件。
    :param to_email: 目标邮箱
    :param otp_code: 6 位数字验证码
    :param username: 用户名（可选）
    :param expires_minutes: 有效期（分钟）
    :param db: 数据库连接对象
    :return: (is_success, status_or_message)
    """
    user_display = username.strip() if username else "尊敬的用户"
    subject = f"【我的记账本】密码重置验证码：{otp_code}"

    text_body = f"""您好，{user_display}：

您正在申请重置【我的记账本】登录密码。
您的专属一次性验证码为：

{otp_code}

该验证码将在 {expires_minutes} 分钟内有效。请勿将此验证码告知任何他人。
如非本人操作，请忽略此邮件，您的账户依然安全。

—— 我的记账本团队
"""

    html_body = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }}
  .email-container {{ max-width: 520px; margin: 0 auto; background: #ffffff; border-radius: 18px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.05); }}
  .email-header {{ background: linear-gradient(135deg, #0c1322, #1e293b); padding: 32px 28px; text-align: center; color: #ffffff; }}
  .email-logo {{ font-size: 32px; margin-bottom: 8px; }}
  .email-title {{ font-size: 20px; font-weight: 700; margin: 0; color: #f8fafc; letter-spacing: 0.5px; }}
  .email-subtitle {{ font-size: 13px; color: #94a3b8; margin-top: 6px; }}
  .email-body {{ padding: 32px 28px; }}
  .greeting {{ font-size: 15px; font-weight: 600; color: #0f172a; margin-bottom: 14px; }}
  .desc {{ font-size: 14px; line-height: 1.6; color: #475569; margin-bottom: 24px; }}
  .otp-box {{ background: linear-gradient(135deg, #f1f5f9, #e2e8f0); border: 2px dashed #cbd5e1; border-radius: 14px; padding: 22px; text-align: center; margin: 24px 0; }}
  .otp-code {{ font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 36px; font-weight: 800; letter-spacing: 8px; color: #0f172a; margin: 0; }}
  .otp-hint {{ font-size: 12.5px; color: #64748b; margin-top: 8px; }}
  .security-alert {{ background: #fffbeb; border-left: 4px solid #f59e0b; padding: 12px 16px; border-radius: 6px; font-size: 12.5px; color: #92400e; line-height: 1.5; margin: 24px 0; }}
  .email-footer {{ padding: 20px 28px; background: #f8fafc; border-top: 1px solid #e2e8f0; text-align: center; font-size: 12px; color: #94a3b8; line-height: 1.5; }}
</style>
</head>
<body>
<div class="email-container">
  <div class="email-header">
    <div class="email-logo">💰</div>
    <h1 class="email-title">我的记账本 · 安全中心</h1>
    <div class="email-subtitle">My Ledger App Security Service</div>
  </div>
  <div class="email-body">
    <div class="greeting">您好，{user_display}：</div>
    <div class="desc">
      系统收到了您为账号绑定的安全重置密码申请。请在重置验证页面输入下方的一次性验证码 (OTP) 完成密码更新：
    </div>
    <div class="otp-box">
      <div class="otp-code">{otp_code}</div>
      <div class="otp-hint">⏳ 验证码有效期为 <strong>{expires_minutes} 分钟</strong></div>
    </div>
    <div class="security-alert">
      ⚠️ <strong>安全提示：</strong> 工作人员绝不会以任何理由向您索要此验证码。如非您本人操作，请忽略本邮件，切勿将验证码泄露给他人。
    </div>
  </div>
  <div class="email-footer">
    此为系统自动发送邮件，请勿直接回复。<br>
    掌控生活收支 · 走向财务自由
  </div>
</div>
</body>
</html>
"""

    return send_email(to_email, subject, html_body, text_body, db=db)
