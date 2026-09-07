"""
Turso HTTP Client wrapper that conforms to sqlite3 connection and cursor interface
so Flask/app.py can transparently use Turso Cloud SQLite without changing business logic.
"""
import requests

class TursoRow(dict):
    """Row that supports both dict indexing r['name'] and integer indexing r[0]"""
    def __init__(self, cols, values):
        super().__init__()
        self._keys = list(cols)
        self._values = list(values)
        for c, v in zip(cols, values):
            self[c] = v

    def __getitem__(self, item):
        if isinstance(item, int):
            return self._values[item]
        return super().__getitem__(item)

    def get(self, item, default=None):
        return super().get(item, default)


class TursoCursor:
    def __init__(self, client):
        self.client = client
        self.lastrowid = None
        self.rowcount = 0
        self._rows = []
        self._index = 0

    def execute(self, sql, params=None):
        res = self.client._execute(sql, params)
        self.lastrowid = res.get('last_insert_rowid')
        self.rowcount = res.get('affected_row_count', 0)
        cols = [c['name'] for c in res.get('cols', [])]
        raw_rows = res.get('rows', [])
        
        parsed_rows = []
        for r in raw_rows:
            vals = []
            for col_val in r:
                val = col_val.get('value')
                t = col_val.get('type')
                if t == 'integer' and val is not None:
                    try:
                        val = int(val)
                    except ValueError:
                        pass
                elif t == 'float' and val is not None:
                    try:
                        val = float(val)
                    except ValueError:
                        pass
                elif t == 'null':
                    val = None
                vals.append(val)
            parsed_rows.append(TursoRow(cols, vals))
            
        self._rows = parsed_rows
        self._index = 0
        return self

    def executemany(self, sql, seq_of_params):
        for params in seq_of_params:
            self.execute(sql, params)
        return self

    def fetchone(self):
        if self._index < len(self._rows):
            r = self._rows[self._index]
            self._index += 1
            return r
        return None

    def fetchall(self):
        remaining = self._rows[self._index:]
        self._index = len(self._rows)
        return remaining


class TursoConnection:
    def __init__(self, url, token):
        # Convert libsql:// to https://
        if url.startswith('libsql://'):
            url = 'https://' + url[9:]
        self.endpoint = url.rstrip('/') + '/v2/pipeline'
        self.token = token
        self.headers = {
            'Authorization': f'Bearer {token}',
            'Content-Type': 'application/json'
        }

    def _execute(self, sql, params=None):
        stmt = {'sql': sql}
        if params:
            args = []
            if isinstance(params, (list, tuple)):
                for p in params:
                    if p is None:
                        args.append({'type': 'null'})
                    elif isinstance(p, int):
                        args.append({'type': 'integer', 'value': str(p)})
                    elif isinstance(p, float):
                        args.append({'type': 'float', 'value': p})
                    else:
                        args.append({'type': 'text', 'value': str(p)})
                stmt['args'] = args
        payload = {
            'requests': [{'type': 'execute', 'stmt': stmt}]
        }
        resp = requests.post(self.endpoint, json=payload, headers=self.headers, timeout=12)
        if resp.status_code != 200:
            raise RuntimeError(f"Turso Error {resp.status_code}: {resp.text}")
        data = resp.json()
        results = data.get('results', [])
        if results and results[0].get('type') == 'ok':
            return results[0]['response']['result']
        elif results and results[0].get('type') == 'error':
            raise RuntimeError(results[0].get('error', {}).get('message', 'Turso query failed'))
        return {}

    def cursor(self):
        return TursoCursor(self)

    def execute(self, sql, params=None):
        cur = self.cursor()
        return cur.execute(sql, params)

    def executemany(self, sql, seq_of_params):
        cur = self.cursor()
        return cur.executemany(sql, seq_of_params)

    def executescript(self, script_str):
        cur = self.cursor()
        for statement in script_str.split(';'):
            stmt = statement.strip()
            if stmt:
                cur.execute(stmt)

    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass
