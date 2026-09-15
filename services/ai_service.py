import json
import re
import requests
from datetime import date

from core.config import (
    GEMINI_API_KEY,
    GEMINI_FALLBACK_MODELS,
    DEEPSEEK_API_KEY,
    OPENAI_API_KEY,
    OPENAI_BASE_URL,
    OPENAI_MODEL,
    OLLAMA_URL,
    OLLAMA_MODEL,
    LLM_TIMEOUT,
    AUTO_TRACK_DEBUG_LOG
)
from core.db import get_db


def clean_and_parse_json(raw_str):
    """从 LLM 返回的文本中稳健提取并解析 JSON 对象"""
    if not raw_str or not isinstance(raw_str, str):
        return None
    s = raw_str.strip()
    if s.startswith('```'):
        lines = s.split('\n')
        if lines[0].startswith('```'):
            lines = lines[1:]
        if lines and lines[-1].startswith('```'):
            lines = lines[:-1]
        s = '\n'.join(lines).strip()
    start = s.find('{')
    end = s.rfind('}')
    if start != -1 and end != -1 and end > start:
        s = s[start:end+1]
    try:
        return json.loads(s)
    except Exception:
        return None


def call_llm_json(prompt, system_instruction=None, timeout=None):
    """
    通用多源 LLM JSON 接口：
    1. 优先 Google Gemini
    2. 次选 DeepSeek / OpenAI
    3. 次选 本地 Ollama
    4. 失败返回 None，调用方自动降级到规则引擎
    """
    t = timeout or LLM_TIMEOUT

    # 1. Google Gemini
    if GEMINI_API_KEY:
        for model in GEMINI_FALLBACK_MODELS:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
            payload = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "responseMimeType": "application/json",
                    "temperature": 0.1,
                    "maxOutputTokens": 250
                }
            }
            if system_instruction:
                payload["systemInstruction"] = {"parts": [{"text": system_instruction}]}
            try:
                resp = requests.post(url, json=payload, timeout=t)
                if resp.status_code == 200:
                    data = resp.json()
                    candidates = data.get('candidates') or []
                    if candidates:
                        part = candidates[0].get('content', {}).get('parts', [{}])[0].get('text', '')
                        parsed = clean_and_parse_json(part)
                        if parsed:
                            return parsed
                elif resp.status_code in (404, 429, 503):
                    continue
            except Exception as e:
                if AUTO_TRACK_DEBUG_LOG:
                    print(f"[LLM DEBUG] Gemini {model} error: {e}")

    # 2. DeepSeek
    if DEEPSEEK_API_KEY:
        try:
            resp = requests.post(
                "https://api.deepseek.com/chat/completions",
                headers={"Authorization": f"Bearer {DEEPSEEK_API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": "deepseek-chat",
                    "messages": [
                        *([{"role": "system", "content": system_instruction}] if system_instruction else []),
                        {"role": "user", "content": prompt}
                    ],
                    "response_format": {"type": "json_object"},
                    "temperature": 0.1
                },
                timeout=t
            )
            if resp.status_code == 200:
                content = resp.json()['choices'][0]['message']['content']
                parsed = clean_and_parse_json(content)
                if parsed:
                    return parsed
        except Exception as e:
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[LLM DEBUG] DeepSeek error: {e}")

    # 3. OpenAI
    if OPENAI_API_KEY:
        try:
            resp = requests.post(
                f"{OPENAI_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": OPENAI_MODEL,
                    "messages": [
                        *([{"role": "system", "content": system_instruction}] if system_instruction else []),
                        {"role": "user", "content": prompt}
                    ],
                    "response_format": {"type": "json_object"},
                    "temperature": 0.1
                },
                timeout=t
            )
            if resp.status_code == 200:
                content = resp.json()['choices'][0]['message']['content']
                parsed = clean_and_parse_json(content)
                if parsed:
                    return parsed
        except Exception as e:
            if AUTO_TRACK_DEBUG_LOG:
                print(f"[LLM DEBUG] OpenAI error: {e}")

    # 4. Local Ollama
    if OLLAMA_URL:
        try:
            resp = requests.post(
                f"{OLLAMA_URL}/api/generate",
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": (f"{system_instruction}\n\n{prompt}") if system_instruction else prompt,
                    "stream": False,
                    "format": "json"
                },
                timeout=t
            )
            if resp.status_code == 200:
                raw_response = resp.json().get('response', '{}')
                parsed = clean_and_parse_json(raw_response)
                if parsed:
                    return parsed
        except Exception:
            pass

    return None


def get_llm_learning_samples_prompt(user_id=None, limit=12):
    """从 llm_learning_samples 数据库读取样本，构造提供给 LLM 提示词的动态参考案例库"""
    try:
        db = get_db()
        rows = db.execute('''
            SELECT text, label_type, is_real_transaction, sample_amount, sample_merchant, sample_category, notes
            FROM llm_learning_samples
            ORDER BY id ASC LIMIT ?
        ''', (limit,)).fetchall()
        if not rows:
            return ""

        lines = [
            "[LEARNING SAMPLES & REFERENCE DATASHEET / 语言学习样本库与判定示范]:",
            "Refer closely to the following labeled real-world samples when evaluating notifications:"
        ]
        for idx, r in enumerate(rows, 1):
            is_real = bool(r['is_real_transaction'])
            tx_type = None
            if is_real:
                tx_type = 'income' if 'income' in r['label_type'] else 'expense'
            item = {
                "is_real_transaction": is_real,
                "label_type": r['label_type'],
                "type": tx_type,
                "amount": r['sample_amount'],
                "merchant": r['sample_merchant'],
                "category": r['sample_category'],
                "reason": r['notes'] or r['label_type']
            }
            lines.append(f"Sample {idx}: \"{r['text']}\" -> {json.dumps(item, ensure_ascii=False)}")
        return "\n".join(lines) + "\n\n"
    except Exception as e:
        if AUTO_TRACK_DEBUG_LOG:
            print(f"[LLM SAMPLES DEBUG] Error loading samples: {e}")
        return ""


def classify_notification_with_llm(text):
    """
    智能营销/广告过滤与关键要素提取：
    通过 LLM 深度校验通知是否为真实发生的交易（扣款/入账），还是营销推广、抽奖、信用卡办卡推广、返现活动或OTP验证码。
    返回: (is_real: bool, llm_data: dict or None)
    采用 Fail-open 策略：若 LLM 离线或超时，放行并返回 (True, None)，确保不漏记真实交易。
    """
    system_instruction = (
        "You are an expert financial transaction validator and parser for Malaysian banking and e-wallets "
        "(Touch 'n Go eWallet, MAE Maybank, Public Bank / MyPB, CIMB Octo, RHB, Hong Leong, GrabPay, Boost, BigPay, etc.).\n"
        "Your task: Decide whether the mobile notification describes an ACTUAL COMPLETED financial transaction "
        "(payment, transfer, debit, credit) or is a PROMOTIONAL MARKETING AD / LOAN OFFER / CREDIT CARD PROMOTION / OTP / SYSTEM NOTICE.\n\n"
        "Rules:\n"
        "1. Genuine Malaysian e-wallet transaction receipts frequently append marketing rewards at the end "
        "(e.g. 'Paid RM 15.00 to FamilyMart. Claim your RM2 voucher!'). If money was ACTUALLY spent, transferred, or received, "
        "is_real_transaction MUST BE TRUE.\n"
        "2. If the message is a promotional campaign inviting the user to apply for cards/loans, join a contest, win prizes, "
        "earn cash back on future spends, or an advertisement (e.g. 'Apply online for PB Credit Card to get RM300 Cash Back'), "
        "is_real_transaction MUST BE FALSE.\n"
        "3. If it is an OTP, verification code, login alert, or system downtime notice, is_real_transaction MUST BE FALSE.\n"
        "4. Standard expense categories: 餐饮, 交通, 购物, 娱乐, 居住, 医疗, 教育, 通讯, 旅行, 人情, 其他.\n"
        "5. Standard income categories: 工资, 奖金, 投资, 自由职业, 其他.\n"
        "Reply with ONLY valid JSON: {\n"
        "  \"is_real_transaction\": true/false,\n"
        "  \"label_type\": \"promo\"|\"expense\"|\"income_transfer\"|\"expense_transfer\"|\"otp_notice\",\n"
        "  \"reason\": \"short reason\",\n"
        "  \"amount\": float or null,\n"
        "  \"type\": \"expense\"|\"income\"|null,\n"
        "  \"merchant\": \"clean merchant or recipient/sender name\" or null,\n"
        "  \"category\": \"standard category name\" or null\n"
        "}"
    )
    samples_block = get_llm_learning_samples_prompt()
    prompt = (
        f"{samples_block}"
        f"Notification text to evaluate:\n\"\"\"{text}\"\"\""
    )
    res = call_llm_json(prompt, system_instruction=system_instruction, timeout=4.5)
    if isinstance(res, dict) and 'is_real_transaction' in res:
        is_real = bool(res['is_real_transaction'])
        return is_real, res

    return True, None


def parse_nlp_with_llm(text):
    """
    通过 LLM 将口语化自然语言文本解析为标准记账对象。
    返回 dict 或 None
    """
    system_instruction = (
        "You are an intelligent accounting parser for a personal ledger app in Malaysia.\n"
        "Parse colloquial natural language entries (in Chinese or English or Malay) into a structured ledger transaction.\n"
        "Categories allowed:\n"
        "- expense: 餐饮, 交通, 购物, 娱乐, 居住, 医疗, 教育, 通讯, 旅行, 人情, 其他\n"
        "- income: 工资, 奖金, 投资, 自由职业, 其他 (group_name is 'main' for salary/main job, 'side' for side gig/investment)\n"
        "- savings: 应急金, 养老, 旅游, 心愿, 其他\n"
        f"- Assume today is {date.today().isoformat()}. Parse relative dates like '昨天', '前天', 'yesterday' correctly.\n"
        "Return ONLY a JSON object: {\n"
        "  \"amount\": positive float,\n"
        "  \"type\": \"expense\" | \"income\" | \"savings\",\n"
        "  \"group_name\": \"main\" | \"side\" | null,\n"
        "  \"category\": \"category name\",\n"
        "  \"note\": \"short descriptive summary of merchant or item\",\n"
        "  \"date\": \"YYYY-MM-DD\"\n"
        "}"
    )
    prompt = f"Ledger entry:\n\"{text}\""
    res = call_llm_json(prompt, system_instruction=system_instruction, timeout=3.5)
    if isinstance(res, dict) and res.get('amount'):
        try:
            amt = float(res['amount'])
            if amt > 0:
                tx_type = res.get('type')
                if tx_type not in ('expense', 'income', 'savings'):
                    tx_type = 'expense'
                group_name = res.get('group_name')
                if tx_type != 'income':
                    group_name = None
                elif group_name not in ('main', 'side'):
                    group_name = 'main'

                parsed_date = res.get('date') or date.today().isoformat()
                if not re.match(r'^\d{4}-\d{2}-\d{2}$', str(parsed_date)):
                    parsed_date = date.today().isoformat()

                return {
                    'date': str(parsed_date),
                    'type': tx_type,
                    'group_name': group_name,
                    'category': str(res.get('category') or '其他'),
                    'amount': amt,
                    'note': str(res.get('note') or text).strip()
                }
        except Exception:
            pass
    return None
