# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import runpy
import tempfile
import unittest

from continue_batch import remaining_keys
from continue_native_appworld import load_lane


class NativeContinuationTest(unittest.TestCase):
    def fixture(self, root):
        p = root / 'pi-formal'
        p.mkdir()
        manifest = {'tasks': ['a', 'b', 'c'], 'targets': [{'name': 'pi'}], 'repeats': 1,
                    'model_profile_sha256': 'model', 'evaluation_profile_sha256': 'profile'}
        (p / 'manifest.json').write_text(json.dumps(manifest))
        (p / 'results.jsonl').write_text(json.dumps({'trial': 'a1', 'task': 'a', 'target': 'pi',
            'repeat': 0, 'status': 'budget_exceeded', 'reward': None}) + '\n')
        (root / 'pi-formal-exit.json').write_text('{"returncode":1}')
        return p

    def test_failed_attempt_is_retained_and_only_unattempted_suffix_remains(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            (root / 'pi-formal-part-001-launch.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'no exit receipt'):
                load_lane(root, 'pi')
            (root / 'pi-formal-part-001-exit.json').write_text('{"returncode":1}')
            # Current parent launch is admitted while an unrelated live launch blocks.
            (root / 'pi-formal-part-002-launch.json').write_text('{}')
            manifest, rows, parts = load_lane(root, 'pi', current_part='pi-formal-part-002')
            self.assertEqual(rows[0]['status'], 'budget_exceeded')
            self.assertEqual(remaining_keys(manifest['targets'], manifest['tasks'], 1, rows),
                             [('pi', 'b', 0), ('pi', 'c', 0)])

    def test_unrecorded_trial_blocks_continuation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p = self.fixture(root)
            (p / 'running.json').write_text(json.dumps({'trial': 'b1', 'task': 'b', 'target': 'pi',
                'repeat': 0, 'status': 'running'}))
            with self.assertRaisesRegex(ValueError, 'Unrecorded trial'):
                load_lane(root, 'pi')

    def test_multiprocessing_import_does_not_parse_options_or_start_work(self):
        path = Path(__file__).with_name('continue_native_appworld.py')
        loaded = runpy.run_path(str(path), run_name='__mp_main__')
        self.assertTrue(callable(loaded['main']))
        self.assertNotIn('parser', loaded)


if __name__ == '__main__':
    unittest.main()
