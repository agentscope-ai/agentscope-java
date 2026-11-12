# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import tempfile
import unittest

from continue_batch import load_parts, remaining_keys, scheduled_keys


class ContinuationTest(unittest.TestCase):
    targets = [{'name': 'a'}, {'name': 'b'}, {'name': 'c'}]

    def test_failure_is_retained_and_rotation_continues_without_retry(self):
        schedule = list(scheduled_keys(self.targets, [0, 1], 3))
        rows = [{'target': t, 'task': task, 'repeat': repeat,
                 'status': 'provider_error' if i == 3 else 'completed'}
                for i, (t, task, repeat) in enumerate(schedule[:4])]
        self.assertEqual(remaining_keys(self.targets, [0, 1], 3, rows), schedule[4:])
        self.assertEqual(schedule[3:6], [('b', 0, 1), ('c', 0, 1), ('a', 0, 1)])

    def test_duplicates_gaps_and_unfinished_attempts_are_rejected(self):
        row = {'target': 'a', 'task': 0, 'repeat': 0, 'status': 'completed'}
        for rows in [[row, row], [{**row, 'target': 'b'}], [{**row, 'status': 'running'}]]:
            with self.assertRaises(ValueError):
                remaining_keys(self.targets, [0], 3, rows)

    def test_unrecorded_trial_is_not_silently_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            part = root / 'tau'
            part.mkdir()
            (root / 'tau-exit.json').write_text('{"returncode": 1}')
            (part / 'manifest.json').write_text(json.dumps({'targets': self.targets, 'tasks': [0], 'repeats': 3}))
            (part / 'unrecorded.json').write_text(json.dumps({'trial': 'unrecorded', 'target': 'a',
                'task': 0, 'repeat': 0, 'status': 'running'}))
            with self.assertRaisesRegex(ValueError, 'unrecorded trial'):
                load_parts(root, 'tau')
