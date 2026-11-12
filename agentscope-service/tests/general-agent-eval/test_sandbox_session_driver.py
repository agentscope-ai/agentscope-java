# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from sandbox_eval.session_driver import SessionDriver, validate_accounting
from sandbox_eval.exec_transport import ExecutionOutcomeUnknown


REQUESTS = [{'kind': 'model', 'status': 'succeeded', 'finished': 1,
             'audit': {'stream_completed': True,
                       'usage': {'total_tokens': 12, 'input_tokens': 10, 'output_tokens': 2}}},
            {'kind': 'tool', 'status': 'succeeded', 'finished': 1, 'audit': {}}]
BUDGET = {'usage': {'model_calls': 1, 'tool_calls': 1, 'total_tokens': 12,
                    'in_flight_calls': 0, 'unreported_calls': 0}}


class Bridge:
    closed = False

    async def close(self):
        self.closed = True


class Terminal:
    def __init__(self):
        self.unknown, self.bindings = {}, {}

    def bind(self, trial, bridge):
        if trial in self.bindings:
            raise ValueError('duplicate')
        self.bindings[trial] = bridge


class Client:
    def __init__(self, lose_send=False, missing_usage=False):
        self.calls = []
        self.lose_send, self.missing_usage = lose_send, missing_usage

    def api(self, path, body=None, key=None, method=None):
        self.calls.append(('api', path))
        if path == '/api/v1/agents':
            return {'agent': {'id': 'agent'}}
        if method == 'PUT':
            return {'limits': body}
        if path.endswith('/budget'):
            return {'usage': {**BUDGET['usage'], 'in_flight_calls': 1 if self.missing_usage else 0}}
        if path.endswith('/turns/turn'):
            return {'id': 'turn', 'session_id': 'session', 'status': 'completed'}
        raise AssertionError(path)

    def create(self, *args):
        self.calls.append(('create', args))
        return {'id': 'session'}

    def send(self, *args):
        self.calls.append(('send', args))
        if self.lose_send:
            raise TimeoutError('Admission response lost')
        return 'turn'

    def cancel(self, *args):
        self.calls.append(('cancel', args))
        return {'already_terminal': True}

    def output(self, path):
        return [{'type': 'agent.message', 'content': [{'type': 'text', 'text': 'Done'}]}]


class Gateway:
    instances = []

    def __init__(self, *args):
        self.operations = []
        self.instances.append(self)

    def snapshot(self):
        return REQUESTS

    def call(self, operation):
        self.operations.append(operation)
        return {'status': 'closed'}


class SessionDriverTest(unittest.IsolatedAsyncioTestCase):
    def driver(self, root, client):
        return SessionDriver({'evaluation_gateway_cluster_base': 'http://audit', 'tool_key': 'private'},
            {'name': 'pi', 'cluster_url': 'http://api', 'headers': {}}, 'qwen3.8-max',
            {'caseTimeoutSeconds': 60, 'turnTimeoutSeconds': 60, 'maxModelCalls': 10,
             'maxToolCalls': 10, 'maxTotalTokens': 10000, 'maxRecoveryAttempts': 0},
            1000, SimpleNamespace(slot='terminal', owner='owner'), Terminal(), Bridge(), root,
            'unique-trial', 'task', 'initial-hash', 'revision', 'environment', client)

    async def test_ready_requires_settled_accounting_and_keeps_credentials_out_of_artifacts(self):
        with tempfile.TemporaryDirectory() as root, patch('sandbox_eval.session_driver.EvaluationGateway', Gateway):
            driver = self.driver(root, Client())
            row = await driver.run('Create a file')
            self.assertEqual(row['status'], 'ready_for_verifier')
            self.assertIsNone(row['reward'])
            self.assertEqual(row['accounting']['total_tokens'], 12)
            self.assertTrue(driver.bridge.closed)
            self.assertEqual(Gateway.instances[-1].operations, ['finish'])
            self.assertNotIn('private', (Path(root) / 'definition.json').read_text())

    async def test_unknown_admission_is_never_retried(self):
        with tempfile.TemporaryDirectory() as root, patch('sandbox_eval.session_driver.EvaluationGateway', Gateway):
            client = Client(lose_send=True)
            driver = self.driver(root, client)
            with self.assertRaises(TimeoutError):
                await driver.run('Create a file')
            receipt = json.loads((Path(root) / 'session-result.json').read_text())
            self.assertEqual(receipt['session'], 'session')
            self.assertTrue(receipt['send_key'])
            with self.assertRaises(FileExistsError):
                await driver.run('Create a file')
            self.assertEqual(sum(name == 'send' for name, _ in client.calls), 1)
            self.assertEqual(Gateway.instances[-1].operations, [])

    async def test_completed_without_public_text_does_not_wait_for_deadline(self):
        with tempfile.TemporaryDirectory() as root, \
                patch('sandbox_eval.session_driver.EvaluationGateway', Gateway), \
                patch('sandbox_eval.session_driver.asyncio.sleep',
                      side_effect=AssertionError('A terminal turn must not be polled again')):
            client = Client()
            client.output = lambda path: None
            driver = self.driver(root, client)
            row = await driver.run('Create a file')
            self.assertEqual(row['status'], 'ready_for_verifier')
            self.assertEqual(row['accounting']['total_tokens'], 12)
            self.assertFalse(row['public_output_observed'])
            self.assertEqual(sum(name == 'send' for name, _ in client.calls), 1)
            self.assertFalse(any(name == 'cancel' for name, _ in client.calls))

    async def test_completed_turn_with_missing_usage_cannot_be_graded(self):
        with tempfile.TemporaryDirectory() as root, patch('sandbox_eval.session_driver.EvaluationGateway', Gateway):
            driver = self.driver(root, Client(missing_usage=True))
            with self.assertRaises(ExecutionOutcomeUnknown):
                await driver.run('Create a file')
            self.assertNotEqual(driver.row['status'], 'ready_for_verifier')
            self.assertEqual(Gateway.instances[-1].operations, [])

    def test_pending_business_tool_rejects_verifier(self):
        with self.assertRaises(ExecutionOutcomeUnknown):
            validate_accounting(BUDGET, REQUESTS + [{'kind': 'tool', 'status': 'pending', 'finished': None}])

    def test_explicit_version_isolates_comparison_without_trial_specific_keys(self):
        with tempfile.TemporaryDirectory() as root:
            driver = self.driver(root, Client())
            self.assertEqual(driver.comparison_key(), 'revision:task')
            driver.comparison_namespace = 'native-remediation-v1'
            versioned = driver.comparison_key()
            self.assertNotEqual(versioned, 'revision:task')
            driver.row.update(trial='other-unique-trial', target='codex')
            self.assertEqual(driver.comparison_key(), versioned)
            driver.row['task'] = 'other-task'
            self.assertNotEqual(driver.comparison_key(), versioned)

    def artifact_driver(self, root, *, process_status='exited', request_status='succeeded', turn_status='failed'):
        client = Client(missing_usage=True)
        original = client.api
        client.api = lambda path: ({'status': turn_status} if path.endswith('/turns/turn') else original(path))
        driver = self.driver(root, client)
        driver.row.update(session='session', turn='turn', status='harness_error', error='usage_unavailable')
        requests = [dict(REQUESTS[0], status='provider_error', audit={'usage': None}),
                    dict(REQUESTS[1], status=request_status)]

        class ArtifactGateway:
            operations = []

            def call(self, operation):
                self.operations.append(operation)
                return {'status': 'active' if operation == 'audit' else 'closed', 'requests': requests}

        class Environment:
            async def exec(self, command, timeout_sec):
                assert 'receipt.json' in command
                return SimpleNamespace(return_code=0, stdout=json.dumps({'operation': {'status': process_status}}))

        driver.gateway = ArtifactGateway()
        driver.bridge.root = '/tmp/managed-eval-terminal'
        driver.bridge.environment = Environment()
        return driver

    async def test_frozen_artifact_can_be_graded_with_unknown_model_usage_retained(self):
        with tempfile.TemporaryDirectory() as root:
            driver = self.artifact_driver(root)
            row = await driver.admit_terminal_artifact()
            self.assertEqual(row['status'], 'ready_for_artifact_verifier')
            self.assertEqual(row['execution_failure']['error'], 'usage_unavailable')
            self.assertEqual(row['budget_usage']['usage']['in_flight_calls'], 1)
            self.assertEqual(driver.gateway.operations, ['audit', 'finish'])
            self.assertIsNone(row['reward'])
            self.assertFalse(any(name in {'send', 'create', 'cancel'} for name, _ in driver.client.calls))
            with self.assertRaises(FileExistsError):
                await driver.admit_terminal_artifact()

    async def test_artifact_gate_rejects_running_turn_process_and_failed_tool(self):
        for kwargs in [{'turn_status': 'running'}, {'process_status': 'running'}, {'request_status': 'failed'}]:
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as root, \
                    patch('sandbox_eval.session_driver.asyncio.sleep'):
                driver = self.artifact_driver(root, **kwargs)
                with self.assertRaises(ExecutionOutcomeUnknown):
                    await driver.admit_terminal_artifact()
                self.assertNotIn('finish', driver.gateway.operations)
                self.assertIsNone(driver.row['reward'])

    async def test_artifact_gate_waits_for_exact_cancelled_turn_without_replaying(self):
        with tempfile.TemporaryDirectory() as root, \
                patch('sandbox_eval.session_driver.asyncio.sleep') as sleep:
            driver = self.artifact_driver(root)
            original = driver.client.api
            observed = []

            def delayed_cancel(path):
                if path.endswith('/turns/turn'):
                    observed.append(path)
                    return {'status': 'running' if len(observed) < 3 else 'cancelled'}
                return original(path)

            driver.client.api = delayed_cancel
            row = await driver.admit_terminal_artifact()
            self.assertEqual(row['status'], 'ready_for_artifact_verifier')
            self.assertEqual(observed, ['/v1/sessions/session/turns/turn'] * 3)
            self.assertEqual(sleep.await_count, 2)
            self.assertFalse(any(name in {'send', 'create', 'cancel'} for name, _ in driver.client.calls))
            self.assertEqual(driver.gateway.operations, ['audit', 'finish'])

    async def test_artifact_gate_never_fences_pending_model_request(self):
        with tempfile.TemporaryDirectory() as root, \
                patch('sandbox_eval.session_driver.asyncio.sleep'):
            driver = self.artifact_driver(root)
            def pending(operation):
                self.assertEqual(operation, 'audit')
                return {'requests': [{'kind': 'model', 'status': 'pending', 'finished': None}]}
            driver.gateway.call = pending
            with self.assertRaises(ExecutionOutcomeUnknown):
                await driver.admit_terminal_artifact()
            self.assertFalse((Path(root) / 'artifact-fence-intent.json').exists())


if __name__ == '__main__':
    unittest.main()
