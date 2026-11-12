# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import concurrent.futures
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from evaluation import (ContractViolation, HarnessFailure, ProviderFailure, failure_status,
                        digest, require_completed, tree_fingerprint)
from evaluation_gateway import CaseStore, make_handler, request_audit, rpc_result, schema_digest, stream_model
from environment_leases import EnvironmentLeases


class GatewayTest(unittest.TestCase):
    def test_terminal_routing_keeps_trial_fence_and_target_exclusion(self):
        observed = []
        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                observed.append((self.headers.get('X-Eval-Trial'),
                                 json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'tools': []}}).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        threading.Thread(target=backend.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as root:
                store = CaseStore(Path(root, 'state.sqlite'))
                leases = EnvironmentLeases(Path(root, 'leases.sqlite'))
                leases.operate('acquire', 'terminal', 'owner')
                with self.assertRaises(ValueError):
                    leases.operate('acquire', 'terminal', 'different')
                store.start({'trial': 'one', 'slot': 'terminal', 'owner': 'owner', 'target': 'pi'})
                with self.assertRaises(sqlite3.IntegrityError):
                    store.start({'trial': 'two', 'slot': 'appworld', 'owner': 'other', 'target': 'pi'})
                policy = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
                gateway = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(store, leases, policy,
                    'http://unused', 'http://unused', 'control', 'tool',
                    terminal='http://127.0.0.1:%d/mcp' % backend.server_port))
                threading.Thread(target=gateway.serve_forever, daemon=True).start()
                try:
                    def post(trial):
                        return urllib.request.urlopen(urllib.request.Request(
                            'http://127.0.0.1:%d/terminal/%s/mcp' % (gateway.server_port, trial),
                            json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).encode(),
                            {'Authorization': 'Bearer tool', 'X-Eval-Trial': 'forged'}), timeout=3)
                    with post('one') as response:
                        self.assertEqual(response.status, 200)
                    with self.assertRaises(urllib.error.HTTPError) as failure:
                        post('other')
                    self.assertEqual(failure.exception.code, 409)
                    failure.exception.close()
                    self.assertEqual(len(observed), 1)
                    self.assertEqual(observed[0][0], 'one')
                finally:
                    gateway.shutdown()
                    gateway.server_close()
        finally:
            backend.shutdown()
            backend.server_close()

    def test_lease_renewal_delay_is_restricted_to_synthetic_cases(self):
        with tempfile.TemporaryDirectory() as root:
            store = CaseStore(Path(root, 'state.sqlite'))
            case = {'trial': 'trial', 'slot': 'tau', 'target': 'pi', 'owner': 'owner', 'fault': 'model_delay'}
            with self.assertRaisesRegex(ValueError, 'restricted'):
                store.start(case)
            store.start({**case, 'slot': 'fixture'})
            self.assertEqual(json.loads(store.active(trial='trial')['configuration'])['fault'], 'model_delay')

    def test_original_large_schema_integer_reaches_provider_but_missing_constraints_are_rejected(self):
        observed = []
        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                observed.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                self.wfile.write(b'data: {"type":"response.completed","response":{"model":"fixture","usage":{"total_tokens":1}}}\n\n')
        provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
        threading.Thread(target=provider.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as root:
                store = CaseStore(Path(root, 'state.sqlite'))
                store.start({'trial': 'trial', 'slot': 'fixture', 'target': 'pi', 'owner': 'owner',
                             'input_key': 'fixture', 'declared_tool_namespace': 'catalog'})
                original = {'type': 'object', 'properties': {'limit': {'type': 'integer',
                            'minimum': 0.0, 'default': 9223372036854775807}}}
                with store.invocation(store.active(trial='trial'), 'mcp', {'catalog_tools': [{
                        'name': 'read', 'parameters': original, 'parameters_sha256': schema_digest(original),
                        'description_sha256': digest('Fixture')}]}) as record:
                    record['status'] = 'succeeded'
                policy = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
                gateway = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(store,
                    EnvironmentLeases(Path(root, 'leases.sqlite')), policy,
                    'http://127.0.0.1:%d' % provider.server_port, 'http://unused', 'control', 'tool'))
                threading.Thread(target=gateway.serve_forever, daemon=True).start()
                try:
                    received = json.loads(json.dumps(original))
                    received['properties']['limit'].update(minimum=0, default=9223372036854776000)
                    payload = {**policy['request'], 'input': 'fixture', 'tools': [{
                        'type': 'function', 'name': 'catalog__read', 'parameters': received, 'description': 'Fixture'}]}
                    url = 'http://127.0.0.1:%d/models/pi/v1/responses' % gateway.server_port
                    with urllib.request.urlopen(urllib.request.Request(url, json.dumps(payload).encode()), timeout=3) as response:
                        self.assertIn(b'response.completed', response.read())
                    self.assertEqual(observed[0]['tools'][0]['parameters'], original)
                    deadline = time.monotonic() + 2
                    while store.export('trial')[-1]['status'] == 'pending' and time.monotonic() < deadline:
                        time.sleep(.01)
                    audit = store.export('trial')[-1]['audit']
                    self.assertEqual(audit['violations'], [])
                    self.assertEqual(audit['schema_numeric_restorations'][0]['paths'], ['/properties/limit/default'])
                    self.assertEqual(audit['request_sha256'], digest(observed[0]))
                    self.assertEqual(audit['incoming_request_sha256'], digest(payload))
                    del received['properties']['limit']['minimum']
                    with self.assertRaises(urllib.error.HTTPError) as failure:
                        urllib.request.urlopen(urllib.request.Request(url, json.dumps(payload).encode()), timeout=3)
                    self.assertEqual(failure.exception.code, 422)
                    self.assertIn(b'original_mcp_schema_changed', failure.exception.read())
                    failure.exception.close()
                    self.assertEqual(len(observed), 1)
                finally:
                    gateway.shutdown()
                    gateway.server_close()
        finally:
            provider.shutdown()
            provider.server_close()

    def test_schema_numbers_ignore_spelling_without_losing_bounds_or_integer_precision(self):
        original = {'type': 'object', 'properties': {'price': {'minimum': 0.0,
                    'default': 9.223372036854776e18, 'maximum': 5.0}}, 'additionalProperties': False}
        javascript = {'additionalProperties': False, 'properties': {'price': {'minimum': 0,
                      'maximum': 5, 'default': 9223372036854776000}}, 'type': 'object'}
        self.assertEqual(schema_digest(original), schema_digest(javascript))
        self.assertNotEqual(schema_digest({'maximum': 9007199254740993}),
                            schema_digest({'maximum': 9007199254740992}))
        self.assertNotEqual(schema_digest({'default': True}), schema_digest({'default': 1}))
        self.assertNotEqual(schema_digest({'default': '1'}), schema_digest({'default': 1}))
        self.assertEqual(schema_digest({'default': -0.0}), schema_digest({'default': 0}))
        del javascript['properties']['price']['minimum']
        self.assertNotEqual(schema_digest(original), schema_digest(javascript))
        with self.assertRaises(ValueError):
            schema_digest({'default': float('inf')})

    def test_model_header_and_stream_timeouts_are_provider_failures_with_unknown_usage(self):
        for send_headers in [False, True]:
            with self.subTest(send_headers=send_headers), tempfile.TemporaryDirectory() as root:
                release = threading.Event()
                class Provider(BaseHTTPRequestHandler):
                    def log_message(self, *_): pass
                    def do_POST(self):
                        self.rfile.read(int(self.headers['Content-Length']))
                        if send_headers:
                            self.send_response(200)
                            self.send_header('Content-Type', 'text/event-stream')
                            self.end_headers()
                            self.wfile.write(b'data: {"type":"response.created"}\n\n')
                            self.wfile.flush()
                        release.wait(3)
                provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
                threading.Thread(target=provider.serve_forever, daemon=True).start()
                store = CaseStore(Path(root, 'state.sqlite'))
                store.start({'trial': 'trial', 'slot': 'fixture', 'target': 'pi', 'owner': 'owner'})
                policy = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
                policy['modelTimeoutSeconds'] = .15
                gateway = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(store,
                    EnvironmentLeases(Path(root, 'leases.sqlite')), policy,
                    'http://127.0.0.1:%d' % provider.server_port, 'http://unused', 'control', 'tool'))
                threading.Thread(target=gateway.serve_forever, daemon=True).start()
                try:
                    request = urllib.request.Request('http://127.0.0.1:%d/models/pi/v1/responses' % gateway.server_port,
                        json.dumps({**policy['request'], 'input': 'fixture'}).encode(),
                        {'Content-Type': 'application/json'})
                    try:
                        with urllib.request.urlopen(request, timeout=3) as response:
                            self.assertTrue(send_headers)
                            self.assertIn(b'response.created', response.read())
                    except urllib.error.HTTPError as error:
                        self.assertFalse(send_headers)
                        self.assertEqual(error.code, 502)
                    deadline = time.monotonic() + 2
                    while store.export('trial')[0]['status'] == 'pending' and time.monotonic() < deadline:
                        time.sleep(.01)
                    row = store.export('trial')[0]
                    self.assertEqual(row['status'], 'provider_error')
                    self.assertIsNotNone(row['finished'])
                    self.assertEqual(row['audit']['failure_stage'], 'upstream_read' if send_headers else 'upstream_headers')
                    self.assertTrue(row['audit']['usage_unknown'])
                    self.assertFalse(row['audit']['stream_completed'])
                    self.assertNotIn('usage', row['audit'])
                    self.assertGreaterEqual(row['audit']['duration_seconds'], .1)
                    self.assertEqual(store.finish('trial', 'owner')['status'], 'closed')
                finally:
                    release.set()
                    gateway.shutdown()
                    gateway.server_close()
                    provider.shutdown()
                    provider.server_close()

    def test_recovery_can_settle_only_finished_model_transport_with_terminal_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            store = CaseStore(Path(root, 'state.sqlite'))
            store.start({'trial': 'trial', 'slot': 'tau', 'target': 'pi', 'owner': 'owner'})
            case = store.active(trial='trial')
            terminal = {'session': 'session', 'turn': 'turn', 'status': 'failed'}
            with store.invocation(case, 'model', {}) as record:
                identity = store.export('trial')[0]['id']
                with self.assertRaises(ValueError):
                    store.settle_model_failure('trial', 'owner', identity, terminal)
            with self.assertRaises(ValueError):
                store.settle_model_failure('trial', 'other', identity, terminal)
            with self.assertRaises(ValueError):
                store.settle_model_failure('trial', 'owner', identity, {**terminal, 'status': 'running'})
            result = store.settle_model_failure('trial', 'owner', identity, terminal)
            self.assertEqual(result['status'], 'provider_error')
            self.assertTrue(result['usage_unknown'])
            self.assertEqual(store.export('trial')[0]['audit']['transport_recovery']['terminal'], terminal)
            with store.invocation(case, 'tool', {}):
                pass
            tool = store.export('trial')[-1]['id']
            with self.assertRaises(ValueError):
                store.settle_model_failure('trial', 'owner', tool, terminal)
            with self.assertRaisesRegex(ValueError, 'Unconfirmed'):
                store.finish('trial', 'owner')

    def test_mcp_canonical_route_and_redirect_failure_are_preserved(self):
        class Mcp(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                if self.path == '/mcp':
                    self.send_response(307)
                    self.send_header('Location', '/mcp/')
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{"jsonrpc":"2.0","id":1,"result":{"content":[],"isError":false}}')
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Mcp)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        try:
            for path, expected in [('/mcp/', 'succeeded'), ('/mcp', 'failed')]:
                with tempfile.TemporaryDirectory() as root:
                    store = CaseStore(Path(root, 'audit.sqlite'))
                    store.start({'trial': 'trial', 'slot': 'appworld', 'target': 'pi', 'owner': 'owner'})
                    policy = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
                    gateway = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(store,
                        EnvironmentLeases(Path(root, 'leases.sqlite')), policy, 'https://unused.invalid',
                        'http://127.0.0.1:%d%s' % (upstream.server_port, path), 'control', 'tool'))
                    threading.Thread(target=gateway.serve_forever, daemon=True).start()
                    try:
                        connection = http.client.HTTPConnection('127.0.0.1', gateway.server_port, timeout=3)
                        try:
                            for _ in range(3):
                                connection.request('GET', '/appworld/trial/mcp/')
                                response = connection.getresponse()
                                self.assertEqual(response.status, 405)
                                self.assertFalse(response.will_close)
                                self.assertEqual(response.read(), b'')
                                connection.request('GET', '/healthz')
                                response = connection.getresponse()
                                self.assertEqual(response.status, 200)
                                self.assertEqual(json.loads(response.read())['status'], 'ok')
                        finally:
                            connection.close()
                        with self.assertRaises(urllib.error.HTTPError) as declined:
                            urllib.request.urlopen('http://127.0.0.1:%d/appworld/trial/mcp/' % gateway.server_port,
                                                   timeout=3)
                        self.assertEqual(declined.exception.code, 405)
                        self.assertEqual(declined.exception.read(), b'')
                        self.assertEqual(declined.exception.headers['Allow'], 'POST, DELETE')
                        request = urllib.request.Request('http://127.0.0.1:%d/appworld/trial/mcp/' % gateway.server_port,
                            b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"fixture","arguments":{}}}',
                            {'Content-Type': 'application/json', 'Authorization': 'Bearer tool'})
                        try:
                            with urllib.request.urlopen(request, timeout=3) as response:
                                self.assertEqual(response.status, 200)
                                self.assertEqual(expected, 'succeeded')
                        except urllib.error.HTTPError as error:
                            self.assertEqual(error.code, 307)
                            self.assertEqual(expected, 'failed')
                        deadline = time.monotonic() + 2
                        while store.export('trial')[0]['status'] == 'pending' and time.monotonic() < deadline:
                            time.sleep(.01)
                        self.assertEqual(store.export('trial')[0]['status'], expected)
                    finally:
                        gateway.shutdown()
                        gateway.server_close()
        finally:
            upstream.shutdown()
            upstream.server_close()

    def test_model_sse_is_forwarded_before_completion_and_preserves_usage(self):
        first = b'data: {"type":"response.output_text.delta","delta":"hello"}\n\n'
        final = b'data: {"type":"response.completed","response":{"model":"fixture","usage":{"input_tokens":3,"output_tokens":2}}}\n\n'
        sent, release = threading.Event(), threading.Event()
        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                self.wfile.write(first)
                self.wfile.flush()
                release.wait(5)
                self.wfile.write(final)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        audit, chunks, statuses = {}, [], []
        def emit(chunk):
            chunks.append(chunk)
            sent.set()
        try:
            with concurrent.futures.ThreadPoolExecutor(1) as pool:
                future = pool.submit(stream_model, 'http://127.0.0.1:%d/responses' % server.server_port,
                    b'{}', {'Content-Type': 'application/json'}, 5,
                    lambda status, _type: statuses.append(status), emit, audit)
                self.assertTrue(sent.wait(3), 'Proxy buffered the first event')
                self.assertFalse(future.done(), 'Provider was expected to remain open')
                release.set()
                self.assertEqual(future.result(5), 'succeeded')
            self.assertEqual(statuses, [200])
            self.assertEqual(b''.join(chunks), first + final)
            self.assertEqual(audit['response_sha256'], hashlib.sha256(first + final).hexdigest())
            self.assertEqual(audit['usage']['input_tokens'], 3)
            self.assertTrue(audit['stream_completed'])
            self.assertGreaterEqual(audit['first_text_seconds'], audit['first_byte_seconds'])
        finally:
            release.set()
            server.shutdown()
            server.server_close()

    def test_environment_restart_fences_even_completed_io_and_preserves_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CaseStore(Path(directory, 'state.sqlite'))
            store.start({'slot': 'appworld', 'trial': 'old', 'target': 'pi', 'owner': 'owner'})
            with store.invocation(store.active(trial='old'), 'tool', {}) as record:
                record['status'] = 'succeeded'
            self.assertEqual(store.interrupt_active(), 1)
            self.assertEqual(store.owned('old', 'owner')['status'], 'interrupted')
            self.assertEqual(len(store.export('old')), 1)
            with self.assertRaises(ValueError):
                store.active(trial='old')
            with self.assertRaises(sqlite3.IntegrityError):
                store.start({'slot': 'appworld', 'trial': 'new', 'target': 'codex', 'owner': 'owner'})
            with self.assertRaisesRegex(ValueError, 'owner mismatch'):
                store.finish('old', 'other')
            self.assertEqual(store.finish('old', 'owner')['status'], 'closed')
            store.start({'slot': 'appworld', 'trial': 'new', 'target': 'codex', 'owner': 'owner'})

    def test_actual_request_contract_and_hash_only_prompt_export(self):
        profile = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
        request = {**profile['request'], 'instructions': 'native\nBUSINESS POLICY',
                   'input': [{'role': 'user', 'content': 'private test input'}],
                   'tools': [{'type': 'function', 'name': 'hands_retail__read',
                              'parameters': {'type': 'object'}, 'description': 'Read an order'}]}
        audit = request_audit(request, profile, 'BUSINESS POLICY')
        self.assertEqual(audit['violations'], [])
        self.assertEqual(audit['tools'][0]['canonical_name'], 'retail__read')
        self.assertNotIn('private test input', json.dumps(audit))
        self.assertNotIn('BUSINESS POLICY', json.dumps(audit))
        request['temperature'] = 1
        self.assertIn('temperature', request_audit(request, profile, 'BUSINESS POLICY')['violations'])
        request['input'] = 'x' * (profile['maxContextBytes'] + 1)
        self.assertIn('context_limit', request_audit(request, profile, 'BUSINESS POLICY')['violations'])

    def test_native_summary_may_reduce_but_cannot_exceed_the_shared_output_ceiling(self):
        policy = json.loads(Path(__file__).with_name('evaluation-native-profile.json').read_text())['gateway']
        request = {**policy['request'], 'input': [{'role': 'user', 'content': 'summary fixture'}],
                   'max_output_tokens': 2048}
        self.assertEqual(request_audit(request, policy, 'BUSINESS POLICY')['violations'], [])
        request['max_output_tokens'] = policy['request']['max_output_tokens'] + 1
        self.assertIn('max_output_tokens', request_audit(request, policy, '')['violations'])
        short = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())['gateway']
        request['max_output_tokens'] = 2048
        self.assertIn('max_output_tokens', request_audit(request, short, '')['violations'])

    def test_unconfirmed_tool_survives_restart_and_blocks_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'state.sqlite')
            store = CaseStore(path)
            store.start({'slot': 'appworld', 'trial': 'old', 'target': 'pi', 'owner': 'owner'})
            with self.assertRaises(TimeoutError):
                with store.invocation(store.active(trial='old'), 'tool', {'arguments_sha256': 'hash'}):
                    raise TimeoutError('external outcome unknown')
            restarted = CaseStore(path)
            with self.assertRaisesRegex(ValueError, 'Unconfirmed'):
                restarted.finish('old', 'owner')
            with self.assertRaises(sqlite3.IntegrityError):
                restarted.start({'slot': 'appworld', 'trial': 'new', 'target': 'codex', 'owner': 'other'})
            with self.assertRaisesRegex(ValueError, 'Unconfirmed'):
                with restarted.invocation(restarted.active(trial='old'), 'tool', {}):
                    self.fail('A second side effect must not start')

    def test_concurrent_calls_are_serial_and_old_trial_cannot_enter_new_case(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CaseStore(Path(directory, 'state.sqlite'))
            store.start({'slot': 'appworld', 'trial': 'old', 'target': 'pi', 'owner': 'owner'})
            old = store.active(trial='old')
            active = 0
            maximum = 0
            def execute(_):
                nonlocal active, maximum
                with store.invocation(old, 'tool', {}) as record:
                    active += 1
                    maximum = max(maximum, active)
                    time.sleep(.01)
                    active -= 1
                    record['status'] = 'succeeded'
            with concurrent.futures.ThreadPoolExecutor(4) as pool:
                list(pool.map(execute, range(4)))
            self.assertEqual(maximum, 1)
            store.finish('old', 'owner')
            store.start({'slot': 'appworld', 'trial': 'new', 'target': 'codex', 'owner': 'owner'})
            with self.assertRaisesRegex(ValueError, 'No active'):
                with store.invocation(old, 'tool', {}):
                    self.fail('Late request entered a new case')

    def test_initial_database_drift_is_rejected_before_case_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CaseStore(Path(directory, 'state.sqlite'))
            first = {'slot': 'tau', 'trial': 'first', 'target': 'pi', 'owner': 'owner',
                     'input_key': 'task0', 'initial_hash': 'clean'}
            store.start(first)
            store.finish('first', 'owner')
            with self.assertRaisesRegex(ValueError, 'Initial database'):
                store.start({**first, 'trial': 'next', 'initial_hash': 'dirty'})
            store.start({**first, 'trial': 'next'})

    def test_effective_tool_catalog_accepts_only_reversible_names(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CaseStore(Path(directory, 'state.sqlite'))
            store.start({'slot': 'fixture', 'trial': 'first', 'target': 'java', 'owner': 'owner', 'input_key': 'same'})
            case = store.active(trial='first')
            item = {'name': 'read', 'canonical_name': 'read', 'parameters_sha256': 'schema', 'description_sha256': 'description'}
            store.check_tools(case, {'violations': [], 'tools': [item]})
            store.check_tools(case, {'violations': [], 'tools': [{**item, 'name': 'hands_read'}]})
            changed = {'violations': [], 'tools': [{**item, 'parameters_sha256': 'different'}]}
            store.check_tools(case, changed)
            self.assertIn('effective_tool_catalog_drift', changed['violations'])

    def test_first_harness_cannot_establish_undeclared_tools_as_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CaseStore(Path(directory, 'state.sqlite'))
            store.start({'slot': 'fixture', 'trial': 'first', 'target': 'java', 'owner': 'owner',
                         'input_key': 'same', 'declared_tool_namespace': 'fixture'})
            case = store.active(trial='first')
            with store.invocation(case, 'mcp', {'catalog_tools': [{'name': 'lookup', 'parameters_sha256': 'schema',
                                                          'description_sha256': 'description'}]}) as record:
                record['status'] = 'succeeded'
            tool = {'name': 'fixture__lookup', 'canonical_name': 'fixture__lookup',
                    'parameters_sha256': 'schema', 'description_sha256': 'description'}
            extra = {**tool, 'name': 'agent_spawn', 'canonical_name': 'agent_spawn'}
            invalid = {'violations': [], 'tools': [tool, extra]}
            store.check_tools(case, invalid)
            self.assertIn('undeclared_or_missing_mcp_tool', invalid['violations'])
            valid = {'violations': [], 'tools': [{**tool, 'name': 'hands_fixture__lookup'}]}
            store.check_tools(case, valid)
            self.assertEqual(valid['violations'], [])
            changed = {'violations': [], 'tools': [{**tool, 'parameters_sha256': 'lost-minimum'}]}
            store.check_tools(case, changed)
            self.assertIn('original_mcp_schema_changed', changed['violations'])

    def test_error_taxonomy_does_not_exclude_harness_failures_as_infrastructure(self):
        self.assertEqual(failure_status(HarnessFailure()), 'harness_error')
        self.assertEqual(failure_status(ProviderFailure()), 'provider_error')
        self.assertEqual(failure_status(ContractViolation()), 'contract_violation')
        self.assertEqual(failure_status(RuntimeError()), 'unknown_error')
        with self.assertRaises(HarnessFailure):
            require_completed({'status': 'failed'})
        require_completed({'status': 'completed'})

    def test_mcp_sse_and_database_snapshot_preserve_empty_and_nested_content(self):
        message = {'jsonrpc': '2.0', 'id': 1, 'result': {'content': [], 'isError': False,
                   'structuredContent': {'nested': [None, {'value': 'a\\b\n'}]}}}
        self.assertEqual(rpc_result(('event: message\ndata: ' + json.dumps(message) + '\n\n').encode()), message)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'database.json')
            path.write_text(json.dumps({'rows': []}))
            initial = tree_fingerprint(directory)
            path.write_text(json.dumps({'rows': [1]}))
            self.assertNotEqual(initial['sha256'], tree_fingerprint(directory)['sha256'])


if __name__ == '__main__':
    unittest.main()
