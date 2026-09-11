"""
Integration test for Subscriptions feature using Flask test client.
"""
import unittest
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import app, init_db, get_db

class TestSubscriptionsIntegration(unittest.TestCase):
    def setUp(self):
        init_db()
        app.config['WTF_CSRF_ENABLED'] = False
        self.client = app.test_client()
        with app.app_context():
            db = get_db()
            admin = db.execute("SELECT id FROM users WHERE username='admin'").fetchone()
            self.admin_id = admin['id']
            db.execute("DELETE FROM subscriptions WHERE name='Netflix 4K'")
            db.execute("DELETE FROM transactions WHERE note LIKE '%Netflix 4K%'")
            db.commit()

        with self.client.session_transaction() as sess:
            sess['logged_in'] = True
            sess['username'] = 'admin'
            sess['user_id'] = self.admin_id

    def test_subscriptions_page_loads(self):
        response = self.client.get('/subscriptions')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'Subscriptions', response.data)
        self.assertIn('订阅服务大厅'.encode('utf-8'), response.data)

    def test_add_and_roll_subscription(self):
        # 1. 添加一个订阅服务 Netflix
        res = self.client.post('/api/subscriptions', data={
            'name': 'Netflix 4K',
            'category': '流媒体',
            'billing_cycle': 'MONTHLY',
            'cost': '55.00',
            'currency': 'MYR',
            'start_date': '2026-09-01',
            'next_billing_date': '2026-09-11', # 今天
            'cancellation_reminder_days': '3',
            'auto_renew': '1',
            'target_to_cancel': '0',
            'is_trial': '0',
            'note': '单元测试样本'
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b'Netflix 4K', res.data)

        # 2. 查询该条目并执行前滚
        with app.app_context():
            db = get_db()
            sub = db.execute("SELECT id, next_billing_date FROM subscriptions WHERE name='Netflix 4K'").fetchone()
            self.assertIsNotNone(sub)
            sub_id = sub['id']
            self.assertEqual(sub['next_billing_date'], '2026-09-11')

        roll_res = self.client.post(f'/api/subscriptions/{sub_id}/roll', data={
            'record_expense': '1'
        }, follow_redirects=True)
        self.assertEqual(roll_res.status_code, 200)

        # 3. 验证数据库中 next_billing_date 变成了 2026-10-11，且 transactions 表新增了一笔记录
        with app.app_context():
            db = get_db()
            sub = db.execute("SELECT id, next_billing_date FROM subscriptions WHERE id=?", (sub_id,)).fetchone()
            self.assertEqual(sub['next_billing_date'], '2026-10-11')
            
            tx = db.execute("SELECT * FROM transactions WHERE note LIKE '%Netflix 4K%'").fetchone()
            self.assertIsNotNone(tx)
            self.assertEqual(tx['amount'], 55.0)

        # 4. 清理测试样本
        self.client.post(f'/api/subscriptions/{sub_id}/delete')

if __name__ == '__main__':
    unittest.main()
