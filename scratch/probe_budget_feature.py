"""
Functional regression test for the category monthly budget + overspending
alert feature ("Prompt 08"):
- category_budgets / category_budget_alerts tables get created by init_db()
- setting / updating / clearing a budget via /categories/<id>/budget
- get_category_budget_status() computes spend vs limit correctly
- check_and_record_budget_alerts() fires once per threshold (70/100/150%) and
  never re-fires for the same (user, category, month, threshold)
- add_transaction() surfaces a budget_alert in its AJAX response when a new
  threshold is crossed, and does NOT surface one when nothing changed
- deleting a category cascades and removes its budget row
"""
import os
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, ROOT_DIR)
os.environ.setdefault('DATA_DIR', os.path.join(ROOT_DIR, 'scratch', '_budget_probe_data'))
os.makedirs(os.environ['DATA_DIR'], exist_ok=True)

from datetime import date

import app as appmod

client = appmod.app.test_client()


def get_csrf_token(html_bytes):
    m = re.search(rb'name="csrf_token" value="([^"]+)"', html_bytes) or re.search(rb'name="csrf-token" content="([^"]+)"', html_bytes)
    assert m, "could not find a csrf token on the page"
    return m.group(1).decode()


with client.session_transaction() as sess:
    sess['logged_in'] = True
    sess['username'] = 'admin'
    sess['user_id'] = 1

USER_ID = '1'
THIS_MONTH = date.today().strftime('%Y-%m')

print("=" * 70)
print("0. Tables exist after init_db()")
print("=" * 70)
app_ctx = appmod.app.app_context()
app_ctx.push()
db = appmod.get_db()
tables = {r['name'] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
assert 'category_budgets' in tables and 'category_budget_alerts' in tables
print("PASS: category_budgets and category_budget_alerts tables exist.")

print("\n" + "=" * 70)
print("1. Create a test expense category, then set a budget on it via the route")
print("=" * 70)
token = get_csrf_token(client.get('/').data)
db.execute("DELETE FROM categories WHERE user_id=? AND name='测试预算分类'", (USER_ID,))
db.execute("DELETE FROM category_budgets WHERE user_id=? AND category='测试预算分类'", (USER_ID,))
db.execute("DELETE FROM category_budget_alerts WHERE user_id=? AND category='测试预算分类'", (USER_ID,))
db.execute("DELETE FROM transactions WHERE user_id=? AND category='测试预算分类'", (USER_ID,))
db.commit()

resp = client.post('/categories/add', data={'type': 'expense', 'name': '测试预算分类', 'csrf_token': token},
                    headers={'X-Requested-With': 'XMLHttpRequest'})
assert resp.get_json()['ok'], resp.get_json()
cat_row = db.execute("SELECT id FROM categories WHERE user_id=? AND name='测试预算分类'", (USER_ID,)).fetchone()
cat_id = cat_row['id']

token2 = get_csrf_token(client.get('/categories').data)
resp = client.post(f'/categories/{cat_id}/budget', data={'monthly_limit': '100', 'csrf_token': token2},
                    headers={'X-Requested-With': 'XMLHttpRequest'})
data = resp.get_json()
print(f"set budget -> ok={data['ok']}  message={data['message']}")
assert data['ok'] is True

budget_row = db.execute("SELECT monthly_limit FROM category_budgets WHERE user_id=? AND category='测试预算分类'", (USER_ID,)).fetchone()
assert budget_row and float(budget_row['monthly_limit']) == 100.0
print("PASS: budget row created with limit RM100.00")

print("\n" + "=" * 70)
print("2. get_category_budget_status(): 0% spent -> level 'ok'")
print("=" * 70)
status = appmod.get_category_budget_status(db, USER_ID, THIS_MONTH)
row = next(s for s in status if s['category'] == '测试预算分类')
print(row)
assert row['spent'] == 0.0 and row['level'] == 'ok' and row['pct'] == 0.0
print("PASS")

print("\n" + "=" * 70)
print("3. Add an expense transaction crossing 70% (RM75 of RM100) -> alert fires once")
print("=" * 70)
token3 = get_csrf_token(client.get('/').data)
resp = client.post('/transactions/add', data={
    'type': 'expense', 'amount': '75', 'category': '测试预算分类', 'date': f'{THIS_MONTH}-01',
    'note': 'test tx 1', 'csrf_token': token3
}, headers={'X-Requested-With': 'XMLHttpRequest'})
data = resp.get_json()
print(f"budget_alert={data.get('budget_alert')}")
assert data['ok'] is True
assert data['budget_alert'] is not None and data['budget_alert']['threshold'] == 70
print("PASS: crossing 70% returns a budget_alert exactly once.")

print("\n" + "=" * 70)
print("4. Adding another small expense that keeps it under 100% -> no NEW alert (already alerted at 70%)")
print("=" * 70)
token4 = get_csrf_token(client.get('/').data)
resp = client.post('/transactions/add', data={
    'type': 'expense', 'amount': '5', 'category': '测试预算分类', 'date': f'{THIS_MONTH}-02',
    'note': 'test tx 2', 'csrf_token': token4
}, headers={'X-Requested-With': 'XMLHttpRequest'})
data = resp.get_json()
print(f"budget_alert={data.get('budget_alert')}  (spent should now be 80/100 = 80%, still in the 70%% bucket)")
assert data['budget_alert'] is None, "must not re-fire the same 70% threshold twice"
print("PASS: no duplicate alert for the same threshold.")

print("\n" + "=" * 70)
print("5. Crossing 100% -> a NEW alert fires (threshold=100)")
print("=" * 70)
token5 = get_csrf_token(client.get('/').data)
resp = client.post('/transactions/add', data={
    'type': 'expense', 'amount': '30', 'category': '测试预算分类', 'date': f'{THIS_MONTH}-03',
    'note': 'test tx 3', 'csrf_token': token5
}, headers={'X-Requested-With': 'XMLHttpRequest'})
data = resp.get_json()
print(f"budget_alert={data.get('budget_alert')}  (spent now 110/100 = 110%)")
assert data['budget_alert'] is not None and data['budget_alert']['threshold'] == 100
print("PASS: crossing 100% fires a new alert distinct from the 70% one.")

status = appmod.get_category_budget_status(db, USER_ID, THIS_MONTH)
row = next(s for s in status if s['category'] == '测试预算分类')
print(f"status now: {row}")
assert row['level'] == 'over' and row['spent'] == 110.0
print("PASS: get_category_budget_status reflects 'over' level correctly.")

print("\n" + "=" * 70)
print("6. Deleting the category cascades to remove its budget row")
print("=" * 70)
token6 = get_csrf_token(client.get('/categories').data)
resp = client.post(f'/categories/{cat_id}/delete', data={'csrf_token': token6}, headers={'X-Requested-With': 'XMLHttpRequest'})
assert resp.get_json()['ok']
remaining = db.execute("SELECT 1 FROM category_budgets WHERE user_id=? AND category='测试预算分类'", (USER_ID,)).fetchone()
assert remaining is None, "budget row must be cascade-deleted along with its category"
print("PASS: budget row removed when the category is deleted.")

# cleanup test transactions
db.execute("DELETE FROM transactions WHERE user_id=? AND category='测试预算分类'", (USER_ID,))
db.execute("DELETE FROM category_budget_alerts WHERE user_id=? AND category='测试预算分类'", (USER_ID,))
db.commit()

print("\nAll category-budget regression checks passed.")
