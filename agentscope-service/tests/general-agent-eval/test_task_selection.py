# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import tempfile
import unittest

from task_selection import select_tasks


class TaskSelectionTest(unittest.TestCase):
    def test_explicit_selection_preserves_order_and_rejects_invalid_schedules(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, 'tasks.json')
            path.write_text(json.dumps(['b', 'a']))
            self.assertEqual(select_tasks(['a', 'b', 'c'], path), ['b', 'a'])
            with self.assertRaisesRegex(ValueError, 'offset/count'):
                select_tasks(['a', 'b'], path, offset=1)
            for invalid in [[], ['a', 'a'], ['unknown'], [1], {'task': 'a'}]:
                path.write_text(json.dumps(invalid))
                with self.assertRaises(ValueError):
                    select_tasks(['a', 'b'], path)

    def test_ranges_cannot_silently_run_fewer_tasks(self):
        self.assertEqual(select_tasks(['b', 'a', 'c'], offset=1, count=2), ['b', 'c'])
        for offset, count in [(-1, 1), (0, 0), (2, 2), (3, 1)]:
            with self.assertRaises(ValueError):
                select_tasks(['a', 'b', 'c'], offset=offset, count=count)
