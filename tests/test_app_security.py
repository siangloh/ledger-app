import io
import re

import pytest


@pytest.fixture()
def is_valid_api_key(flask_app):
    return flask_app.is_valid_api_key


def test_api_key_accepts_the_configured_key(is_valid_api_key):
    assert is_valid_api_key("test-auto-track-key") is True


def test_api_key_rejects_wrong_key(is_valid_api_key):
    assert is_valid_api_key("something-else") is False


def test_api_key_rejects_empty_or_missing_key(is_valid_api_key):
    assert is_valid_api_key("") is False
    assert is_valid_api_key(None) is False


def test_api_key_comparison_uses_hmac_compare_digest(flask_app, monkeypatch):
    """Regression test: the key check must not use a plain '==' string comparison,
    which is vulnerable to a timing side-channel attack."""
    calls = []
    real_compare = flask_app.hmac.compare_digest

    def spy(a, b):
        calls.append((a, b))
        return real_compare(a, b)

    monkeypatch.setattr(flask_app.hmac, "compare_digest", spy)
    flask_app.is_valid_api_key("test-auto-track-key")
    assert calls, "is_valid_api_key should delegate to hmac.compare_digest"


def _extract_hidden_value(html, field_name):
    match = re.search(
        rf'name="{field_name}"\s+value="([^"]*)"', html.decode("utf-8")
    )
    assert match, f"could not find hidden field {field_name!r} in response"
    return match.group(1)


def test_import_upload_then_confirm_happy_path(logged_in_client):
    csv_bytes = b"date,amount,note\n2026-01-05,12.50,coffee\n"
    upload_resp = logged_in_client.post(
        "/import/upload",
        data={"file": (io.BytesIO(csv_bytes), "test.csv")},
        content_type="multipart/form-data",
    )
    assert upload_resp.status_code == 200
    token = _extract_hidden_value(upload_resp.data, "token")
    ext = _extract_hidden_value(upload_resp.data, "ext")
    assert re.fullmatch(r"[0-9a-f]{32}", token)
    assert ext == ".csv"

    confirm_resp = logged_in_client.post(
        "/import/confirm",
        data={
            "token": token,
            "ext": ext,
            "date_col": "date",
            "amount_col": "amount",
            "type_mode": "fixed_expense",
            "note_col": "note",
            "default_category": "未分类",
            "default_group": "main",
        },
        follow_redirects=False,
    )
    assert confirm_resp.status_code in (302, 303)
    assert confirm_resp.headers["Location"].endswith("/records") or "/records" in confirm_resp.headers["Location"]


@pytest.mark.parametrize(
    "token,ext",
    [
        ("../../../../etc/passwd", ".csv"),
        ("/etc/passwd", ""),
        ("..%2f..%2fapp", ".csv"),
        ("not-a-uuid-hex", ".csv"),
        ("a" * 32, ".exe"),
        ("a" * 32, ""),
    ],
)
def test_import_confirm_rejects_tampered_token_or_extension(logged_in_client, token, ext):
    """Regression test for the path-traversal / arbitrary file read bug: token and ext
    come from plain hidden form fields the client fully controls, so import_confirm
    must validate them before building a filesystem path instead of trusting them."""
    resp = logged_in_client.post(
        "/import/confirm",
        data={
            "token": token,
            "ext": ext,
            "date_col": "date",
            "amount_col": "amount",
            "type_mode": "fixed_expense",
        },
        follow_redirects=False,
    )
    # Must bounce back to the import page with an error, never attempt to read
    # whatever file the tampered path happened to point at.
    assert resp.status_code in (302, 303)
    assert "/import" in resp.headers["Location"]


def test_login_and_register_are_csrf_exempt_to_prevent_login_loops(client):
    """Regression test: /login and /register must be exempt from CSRF protection
    to prevent login loop / session token missing errors across idle wakeups and
    ephemeral container restarts."""
    resp = client.post(
        "/login",
        data={"username": "invalid_user", "password": "WrongPassword123!"},
        follow_redirects=False,
    )
    # Reaches credential verification (not intercepted by CSRF errorhandler)
    assert resp.status_code == 401
    assert b"\xe7\x94\xa8\xe6\x88\xb7\xe5\x90\x8d\xe6\x88\x96\xe5\xaf\x86\xe7\xa0\x81\xe9\x94\x99\xe8\xaf\xaf" in resp.data or b"login" in resp.data

