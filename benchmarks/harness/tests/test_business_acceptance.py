# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from reports.business_results import AGENTS, evaluate, expected_tasks


class BusinessAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.inventory = {'tau': {'retail': [0]}, 'appworld': {'dev': ['abc_1', 'abc_2', 'abc_3']}}
        self.manifest = {'run_id': 'fixture', 'rollouts': 1, 'agents': [{'name': a} for a in AGENTS],
                         'tasks': list(expected_tasks(self.inventory))}
        self.rows = [{'agent': a, 'task': t, 'rollout': 0, 'score': 1, 'execution_status': 'completed',
                      'scoring_status': 'scored', 'native_completed': True, 'protocol_valid': True,
                      'archive_complete': True} for a in AGENTS for t in self.manifest['tasks']]

    def test_missing_and_invalid_trials_remain_in_denominator_and_scenario(self):
        rows = copy.deepcopy(self.rows)
        rows[1]['native_completed'] = False
        rows.pop(2)
        result = evaluate(self.inventory, [(self.manifest, {'results': rows})])
        self.assertEqual((result['planned'], result['observed'], result['missing']), (12, 11, 1))
        group = next(g for g in result['groups'] if g['agent'] == 'agentscope' and g['split'] == 'appworld-dev')
        self.assertEqual((group['planned'], group['passed'], group['invalid_or_unscored']), (3, 1, 2))
        self.assertEqual(result['appworld_scenarios'][0]['all_three_passed'], 0)
        self.assertFalse(result['all_trials_valid_scored'])

    def test_duplicate_results_are_rejected(self):
        with self.assertRaises(ValueError):
            evaluate(self.inventory, [(self.manifest, {'results': self.rows + self.rows[:1]})])


if __name__ == '__main__':
    unittest.main()
