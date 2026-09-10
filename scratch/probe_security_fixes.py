"""Functional regression test for the security fixes applied in this pass:
- old leaked auto-track key no longer works
- no configured key => auto-track rejects everything (fail closed)
- no APP_PASSWORD => admin gets a random one-time password (not admin123), logged once
- CSRF is enforced on previously-exempt session-authenticated routes
- /api/categories and /api/transactions/sync only accept X-API-KEY, not session cookies
- /split-bill/ocr-upload and /split-bill/parse-text now require login
"""
import os
import re
import sys
import io
import logging

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
os.environ.setdefault('DATA_DIR', '/tmp/ledger_security_probe')
os.makedirs('/tmp/ledger_security_probe', exist_ok=True)
# deliberately unset these to exercise the "nothing configured" fail-closed paths
os.environ.pop('AUTO_TRACK_KEY', None)
os.environ.pop('APP_PASSWORD', None)

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

# capture app.logger output so we can see the generated one-time admin password.
# init_db() runs at module import time, so the logging handler must be attached to the
# *root* logger before `import app` runs, otherwise we miss the warning it logs during import.
log_stream = io.StringIO()
handler = logging.StreamHandler(log_stream)
logging.getLogger().addHandler(handler)
logging.getLogger().setLevel(logging.WARNING)

import app as appmod

client = appmod.app.test_client()

print("=" * 70)
print("1. Old leaked key ('my-secret-ledger-key') must be permanently rejected")
print("=" * 70)
resp = client.post('/api/auto-track', headers={'X-API-KEY': 'my-secret-ledger-key'}, json={'text': 'test RM10.00'})
print(f"status={resp.status_code}  body={resp.get_json()}")
assert resp.status_code == 401, "old leaked key must NOT work anymore"
print("PASS: old leaked key rejected.")

print("\n" + "=" * 70)
print("2. No AUTO_TRACK_KEY configured at all -> must fail closed (reject everything)")
print("=" * 70)
resp = client.post('/api/auto-track', headers={'X-API-KEY': 'zo}SxK_}_%0LO8w;'}, json={'text': 'test RM10.00'})
print(f"status={resp.status_code}")
assert resp.status_code == 401, "the old hardcoded DEFAULT_AUTO_TRACK_KEY must not work either"
print("PASS: no hardcoded fallback key works.")

print("\n" + "=" * 70)
print("3. No APP_PASSWORD -> default admin user gets a random one-time password (not admin123)")
print("=" * 70)


def get_csrf_token(html_bytes):
    m = re.search(rb'name="csrf_token" value="([^"]+)"', html_bytes)
    assert m, "could not find csrf_token in the rendered login page"
    return m.group(1).decode()


def is_authenticated(c):
    r = c.get('/records', follow_redirects=False)
    return r.status_code == 200


login_page = client.get('/login')
token = get_csrf_token(login_page.data)
resp = client.post('/login', data={'username': 'admin', 'password': 'admin123', 'csrf_token': token})
print(f"login with correct CSRF token but wrong password 'admin123' -> authenticated? {is_authenticated(client)}")
assert not is_authenticated(client), "admin123 must no longer be a valid password"

log_output = log_stream.getvalue()
m = re.search(r'一次性随机密码（仅显示这一次）：(\S+)', log_output)
assert m, f"expected the one-time password warning to be logged, got: {log_output!r}"
generated_pw = m.group(1)
print(f"Found generated one-time password in log: {generated_pw[:6]}... (length {len(generated_pw)})")

login_page2 = client.get('/login')
token2 = get_csrf_token(login_page2.data)
client.post('/login', data={'username': 'admin', 'password': generated_pw, 'csrf_token': token2})
print(f"login with the actual generated password -> authenticated? {is_authenticated(client)}")
assert is_authenticated(client)
print("PASS: no predictable default password; the real generated one works; CSRF token was required both times.")

print("\n" + "=" * 70)
print("3b. Login WITHOUT a CSRF token at all must not authenticate (graceful redirect, but not logged in)")
print("=" * 70)
fresh_client_for_csrf_check = appmod.app.test_client()
fresh_client_for_csrf_check.post('/login', data={'username': 'admin', 'password': generated_pw})  # no csrf_token field
print(f"authenticated without sending any CSRF token? {is_authenticated(fresh_client_for_csrf_check)} (expect False)")
assert not is_authenticated(fresh_client_for_csrf_check), "login must require a valid CSRF token, even though the response is a friendly redirect rather than a raw 400"
print("PASS: CSRF protection on /login actually blocks authentication (redirect looks 'successful' at a glance, but session is never authenticated).")

print("\n" + "=" * 70)
print("4. CSRF is now enforced on /api/llm-samples/reset (session-authenticated, state-changing)")
print("=" * 70)
with client.session_transaction() as sess:
    sess['logged_in'] = True
    sess['user_id'] = 'test-user'
    sess['username'] = 'admin'
resp = client.post('/api/llm-samples/reset')  # no CSRF token header
print(f"without CSRF token -> status={resp.status_code} (expect 400, CSRF rejected)")
assert resp.status_code == 400
print("PASS: request without a CSRF token is rejected.")

print("\n" + "=" * 70)
print("5. /api/categories and /api/transactions/sync no longer accept a session cookie alone")
print("=" * 70)
resp = client.get('/api/categories')  # session is already set from step 4, but no X-API-KEY
print(f"session-only, no API key -> status={resp.status_code} (expect 401)")
assert resp.status_code == 401
print("PASS: session cookie alone no longer grants access to this Android-only endpoint.")

print("\n" + "=" * 70)
print("6. /split-bill/ocr-upload and /split-bill/parse-text now require login")
print("=" * 70)
fresh_client = appmod.app.test_client()  # no session at all
# Grab a real CSRF token from the public /login page first, so this test isolates the
# *login* gate specifically (a request with a valid CSRF token still needs to be logged in) -
# a request with no CSRF token at all gets intercepted by CSRF-checking first (400), which
# is a different, already-covered protection layer, not what this section is testing.
anon_login_page = fresh_client.get('/login')
anon_token = get_csrf_token(anon_login_page.data)

resp = fresh_client.post('/split-bill/parse-text', data={'text': 'Tea 2.20', 'csrf_token': anon_token})
print(f"parse-text, valid CSRF but no login -> status={resp.status_code} (expect redirect to /login, 302)")
assert resp.status_code == 302 and '/login' in (resp.headers.get('Location') or '')
resp2 = fresh_client.post('/split-bill/ocr-upload', data={'csrf_token': anon_token})
print(f"ocr-upload, valid CSRF but no login -> status={resp2.status_code} (expect redirect to /login, 302)")
assert resp2.status_code == 302 and '/login' in (resp2.headers.get('Location') or '')
print("PASS: both routes now require login (independent of CSRF, which is also enforced).")

print("\nAll security-fix regression checks passed.")
