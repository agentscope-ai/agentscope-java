# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
from copy import deepcopy
import unittest

from summarize_native_appworld import summarize


class NativeSummaryTest(unittest.TestCase):
    def lanes(self):
        manifest = {'tasks': ['one', 'two'], 'repeats': 1, 'commit': 'frozen', 'model': 'qwen',
                    'model_profile_sha256': 'model', 'evaluation_profile_sha256': 'eval'}
        return [{'target': target, 'manifest': manifest, 'instructions': {'one': 'Do the task'},
                 'rows': [{'target': target, 'task': 'one', 'trial': target + '-one', 'repeat': 0,
                     'status': 'budget_exceeded', 'reward': None, 'elapsed_seconds': 10,
                     'provider_known_tokens': 30, 'provider_usage_missing': 1}],
                 'active': [], 'pending': 0, 'leases': 0, 'captured_at': 100}
                for target in ['agentscope', 'pi', 'codex']]

    def test_unscored_and_pending_are_not_official_zero(self):
        report = summarize(self.lanes())
        self.assertFalse(report['complete'])
        for summary in report['summary'].values():
            self.assertEqual(summary['unscored'], 1)
            self.assertEqual(summary['pending'], 1)
            self.assertEqual(summary['official_zeros'], 0)
            self.assertEqual(summary['scoring_coverage_of_attempts'], 0)

    def test_duplicate_or_reordered_continuation_is_rejected(self):
        lanes = self.lanes()
        lanes[0]['rows'].append(deepcopy(lanes[0]['rows'][0]))
        with self.assertRaises(ValueError):
            summarize(lanes)
        lanes = self.lanes()
        lanes[0]['rows'][0]['task'] = 'two'
        with self.assertRaises(ValueError):
            summarize(lanes)

    def test_all_rows_without_cleanup_are_not_complete(self):
        lanes = self.lanes()
        for lane in lanes:
            row = {**lane['rows'][0], 'task': 'two', 'trial': lane['target'] + '-two',
                   'status': 'completed', 'reward': 1}
            lane['rows'].append(row)
        lanes[1]['leases'] = 1
        self.assertFalse(summarize(lanes)['complete'])
        lanes[1]['leases'] = 0
        self.assertTrue(summarize(lanes)['complete'])


if __name__ == '__main__':
    unittest.main()
