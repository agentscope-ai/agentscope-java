# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest
from report import summarize, task_table
from metrics import compare, parse_cgroup


class ReportTest(unittest.TestCase):
    def test_deadline_usage_and_official_failure_remain_distinct(self):
        manifest = {'benchmark': 'fixture', 'targets': [{'name': 'a'}],
                    'tasks': list(range(6)), 'repeats': 1}
        outcomes = [
            {'status': 'completed', 'reward': 0},
            {'status': 'budget_exceeded', 'error':
             'CaseBudgetExceeded: Turn turn_1 exceeded the evaluation wait budget'},
            {'status': 'budget_exceeded', 'budget_usage': {'usage': {
                'last_denial': {'limit': 'usage_unavailable'}}}},
            {'status': 'provider_error', 'budget_usage': {'usage': {
                'last_denial': {'limit': 'usage_unavailable'}}}},
            {'status': 'budget_exceeded'},
            {'status': 'completed', 'reward': 1},
        ]
        rows = [dict(target='a', task=i, repeat=0, **row) for i, row in enumerate(outcomes)]
        result = summarize(manifest, rows)
        expected = ['official_task_failure', 'evaluation_wait_deadline', 'model_usage_unavailable',
                    'provider_error', 'other_budget_failure', None]
        trials = [task['targets']['a']['trials'][0] for task in result['tasks']]
        self.assertEqual([trial['failure_category'] for trial in trials], expected)
        self.assertEqual([trial['official_score'] for trial in trials], [0, None, None, None, None, 1])
        self.assertEqual(result['targets']['a']['failure_categories'],
                         {category: 1 for category in expected if category is not None})
        self.assertEqual(result['targets']['a']['official_scored'], 2)
        self.assertEqual(result['targets']['a']['end_to_end_success_rate'], 1 / 6)

    def test_missing_usage_is_visible_even_without_explicit_unknown_flag(self):
        manifest = {'benchmark': 'fixture', 'targets': [{'name': 'a'}], 'tasks': [0], 'repeats': 1}
        requests = [
            {'kind': 'model', 'audit': {'usage': {'input_tokens': 0, 'output_tokens': 0, 'total_tokens': 0}}},
            {'kind': 'model', 'audit': {'http_status': 200, 'stream_completed': False}},
            {'kind': 'model', 'audit': {'usage_unknown': True,
                                      'usage': {'input_tokens': 4, 'output_tokens': 1, 'total_tokens': 5}}},
        ]
        row = {'target': 'a', 'task': 0, 'repeat': 0, 'status': 'provider_error', 'request_audit': requests}
        result = summarize(manifest, [row])
        coverage = result['targets']['a']['model_usage_coverage']
        self.assertEqual(coverage['audited_model_requests'], 3)
        self.assertEqual(coverage['usage_reported_requests'], 1)
        self.assertEqual(coverage['usage_unknown_requests'], 2)
        self.assertEqual(coverage['incomplete_stream_requests'], 1)
        self.assertTrue(coverage['observed_tokens_are_lower_bound'])
        trial = result['tasks'][0]['targets']['a']['trials'][0]
        self.assertEqual(trial['model_usage_coverage'], coverage)
        self.assertIsNone(trial['official_score'])
        del row['request_audit']
        coverage = summarize(manifest, [row])['targets']['a']['model_usage_coverage']
        self.assertEqual(coverage['trials_without_request_audit'], 1)
        self.assertTrue(coverage['observed_tokens_are_lower_bound'])

    def test_task_matrix_keeps_zero_failure_and_missing_distinct(self):
        manifest = {'benchmark': 'fixture', 'targets': [{'name': 'a'}, {'name': 'b'}],
                    'tasks': [0], 'repeats': 3}
        rows = [{'target': 'a', 'task': 0, 'repeat': 0, 'status': 'completed', 'reward': 0},
                {'target': 'a', 'task': 0, 'repeat': 1, 'status': 'provider_error'},
                {'target': 'b', 'task': 0, 'repeat': 2, 'status': 'completed', 'reward': 1}]
        result = summarize(manifest, rows)
        a = result['tasks'][0]['targets']['a']
        self.assertEqual([t['official_score'] for t in a['trials']], [0, None, None])
        self.assertEqual([t['status'] for t in a['trials']], ['completed', 'provider_error', 'not_run'])
        self.assertIsNone(a['all_repeats_successful'])
        self.assertEqual(a['attempted'], 2)
        self.assertEqual(a['mean_official_score'], 0)
        self.assertIn('| 0 | 0, provider_error, not_run | not_run, not_run, 1 |', task_table(result, manifest))

    def test_all_repeat_reliability_requires_all_official_scores(self):
        manifest = {'benchmark': 'fixture', 'targets': [{'name': 'a'}],
                    'tasks': [0, 1, 2], 'repeats': 3}
        rows = [{'target': 'a', 'task': task, 'repeat': repeat, 'status': 'completed',
                 'reward': int(task == 0 or repeat < 2)}
                for task in [0, 1] for repeat in range(3)]
        rows.append({'target': 'a', 'task': 2, 'repeat': 0, 'status': 'completed', 'reward': 1})
        result = summarize(manifest, rows)
        reliability = result['targets']['a']['repeat_reliability']
        self.assertEqual(reliability['fully_scored_tasks'], 2)
        self.assertEqual(reliability['all_repeats_successful_tasks'], 1)
        self.assertEqual(reliability['all_repeats_success_rate_scored_tasks'], .5)
        self.assertEqual([t['targets']['a']['all_repeats_successful'] for t in result['tasks']],
                         [True, False, None])

    def test_only_known_cumulative_counters_can_establish_a_reset(self):
        row = {'target': 'a', 'pod': 'a', 'uid': 'one', 'container': 'brain', 'restarts': 0,
               'cgroup': {'cpu.stat': {'nr_throttled': 2, 'current_bw': 100},
                          'memory.events': {'oom_kill': 0}}}
        before = {'captured_at': 1, 'containers': [row]}
        current = {**row, 'cgroup': {**row['cgroup'],
                                   'cpu.stat': {'nr_throttled': 2, 'current_bw': 50}}}
        result = compare(before, {'captured_at': 2, 'containers': [current]})['containers'][0]
        self.assertNotIn('counter_reset', result)
        self.assertEqual(result['cpu.stat_delta'], {'nr_throttled': 0})
        self.assertEqual(result['cpu.stat_unclassified'], {'current_bw': {'before': 100, 'after': 50}})
        current['cgroup']['cpu.stat']['nr_throttled'] = 1
        result = compare(before, {'captured_at': 2, 'containers': [current]})['containers'][0]
        self.assertTrue(result['counter_reset'])

    def test_failures_remain_in_denominator_and_unrun_cases_are_explicit(self):
        manifest = {'benchmark': 'fixture', 'targets': [{'name': 'a'}, {'name': 'b'}],
                    'tasks': [0, 1, 2], 'repeats': 1}
        rows = [{'target': 'a', 'task': 0, 'repeat': 0, 'status': 'completed', 'reward': 1},
                {'target': 'a', 'task': 1, 'repeat': 0, 'status': 'infrastructure_error'},
                {'target': 'b', 'task': 0, 'repeat': 0, 'status': 'completed', 'reward': 0}]
        result = summarize(manifest, rows)
        self.assertEqual(result['targets']['a']['end_to_end_success_rate'], .5)
        self.assertEqual(result['targets']['a']['official_scored_success_rate'], 1)
        self.assertEqual(result['targets']['a']['not_run'], 1)
        self.assertEqual(result['paired_scored_cases'], 1)
        self.assertIsNone(result['cost'])
        with self.assertRaises(ValueError):
            summarize(manifest, rows + rows[:1])

    def test_replacement_cannot_be_reported_as_zero_throttling(self):
        row = {'target': 'a', 'pod': 'a', 'uid': 'one', 'container': 'brain', 'restarts': 0,
               'cgroup': {'cpu.stat': {'nr_throttled': 2}, 'memory.events': {'oom_kill': 0}}}
        before = {'captured_at': 1, 'containers': [row]}
        after = {'captured_at': 2, 'containers': [{**row, 'uid': 'two'}]}
        self.assertTrue(compare(before, after)['containers'][0]['replaced_or_new'])

    def test_cgroup_v1_counters_preserve_units_and_oom_evidence(self):
        result = parse_cgroup('cpu.stat\nnr_periods 11446\nnr_throttled 2\nthrottled_time 1234567\n'
                              'memory.oom_control\noom_kill_disable 0\nunder_oom 0\noom_kill 3\n'
                              'memory.failcnt\n7\nmemory.current\n4096\n')
        self.assertEqual(result['cpu.stat']['throttled_usec'], 1234)
        self.assertEqual(result['memory.events'], {'oom_kill': 3, 'max': 7})
        self.assertEqual(result['memory.current']['value'], 4096)
        self.assertEqual(parse_cgroup('cpu.stat\nthrottled_usec 42\n')['cpu.stat']['throttled_usec'], 42)
        self.assertNotIn('memory.events', parse_cgroup('cpu.stat\nnr_periods 1\n'))
