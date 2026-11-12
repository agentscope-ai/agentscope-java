# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from continue_batch import scheduled_keys
from merge_parallel_results import compare_hashes, merge_rows, result_report, verify_source, write_reports
from parallel_batch import PHASE, build_plan


class MergeResultsTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {'benchmark': 'AppWorld', 'targets': [{'name': n} for n in ['agentscope', 'pi', 'codex']],
                         'tasks': ['a', 'b'], 'repeats': 3, 'model_profile_sha256': 'm',
                         'evaluation_profile_sha256': 'e'}
        self.rows = [{'target': target, 'task': task, 'repeat': repeat,
                      'trial': f'{target}-{task}-{repeat}', 'status': 'completed', 'reward': 1,
                      'started': 100 + index * 10, 'elapsed_seconds': 9,
                      'initial_hash': task + '-initial', 'definition_sha256': 'definition',
                      'task_sha256': task + '-input', 'request_audit': []}
                     for index, (target, task, repeat) in enumerate(
                         scheduled_keys(self.manifest['targets'], self.manifest['tasks'], 3))]
        self.serial = self.rows[:5]
        self.plan = build_plan('batch', self.manifest, self.serial)
        self.lanes = {target: [row for row in self.rows[5:] if row['target'] == target]
                      for target in self.plan['lanes']}

    def merge(self):
        return merge_rows(self.manifest, self.plan, self.serial, self.lanes)

    def test_all_cutover_boundaries_restore_exact_schedule_without_mutating_rows(self):
        before = copy.deepcopy(self.rows)
        for count in range(len(self.rows) + 1):
            serial = self.rows[:count]
            plan = build_plan('batch', self.manifest, serial)
            lanes = {target: [r for r in self.rows[count:] if r['target'] == target]
                     for target in plan['lanes']}
            merged = merge_rows(self.manifest, plan, serial, lanes)
            self.assertEqual([r['trial'] for r in merged], [r['trial'] for r in self.rows])
            self.assertEqual(sum(r['execution_phase'] == 'serial' for r in merged), count)
        self.assertEqual(self.rows, before)

    def test_missing_duplicate_reordered_and_foreign_rows_are_rejected(self):
        for mutation in ['missing', 'duplicate', 'reordered', 'foreign', 'running']:
            with self.subTest(mutation=mutation):
                lanes = copy.deepcopy(self.lanes)
                if mutation == 'missing': lanes['pi'].pop()
                elif mutation == 'duplicate': lanes['pi'].append(lanes['pi'][-1])
                elif mutation == 'reordered': lanes['pi'].reverse()
                elif mutation == 'foreign': lanes['pi'][0]['target'] = 'codex'
                else: lanes['pi'][0]['status'] = 'running'
                with self.assertRaises(ValueError): merge_rows(self.manifest, self.plan, self.serial, lanes)

    def test_tampered_schedule_and_configuration_are_rejected(self):
        for field, value in [('tasks', ['b', 'a']), ('model_profile_sha256', 'changed')]:
            manifest = {**self.manifest, field: value}
            with self.assertRaises(ValueError): merge_rows(manifest, self.plan, self.serial, self.lanes)
        with self.assertRaises(ValueError): merge_rows(self.manifest, self.plan, self.serial[::-1], self.lanes)

    def test_reused_trial_identity_is_not_silently_deduplicated(self):
        self.lanes['pi'][0]['trial'] = self.serial[0]['trial']
        with self.assertRaises(ValueError): self.merge()

    def test_failed_attempt_retains_null_score_unknown_usage_and_phase(self):
        failed = self.lanes['codex'][0]
        failed.update({'status': 'budget_exceeded', 'reward': None,
                       'request_audit': [{'kind': 'model', 'audit': {'usage_unknown': True}}]})
        self.lanes['pi'][0]['reward'] = 0
        result = result_report(self.manifest, self.merge())
        trial = result['tasks'][0]['targets']['codex']['trials'][failed['repeat']]
        self.assertIsNone(trial['official_score'])
        self.assertEqual(trial['execution_phase'], PHASE)
        self.assertTrue(trial['model_usage_coverage']['observed_tokens_are_lower_bound'])
        self.assertEqual(trial['observed_provider_usage'], {})
        self.assertEqual(result['targets']['codex']['attempted'], 6)
        self.assertEqual(result['targets']['codex']['official_scored'], 5)
        self.assertEqual(result['targets']['pi']['official_scored'], 6)
        self.assertEqual(result['targets']['pi']['successes'], 5)
        self.assertNotIn('not_run', result['execution_phases']['serial']['targets']['codex'])

    def test_missing_hashes_and_drift_remain_explicit(self):
        merged = self.merge()
        merged[0].pop('initial_hash')
        merged[1]['definition_sha256'] = 'different'
        checks = compare_hashes(self.manifest, merged)
        self.assertEqual(checks['a']['hashes']['initial_hash']['missing_trials'], [merged[0]['trial']])
        self.assertTrue(checks['a']['hashes']['definition_sha256']['mismatch'])
        self.assertEqual(checks['a']['phases'], [PHASE, 'serial'])

    def test_frozen_source_tampering_is_detected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'code').mkdir()
            (root / 'code/runner.py').write_bytes(b'original')
            expected = {'runner.py': hashlib.sha256(b'original').hexdigest()}
            (root / 'runner-source.json').write_text(json.dumps(expected))
            verify_source(root, expected)
            (root / 'code/runner.py').write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_source(root, expected)

    def test_archived_rows_need_no_duplicate_copy_for_identical_reports(self):
        self.lanes['codex'][0].update(status='budget_exceeded', reward=None)
        rows = self.merge()
        plan = {**self.plan, 'lane_namespaces': {'codex': 'fixture'}}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_reports(root / 'full', {'appworld': (self.manifest, rows)}, plan, {})
            write_reports(root / 'reports', {'appworld': (self.manifest, rows)}, plan, {},
                          include_private_rows=False)
            raw = root / 'full/appworld/results.private.jsonl'
            self.assertEqual([json.loads(line) for line in raw.read_text().splitlines()], rows)
            self.assertFalse((root / 'reports/appworld/results.private.jsonl').exists())
            for name in ['manifest.json', 'report.json', 'report.md', 'cases.md', 'verification.json']:
                self.assertEqual((root / 'full/appworld' / name).read_bytes(),
                                 (root / 'reports/appworld' / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
