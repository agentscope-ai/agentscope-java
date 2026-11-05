# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Exercise the packaged checker against the public Session API contract."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

SERVICE = Path(__file__).resolve().parents[2]


class SmokeTests(unittest.TestCase):
    def exercise(self, local_dev, transient=False):
        requests = []
        failures = set()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length']))) if self.command == 'POST' else None
                requests.append((self.command, self.path, body, self.headers.get('Idempotency-Key')))
                status, result = 200, {}
                if self.path == '/api/internal/channels/config':
                    status = 404
                elif self.path == '/api/auth/login':
                    if body['password'] == 'smoke-password-123':
                        result = {'token': 'fixture-token'}
                    else:
                        status = 401
                elif self.path == '/api/auth/me':
                    result = {'isAdmin': True}
                elif self.path == '/api/v1/me/scope':
                    result = {'namespaces': [{'tenant': 'fixture-tenant', 'name': 'fixture-namespace'}]}
                elif self.path in ('/healthz', '/actuator/health'):
                    result = {'status': 'UP'}
                elif self.path == '/api/v1/agents' and self.command == 'POST':
                    if (self.headers.get('X-AgentScope-Tenant') != 'fixture-tenant'
                            or self.headers.get('X-AgentScope-Namespace') != 'fixture-namespace'):
                        self.send_error(403)
                        return
                    result = {'agent': {'id': 'agent-1'}}
                elif self.path == '/api/v1/agents/agent-1':
                    result = {'agent': {'id': 'agent-1'}}
                elif self.path == '/api/environments':
                    result = {'id': 'environment-1'}
                elif self.path == '/v1/sessions':
                    result = {'id': 'session-1'}
                elif self.path == '/v1/sessions/session-1':
                    result = {'id': 'session-1', 'status': 'idle'}
                elif self.path == '/v1/sessions/session-1/events' and self.command == 'POST':
                    if self.headers.get('AgentScope-Extensions') != 'turn-inputs-v1':
                        self.send_error(400)
                        return
                    result = {'data': [{'id': 'event-1', 'agentscope_service': {'turn_id': 'turn-1'}}]}
                elif self.path == '/v1/sessions/session-1/turns/turn-1':
                    result = {'id': 'turn-1', 'status': 'completed'}
                elif self.path == '/v1/sessions/session-1/turns/turn-1/output':
                    result = {'data': [{'text': 'release smoke passed'}], 'output_available': True}
                elif self.path == '/v1/sessions/session-1/events?limit=100':
                    result = {'events': [{'type': 'turn.completed'}]}
                else:
                    status, result = 404, {'error': 'Unsupported API'}
                if transient and self.path in ('/v1/sessions', '/v1/sessions/session-1/events?limit=100') and self.path not in failures:
                    failures.add(self.path)
                    status, result = 503, {'error': 'Starting up'}
                raw = json.dumps(result).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                env = Path(directory) / '.env'
                env.write_text('BUILDER_LOCAL_DEV=' + str(local_dev).lower() + '\n'
                               'CONTROL_PLANE_BOOTSTRAP_PASSWORD=smoke-password-123\n')
                command = [sys.executable, str(SERVICE / 'release/smoke.py'),
                           '--base', 'http://127.0.0.1:' + str(server.server_port),
                           '--env-file', str(env), '--state', str(Path(directory) / 'state.json')]
                if not local_dev:
                    command += ['--environment-id', 'environment-1']
                result = subprocess.run(command + ['--model-turn'], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                result = subprocess.run(command + ['--resume'], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                posts = [r for r in requests if r[0] == 'POST']
                session = next(r for r in posts if r[1] == '/v1/sessions')
                self.assertEqual(session[2], {'agent': 'agent-1', 'environment_id': 'environment-1'})
                self.assertTrue(session[3])
                session_requests = [r for r in posts if r[1] == '/v1/sessions']
                self.assertTrue(all(r[2:] == session[2:] for r in session_requests))
                turn = next(r for r in posts if r[1].endswith('/events'))
                self.assertTrue(turn[3])
                self.assertEqual(turn[2], {'events': [{
                    'type': 'user.message',
                    'content': [{'type': 'text', 'text': 'Reply with: release smoke passed'}],
                    'agentscope_service': {'intent': 'new_turn'}}]})
                self.assertFalse(any(r[1].startswith('/api/sessions') for r in requests))
                environments = [r for r in posts if r[1] == '/api/environments']
                self.assertEqual(len(environments), 1 if local_dev else 0)
                if environments:
                    self.assertEqual(environments[0][2]['type'], 'local')
                agent = next(r for r in posts if r[1] == '/api/v1/agents')
                self.assertEqual(agent[2]['definition']['defaultEnvironmentId'], 'environment-1')
                self.assertEqual(any(r[1] == '/api/auth/login' for r in requests), not local_dev)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_production_identity_and_turn_survive_restart(self):
        self.exercise(False, transient=True)

    def test_local_development_creates_local_environment_without_login(self):
        self.exercise(True)
