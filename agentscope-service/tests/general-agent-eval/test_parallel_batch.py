# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import copy
import json
from pathlib import Path
import tempfile
import unittest

from continue_batch import scheduled_keys
from parallel_batch import PHASE, build_plan, lane_remaining, load_lane, validate_plan


class ParallelScheduleTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {'targets': [{'name': n} for n in ['agentscope', 'pi', 'codex']],
                         'tasks': ['task-a', 'task-b'], 'repeats': 3,
                         'model_profile_sha256': 'm', 'evaluation_profile_sha256': 'e'}
        self.keys = list(scheduled_keys(self.manifest['targets'], self.manifest['tasks'], 3))

    def rows(self, keys):
        return [{'target': t, 'task': task, 'repeat': repeat, 'status': 'budget_exceeded'}
                for t, task, repeat in keys]

    def test_every_possible_cutover_is_complete_disjoint_and_preserves_failures(self):
        for count in range(len(self.keys) + 1):
            with self.subTest(count=count):
                plan = build_plan('batch', self.manifest, self.rows(self.keys[:count]))
                validate_plan(plan)
                remaining = [tuple(k) for keys in plan['lanes'].values() for k in keys]
                self.assertFalse(set(remaining) & set(self.keys[:count]))
                self.assertEqual(set(remaining), set(self.keys[count:]))

    def test_lane_resume_never_replays_failed_or_successful_attempts(self):
        plan = build_plan('batch', self.manifest, self.rows(self.keys[:4]))
        keys = plan['lanes']['codex']
        attempted = self.rows(keys[:2]); attempted[1]['status'] = 'completed'
        self.assertEqual(lane_remaining(plan, 'codex', attempted), keys[2:])

    def test_missing_duplicate_reordered_or_cross_lane_entries_fail(self):
        original = build_plan('batch', self.manifest, self.rows(self.keys[:4]))
        for mutation in ['missing', 'duplicate', 'reordered', 'cross_lane']:
            with self.subTest(mutation=mutation):
                plan = copy.deepcopy(original); keys = plan['lanes']['codex']
                if mutation == 'missing': keys.pop()
                elif mutation == 'duplicate': keys.append(keys[0])
                elif mutation == 'reordered': keys.reverse()
                else: keys.append(plan['lanes']['pi'][0])
                with self.assertRaises(ValueError): validate_plan(plan)

    def test_running_part_and_orphan_trial_block_continuation(self):
        plan = build_plan('batch', self.manifest, self.rows(self.keys[:4]))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'parallel-plan.json').write_text(json.dumps(plan))
            directory = root / PHASE / 'codex'; part = directory / 'part-000'
            part.mkdir(parents=True)
            manifest = {**self.manifest, 'targets': [{'name': 'codex'}]}
            (part / 'manifest.json').write_text(json.dumps(manifest))
            row = {**self.rows(plan['lanes']['codex'][:1])[0], 'trial': 'retained-failure'}
            (part / 'results.jsonl').write_text(json.dumps(row) + '\n')
            with self.assertRaises(ValueError): load_lane(root, 'codex')
            (directory / 'part-000-exit.json').write_text('{"returncode":1}')
            self.assertEqual(load_lane(root, 'codex')[1], [row])
            (part / 'orphan.json').write_text(json.dumps({**row, 'trial': 'unknown', 'status': 'running'}))
            with self.assertRaises(ValueError): load_lane(root, 'codex')


if __name__ == '__main__':
    unittest.main()
