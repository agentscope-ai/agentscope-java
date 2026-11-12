# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import concurrent.futures
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from environment_leases import EnvironmentLeases
from evaluation import (CaseBudgetExceeded, EnvironmentLease, EvaluationGateway, admit_case, cases,
                        failure_status, finish_case, load_profile, remaining)
from session_client import SessionClient


class EvaluationTest(unittest.TestCase):
    def test_failed_model_transport_is_classified_from_gateway_evidence(self):
        gateway = EvaluationGateway.__new__(EvaluationGateway)
        for status, expected in [('provider_error', 'provider_error'), ('failed', 'infrastructure_error')]:
            gateway.row = {'status': 'harness_error'}
            gateway.call = Mock(return_value={'requests': [{'kind': 'model', 'status': status,
                'audit': {'usage_unknown': True}}]})
            gateway.snapshot()
            self.assertEqual(gateway.row['status'], expected)
            self.assertEqual(gateway.row['request_audit'][0]['audit'], {'usage_unknown': True})

    def test_failed_environment_initialization_is_not_a_model_budget_score(self):
        gateway = EvaluationGateway.__new__(EvaluationGateway)
        gateway.row = {'status': 'budget_exceeded'}
        request = {'kind': 'mcp', 'status': 'failed', 'audit': {'http_status': 307}}
        gateway.call = Mock(return_value={'requests': [request]})
        gateway.snapshot()
        self.assertEqual(gateway.row['status'], 'infrastructure_error')
        gateway.row = {'status': 'harness_error'}
        gateway.call.return_value = {'requests': [{'kind': 'tool', 'status': 'succeeded',
                                                   'audit': {'http_status': 200, 'isError': True}}]}
        gateway.snapshot()
        self.assertEqual(gateway.row['status'], 'harness_error')

    def test_rotating_order_balances_positions_and_preserves_repeats(self):
        schedule = list(cases(['java', 'pi', 'codex'], ['task'], 3))
        self.assertEqual([row[0] for row in schedule],
                         ['java', 'pi', 'codex', 'pi', 'codex', 'java', 'codex', 'java', 'pi'])
        self.assertEqual(len(set(schedule)), 9)
        with self.assertRaises(ValueError):
            list(cases(['java'], [], 1))

    def test_case_budget_is_accepted_exactly_and_timeout_uses_case_deadline(self):
        _, settings = load_profile(Path(__file__).with_name('evaluation-profile.json'), 'tau')
        client = Mock()
        client.api.side_effect = lambda _path, limits, **_kw: {'limits': limits}
        limits = admit_case(client, 'session', settings, 100)
        self.assertEqual(client.api.call_args.kwargs, {'method': 'PUT'})
        self.assertEqual(limits['deadline_epoch_ms'], 1000000)
        self.assertEqual(limits['max_tool_calls'], 30)
        with patch('evaluation.time.time', return_value=995):
            self.assertEqual(remaining(limits, 300), 5)
        with patch('evaluation.time.time', return_value=1000):
            with self.assertRaises(CaseBudgetExceeded):
                remaining(limits, 300)
        client.api.side_effect = None
        client.api.return_value = {'limits': {'max_model_calls': 60}}
        with self.assertRaises(RuntimeError):
            admit_case(client, 'session', settings, 100)

    def test_network_timeout_is_not_an_agent_budget_failure(self):
        self.assertEqual(failure_status(TimeoutError('network')), 'infrastructure_error')
        self.assertEqual(failure_status(CaseBudgetExceeded('limit')), 'budget_exceeded')
        client = SessionClient('http://unused', {})
        client.cancel = Mock()
        with self.assertRaises(CaseBudgetExceeded):
            client.wait('s', 't', 0)
        client.cancel.assert_not_called()  # Persist failure before requesting cancellation.

    def test_failure_keeps_budget_and_cancellation_evidence(self):
        client = Mock()
        client.cancel.side_effect = TimeoutError('uncertain cancellation')
        client.api.return_value = {'usage': {'model_calls': 60}}
        row = {'session': 's', 'turn': 't', 'status': 'budget_exceeded'}
        finish_case(client, row)
        self.assertEqual(row['status'], 'budget_exceeded')
        self.assertEqual(row['budget_usage']['usage']['model_calls'], 60)
        self.assertIn('TimeoutError', row['cancellation_error'])

    def test_durable_budget_denial_stops_waiting_even_if_turn_is_not_terminal(self):
        client = SessionClient('http://unused', {})
        client.api = Mock(return_value={'usage': {'last_denial':
            {'code': 'budget_exceeded', 'limit': 'max_recovery_attempts'}}})
        with self.assertRaisesRegex(CaseBudgetExceeded, 'max_recovery_attempts'):
            client.wait('s', 't', 10, monitor_budget=True)
        self.assertEqual(client.api.call_count, 1)

    def test_only_one_concurrent_runner_owns_slot_and_restart_does_not_clear_it(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, 'lease.sqlite')
            leases = EnvironmentLeases(path)
            def acquire(owner):
                try:
                    leases.operate('acquire', 'tau', owner)
                    return owner
                except ValueError:
                    return None
            with concurrent.futures.ThreadPoolExecutor(4) as pool:
                owners = [x for x in pool.map(acquire, ['a', 'b', 'c', 'd']) if x]
            self.assertEqual(len(owners), 1)
            restarted = EnvironmentLeases(path)
            with self.assertRaises(ValueError):
                restarted.operate('acquire', 'tau', 'new')
            with self.assertRaises(ValueError):
                restarted.operate('release', 'tau', 'new')
            restarted.operate('check', 'tau', owners[0])
            restarted.operate('release', 'tau', owners[0])
            restarted.operate('acquire', 'tau', 'new')
            restarted.operate('acquire', 'appworld', 'independent')

    def test_lost_acquire_response_leaves_owner_identity_for_recovery(self):
        with tempfile.TemporaryDirectory() as root:
            with patch.object(EnvironmentLease, 'call', side_effect=TimeoutError('lost receipt')):
                with self.assertRaises(TimeoutError):
                    EnvironmentLease({}, 'tau', root)
            record = json.loads(Path(root, 'environment-lease.json').read_text())
            self.assertEqual(record['status'], 'acquiring')
            self.assertTrue(record['owner'])


if __name__ == '__main__':
    unittest.main()
