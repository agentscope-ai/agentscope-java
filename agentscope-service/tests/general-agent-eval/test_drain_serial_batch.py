# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from drain_serial_batch import completed_boundary


class DrainBoundaryTest(unittest.TestCase):
    def test_only_fully_recorded_idle_boundary_can_be_drained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = {'trial': 'finished', 'target': 'pi', 'task': 'a', 'repeat': 0, 'status': 'completed'}
            (root / 'results.jsonl').write_text(json.dumps(row) + '\n')
            (root / 'finished.json').write_text(json.dumps(row))
            db_path = root / 'audit.sqlite'
            connect = sqlite3.connect
            with connect(db_path) as db:
                db.execute('CREATE TABLE eval_case(status TEXT)')
                db.execute('CREATE TABLE eval_request(status TEXT)')
            with patch('drain_serial_batch.sqlite3.connect', side_effect=lambda *a, **k: connect(db_path)):
                self.assertEqual(completed_boundary(root)[1], 1)
                (root / 'new.json').write_text(json.dumps({**row, 'trial': 'new', 'status': 'running'}))
                self.assertIsNone(completed_boundary(root))
                (root / 'new.json').unlink()
                for table in ['eval_case', 'eval_request']:
                    with connect(db_path) as db:
                        db.execute('INSERT INTO ' + table + ' VALUES (?)',
                                   ('active' if table == 'eval_case' else 'pending',))
                    self.assertIsNone(completed_boundary(root))
                    with connect(db_path) as db:
                        db.execute('DELETE FROM ' + table)
                (root / 'results.jsonl').write_text(json.dumps(row))
                self.assertIsNone(completed_boundary(root))


if __name__ == '__main__':
    unittest.main()
