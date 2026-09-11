import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import app, get_db

with app.test_client() as client:
    with client.session_transaction() as sess:
        sess['logged_in'] = True
        sess['user_id'] = '2a6512ec-746a-4917-a606-615eee541e9d'
        sess['username'] = 'admin'

    # Direct GET
    r_direct = client.get('/liabilities')
    print('Direct /liabilities status:', r_direct.status_code)
    print('Direct /liabilities header count:', r_direct.data.decode('utf-8').count('<header class="app-header"'))

    # HTMX GET
    r_hx = client.get('/liabilities', headers={'HX-Request': 'true'})
    print('HX /liabilities status:', r_hx.status_code)
    print('HX /liabilities header count:', r_hx.data.decode('utf-8').count('<header class="app-header"'))
    print('HX starts with:', r_hx.data.decode('utf-8').strip()[:100])
