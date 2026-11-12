#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Independent schema jobs, deployment grants, and actual Service process startup.

Requires an existing, dedicated local PostgreSQL Docker fixture and built jars/binaries.
Only this run's database, roles, processes, and files are removed. No external model/provider calls.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.client import HTTPConnection
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlencode, urlsplit
import uuid
from session_smoke_http import read_sse
from agent_service_postgres_fixture import managed_environment

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def request(url, method='GET', body=None, token=None, headers=None, raw_response=False):
    headers = {'Content-Type': 'application/json', **(headers or {})}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(url, data=None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode(),
                  method=method, headers=headers)
    try:
        with urlopen(req, timeout=15) as response:
            status, raw = response.status, response.read()
    except HTTPError as response:
        status, raw = response.code, response.read()
    if raw_response:
        return status, raw
    try:
        return status, json.loads(raw)
    except json.JSONDecodeError:
        return status, raw.decode()


def run(options):
    options.webhooks = getattr(options, "webhooks", False)
    options.managed_maintenance = getattr(options, 'managed_maintenance', False)
    options.managed_turn = options.managed_turn or options.managed_files or options.webhooks or options.managed_maintenance
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', options.owner):
        raise ValueError('PostgreSQL fixture owner must be an identifier')
    password = os.environ['MANAGED_AGENTS_TEST_POSTGRES_PASSWORD']
    suffix = uuid.uuid4().hex[:18]
    database = 'deployment_' + suffix
    folder = Path(tempfile.mkdtemp(prefix='managed-agents-deployment-'))
    processes, logs, roles = [], [], {}
    model_server, model_calls = None, []
    webhook_server, webhook_calls = None, []
    webhook_resume = threading.Event()
    launches = {}
    passed = False
    environment = {'PATH': os.environ['PATH'], 'TZ': 'UTC'}
    if os.environ.get('JAVA_HOME'):
        environment['JAVA_HOME'] = os.environ['JAVA_HOME']
    owner_dsn = 'postgres://' + options.owner + ':' + password + '@127.0.0.1:' + str(options.port) + '/' + database + '?sslmode=disable'
    jdbc = 'jdbc:postgresql://127.0.0.1:' + str(options.port) + '/' + database

    def sql(statement, selected_database=database):
        result = subprocess.run(['docker', 'exec', '-i', options.postgres_container, 'psql',
            '-X', '-v', 'ON_ERROR_STOP=1', '-U', options.owner, '-d', selected_database, '-At'],
            input=statement, text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError('Fixture SQL failed: ' + result.stderr)
        return result.stdout.strip()

    def policy_diagnostic(session_id):
        assert re.fullmatch(r'[a-f0-9-]{36}', session_id), session_id
        return sql("SELECT concat(p.failure_code, ':', p.valid_until, ':', p.claim_until) "
            "FROM portal.builder_portal_policy p JOIN portal.builder_portal_session s ON s.policy_id=p.id "
            "WHERE s.id='" + session_id + "'")

    def command(name, arguments, extra=None):
        with (folder / (name + '.log')).open('w') as log:
            subprocess.run(arguments, env={**environment, **(extra or {})}, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=120)

    def start(name, arguments, extra, health):
        log = (folder / (name + '.log')).open('w')
        logs.append(log)
        process = subprocess.Popen(arguments, env={**environment, **extra}, stdout=log,
                                   stderr=subprocess.STDOUT, cwd=folder)
        processes.append(process)
        launches[name] = (arguments, extra, health, process)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + ' exited; logs: ' + str(folder))
            try:
                if request(health)[0] == 200:
                    return
            except (URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(0.2)
        raise RuntimeError(name + ' startup timed out; logs: ' + str(folder))

    def jar(module):
        return str(ROOT / module / 'target' / ('managed-agents-' + module + '-' + options.version + '.jar'))

    sql('CREATE DATABASE ' + database + ';', 'postgres')
    try:
        sql('CREATE SCHEMA rt; CREATE SCHEMA cp;')
        control = str(ROOT / 'control-plane/bin/control-plane')
        command('schema-control', [control, '--storage-driver=postgres', '--migrate-only'],
                {'CONTROL_PLANE_STORAGE_DSN': owner_dsn + '&search_path=rt',
                 'CONTROL_PLANE_PRODUCT_DSN': owner_dsn})
        for component, module in [('agent-service', 'agent-service'), ('agent-harness-runtime', 'agent-harness-runtime'), ('runtime', 'agent-service')]:
            command('schema-' + component, [shutil.which('java'),
                '-Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain',
                '-cp', jar(module), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                '--component=' + component, '--url=' + jdbc, '--username=' + options.owner],
                {'MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD': password, 'MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_PASSWORD': password, 'MANAGED_AGENTS_RUNTIME_DB_PASSWORD': password})
        role_environment = {'PGHOST': '127.0.0.1', 'PGDATABASE': database,
                            'PGUSER': options.owner, 'PGPASSWORD': password}
        for component, prefix in [('control', 'CONTROL'),
                                  ('portal', 'AGENT_SERVICE'), ('data', 'AGENT_HARNESS_RUNTIME'), ('hands', 'TOOL_WORKER')]:
            role, secret = component + '_' + suffix, secrets.token_hex(24)
            roles[component] = (role, secret)
            role_environment[prefix + '_DB_USER'] = role
            role_environment[prefix + '_DB_PASSWORD'] = secret
        grant_arguments = ['docker', 'exec', '-i']
        for key, value in role_environment.items():
            grant_arguments += ['-e', key + '=' + value]
        grant_arguments += [options.postgres_container, 'sh', '-s']
        with (folder / 'schema-grants.log').open('w') as log:
            subprocess.run(grant_arguments, input=(ROOT / 'deploy/postgres-roles.sh').read_text(),
                           text=True, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
        for component, schema, ledger in [('control', 'cp', 'product_schema_migrations'),
                ('control', 'rt', 'schema_migrations'), ('portal', 'portal', 'builder_schema_migration'),
                ('data', 'dp', 'builder_schema_migration'),
                ('hands', 'ma', 'builder_schema_migration')]:
            role = roles[component][0]
            assert sql("SELECT has_schema_privilege('" + role + "','" + schema + "','CREATE')") == 'f'
            assert sql("SELECT has_table_privilege('" + role + "','" + schema + '.' + ledger + "','SELECT')") == 't'
            assert sql("SELECT has_table_privilege('" + role + "','" + schema + '.' + ledger + "','UPDATE')") == 'f'
        for component in ['portal', 'data', 'hands']:
            role = roles[component][0]
            assert sql("SELECT has_table_privilege('" + role + "','ma.turn','UPDATE')") == 't'
            assert sql("SELECT has_table_privilege('" + role + "','ma.builder_schema_migration','UPDATE')") == 'f'
            assert sql("SELECT has_schema_privilege('" + role + "','ma','CREATE')") == 'f'
        assert sql("SELECT has_schema_privilege('" + roles['portal'][0] + "','dp','USAGE')") == 'f'
        assert sql("SELECT has_schema_privilege('" + roles['data'][0] + "','portal','USAGE')") == 'f'
        unsafe_arguments = ['docker', 'exec', '-i']
        for key, value in {**role_environment, 'CONTROL_DB_USER': options.owner}.items():
            unsafe_arguments += ['-e', key + '=' + value]
        unsafe_arguments += [options.postgres_container, 'sh', '-s']
        with (folder / 'schema-grants-owner-rejected.log').open('w') as log:
            unsafe = subprocess.run(unsafe_arguments, input=(ROOT / 'deploy/postgres-roles.sh').read_text(),
                text=True, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        assert unsafe.returncode == 3, 'Migration owner must be rejected as a business role'
        if options.schema_only:
            passed = True
            print('Deployment schema smoke passed: actual migration entry points, business grants, '
                  'metadata write rejection, cross-schema isolation, and migration-owner rejection.')
            return
        environment.update(managed_environment(folder, suffix))
        ports = {name: port() for name in ['control', 'portal', 'data', 'gateway']}
        urls = {name: 'http://127.0.0.1:' + str(value) for name, value in ports.items()}
        common = {'MANAGED_AGENTS_LOCAL_DEV': 'false', 'MANAGED_AGENTS_JWT_SECRET': 'deployment-smoke-jwt-secret-at-least-32-bytes',
                  'MANAGED_AGENTS_INTERNAL_TOKEN': 'deployment-smoke-internal-token-at-least-32-bytes',
                  'MANAGED_AGENTS_VAULT_MASTER_KEY': 'deployment-smoke-vault-key-at-least-32-bytes',
                  'MANAGED_AGENTS_CONTROL_URL': urls['control'], 'MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL': urls['data'],
                  'MANAGED_AGENTS_AGENT_SERVICE_URL': urls['portal'],
                  'AGENT_SERVICE_URL': urls['portal'],
                  'MANAGED_AGENTS_WORKSPACE_ROOT': str(folder / 'workspaces'), 'MANAGED_AGENTS_JPA_DDL_AUTO': 'validate',
                  'MANAGED_AGENTS_SCHEMA_INIT': 'false', 'MANAGED_AGENTS_AGENT_SERVICE_SCHEMA_INIT': 'never',
                  'MANAGED_AGENTS_SEED_USERS': 'false'}
        if options.webhooks:
            common.update({'MANAGED_AGENTS_WEBHOOK_ALLOW_LOOPBACK_HTTP': 'true', 'MANAGED_AGENTS_WEBHOOK_SLOTS': '2'})
            class WebhookFixture(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass
                def do_POST(self):
                    size = int(self.headers['Content-Length'])
                    assert 0 < size <= 8192
                    webhook_calls.append({'raw': self.rfile.read(size), 'headers': dict(self.headers)})
                    self.send_response(204 if webhook_resume.is_set() else 503)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
            webhook_server = ThreadingHTTPServer(('127.0.0.1', 0), WebhookFixture)
            threading.Thread(target=webhook_server.serve_forever, daemon=True).start()

        def dsn(component):
            role, secret = roles[component]
            return 'postgres://' + role + ':' + secret + '@127.0.0.1:' + str(options.port) + '/' + database + '?sslmode=disable'

        start('control', [control, '--storage-driver=postgres', '--schema-mode=validate',
                '--enable-runtime-workers=true', '--grpc-bind-address=127.0.0.1:' + str(port()),
                '--http-bind-address=127.0.0.1:' + str(ports['control'])],
              {**common, 'CONTROL_PLANE_ENABLE_KUBERNETES': 'false', 'CONTROL_PLANE_STORAGE_DSN': dsn('control') + '&search_path=rt',
               'CONTROL_PLANE_PRODUCT_DSN': dsn('control'), 'CONTROL_PLANE_SEED_USERS': 'false',
               'CONTROL_PLANE_BOOTSTRAP_ADMIN': 'deployment-admin', 'CONTROL_PLANE_BOOTSTRAP_PASSWORD': 'deployment-test-password-123',
               'CONTROL_PLANE_WORKSPACE_ROOT': str(folder / 'workspaces'), 'CONTROL_PLANE_ARTIFACT_ROOT': str(folder / 'artifacts')},
              urls['control'] + '/healthz')
        command('control-readiness', [control, '--healthcheck', '--http-bind-address=127.0.0.1:' + str(ports['control'])])
        if options.managed_turn:
            class ModelFixture(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    assert self.path in ('/api/v1/services/aigc/text-generation/generation',
                        '/api/v1/services/aigc/multimodal-generation/generation'), self.path
                    assert self.headers.get('Authorization') == 'Bearer deployment-fake-model-key'
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    assert body['model'] == ('qwen-vl-deployment-smoke' if options.managed_files else 'qwen-deployment-smoke'), body
                    model_calls.append(body)
                    system = str(body.get('input', {}).get('messages', [{}])[0].get('content', ''))
                    result = {'request_id': 'deployment-model-' + str(len(model_calls)),
                        'output': {'choices': [{'message': {'role': 'assistant',
                            'content': 'NO_REPLY' if 'memory extraction assistant' in system else 'Deployment response'},
                            'finish_reason': 'stop'}]},
                        'usage': {'input_tokens': 7, 'output_tokens': 3, 'total_tokens': 10}}
                    raw = ('data: ' + json.dumps(result) + '\n\ndata: [DONE]\n\n').encode()
                    self.send_bytes(200, 'text/event-stream', raw)

                def send_bytes(self, status, kind, raw):
                    self.send_response(status)
                    self.send_header('Content-Type', kind)
                    self.send_header('Content-Length', str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)

            model_server = ThreadingHTTPServer(('127.0.0.1', 0), ModelFixture)
            threading.Thread(target=model_server.serve_forever, daemon=True).start()
        for name, module in [('portal', 'agent-service'), ('data', 'agent-harness-runtime'), ('gateway', 'gateway')]:
            extra = {**common, 'SERVER_PORT': str(ports[name]), 'SERVER_ADDRESS': '127.0.0.1'}
            if name == 'portal':
                extra.update({'MANAGED_AGENTS_AGENT_SERVICE_DB_URL': jdbc + '?currentSchema=portal',
                    'MANAGED_AGENTS_AGENT_SERVICE_DB_USER': roles[name][0], 'MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD': roles[name][1]})
            elif name == 'data':
                extra.update({'MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_URL': jdbc + '?currentSchema=dp',
                    'MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_USER': roles[name][0], 'MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_PASSWORD': roles[name][1],
                    'SPRING_PROFILES_ACTIVE': 'jdbc'})
                if name == 'data' and options.managed_turn:
                    extra.update({'DASHSCOPE_API_KEY': 'deployment-fake-model-key',
                        'DASHSCOPE_BASE_URL': 'http://127.0.0.1:' + str(model_server.server_port)})
            start(name, [shutil.which('java'), '-jar', jar(module)], extra, urls[name] + '/actuator/health')
        assert sql('SELECT count(*) FROM dp.builder_user') == '0', 'Execution components must not seed control-plane accounts'
        assert request(urls['gateway'] + '/api/internal/channels/config')[0] == 404
        status, login = request(urls['gateway'] + '/api/auth/login', 'POST',
                {'username': 'deployment-admin', 'password': 'deployment-test-password-123'})
        assert status == 200 and login.get('token'), (status, login)
        status, user = request(urls['gateway'] + '/api/auth/me', token=login['token'])
        assert status == 200 and user.get('isAdmin'), (status, user)
        assert request(urls['gateway'] + '/api/v1/agent-sessions')[0] == 401
        status, descriptor = request(urls['gateway'] + '/api/v1/me/scope', token=login['token'])
        assert status == 200, (status, descriptor)
        scope = next(
            item for item in descriptor['namespaces'] if item['name'].startswith('personal-'))
        status, machine = request(urls['gateway'] + '/api/v1/runtime-host-enrollments', 'POST',
            {'hostKey': 'deployment-host', 'tenant': scope['tenant'], 'namespace': scope['name']}, token=login['token'])
        assert status == 201 and machine.get('runtimeToken'), (status, machine)
        assert machine['namespace'] == scope['name'] and machine['tenant'] == scope['tenant']
        status, registration = request(urls['gateway'] + '/api/v1/runtime-hosts/register', 'POST',
            {'hostKey': machine['hostKey'], 'tenant': machine['tenant'], 'namespace': machine['namespace'],
             'poolName': 'deployment-pool', 'capacity': 1}, token=machine['runtimeToken'])
        assert status == 200 and registration.get('host'), (status, registration)
        registered_host = registration['host']
        status, renewed = request(urls['gateway'] + '/api/v1/runtime-hosts/' + registered_host['id'] + '/credentials',
            'POST', {'generation': registered_host['leaseGeneration']}, token=machine['runtimeToken'])
        assert status == 200 and renewed.get('runtimeToken') and renewed['hostKey'] == machine['hostKey'], (status, renewed)
        if options.managed_turn:
            scoped = {'X-Managed-Agents-Tenant': scope['tenant'], 'X-Managed-Agents-Namespace': scope['name']}
            environment_config = {}
            status, selected_environment = request(urls['gateway'] + '/api/environments', 'POST',
                {'name': 'deployment-environment', 'type': 'self_hosted',
                    'config': environment_config}, token=login['token'], headers=scoped)
            assert status == 200 and selected_environment.get('id'), (status, selected_environment)
            status, agent = request(urls['gateway'] + '/api/v1/agents', 'POST',
                {'tenant': scope['tenant'], 'namespace': scope['name'], 'agentKey': 'deployment-worker',
                 'displayName': 'Deployment worker', 'ownerType': 'user', 'binding': {'kind': 'managed'},
                 'definition': {'name': 'Deployment worker', 'system': 'Answer the deployment fixture request',
                     'model': 'dashscope:' + ('qwen-vl-deployment-smoke' if options.managed_files else 'qwen-deployment-smoke'), 'maxIters': 2,
                     'tools': [], 'mcpServers': [],
                     'defaultEnvironmentId': selected_environment['id']}}, token=login['token'], headers=scoped)
            assert status == 201 and agent.get('agent'), (status, agent)
            create = {'target': {'type': 'agent', 'id': agent['agent']['id']}}
            headers = {**scoped, 'Idempotency-Key': 'deployment-managed-create'}
            base = urls['gateway'] + '/api/v1/agent-sessions'
            status, session = request(base, 'POST', create, token=login['token'], headers=headers)
            assert status in (200, 201) and session.get('id'), (status, session)
            assert request(base, 'POST', create, token=login['token'], headers=headers)[1]['id'] == session['id']
            public = base + '/' + session['id']
            if options.webhooks:
                hook_request = {'url': 'http://127.0.0.1:' + str(webhook_server.server_port) + '/events',
                                'event_types': ['turn.completed']}
                hook_headers = {**scoped, 'Idempotency-Key': 'deployment-webhook'}
                status, hook = request(public + '/webhooks', 'POST', hook_request, token=login['token'], headers=hook_headers)
                assert status == 201 and hook.get('signing_secret'), (status, hook)
                assert request(public + '/webhooks', 'POST', hook_request, token=login['token'], headers=hook_headers)[1] == hook
                assert request(urls['gateway'] + '/api/internal/runtime/webhooks/claim', 'POST',
                    {'version': 1, 'workerId': 'denied-fixture', 'limit': 1})[0] == 404
            turn_input = {'message': 'hello deployment'}
            if options.managed_files:
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    status, ready = request(public, token=login['token'], headers=scoped)
                    if status == 200 and ready.get('state') == 'ready':
                        break
                    time.sleep(0.2)
                assert status == 200 and ready.get('state') == 'ready', (status, ready)
                status, capabilities = request(public + '/capabilities', token=login['token'], headers=scoped)
                assert status == 200 and all(capabilities['capabilities'][name] for name in ('files', 'file_input', 'artifacts')), (status, capabilities)
                assert request(public + '/files', headers=scoped)[0] == 401
                assert request(public + '/files', token=login['token'],
                    headers={**scoped, 'X-Managed-Agents-Namespace': 'unrelated'})[0] == 404
                private_files = urls['data'] + '/api/internal/runtime/managed/sessions/' + session['id'] + '/files'
                assert request(private_files)[0] == 401
                assert request(private_files, token=login['token'])[0] == 403
                assert request(private_files, headers={'X-Managed-Agents-Internal-Token': common['MANAGED_AGENTS_INTERNAL_TOKEN'],
                    'X-Managed-Agents-Internal-User': 'unrelated-owner'})[0] == 403
                binary = secrets.token_bytes(2 * 1024 * 1024 + 17)
                file_headers = {**scoped, 'Content-Type': 'application/octet-stream',
                    'X-File-Name': 'deployment.bin', 'Idempotency-Key': 'deployment-binary'}
                status, uploaded = request(public + '/files', 'POST', binary, token=login['token'], headers=file_headers)
                assert status == 201 and uploaded['size'] == len(binary), (status, uploaded)
                assert uploaded['sha256'] == hashlib.sha256(binary).hexdigest(), uploaded
                assert request(public + '/files', 'POST', binary, token=login['token'], headers=file_headers)[1]['file_id'] == uploaded['file_id']
                assert request(public + '/files', 'POST', binary + b'x', token=login['token'], headers=file_headers)[0] == 409
                assert request(public + '/files/' + uploaded['file_id'] + '/content', token=login['token'],
                    headers=scoped, raw_response=True) == (200, binary)
                assert request(public + '/files/' + uploaded['file_id'], token=login['token'], headers=scoped)[1]['file_id'] == uploaded['file_id']
                assert request(public + '/files?limit=101', token=login['token'], headers=scoped)[0] == 400
                destination = urlsplit(public + '/files')
                oversized = HTTPConnection(destination.hostname, destination.port, timeout=10)
                try:
                    oversized.putrequest('POST', destination.path)
                    for key, value in {**file_headers, 'Idempotency-Key': 'too-large',
                        'Authorization': 'Bearer ' + login['token'], 'Content-Length': str(16 * 1024 * 1024 + 1)}.items():
                        oversized.putheader(key, value)
                    oversized.endheaders()
                    reply = oversized.getresponse()
                    assert reply.status == 413, reply.status
                    reply.read()
                finally:
                    oversized.close()
                image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=')
                status, image_file = request(public + '/files', 'POST', image, token=login['token'],
                    headers={**file_headers, 'Idempotency-Key': 'image', 'X-File-Name': 'fixture.png', 'Content-Type': 'image/png'})
                assert status == 201, (status, image_file)
                status, listed = request(public + '/files?limit=1', token=login['token'], headers=scoped)
                assert status == 200 and len(listed['data']) == 1 and listed['has_more'], (status, listed)
                assert len(request(public + '/files?limit=1&offset=1', token=login['token'], headers=scoped)[1]['data']) == 1
                artifact_headers = {**scoped, 'Idempotency-Key': 'deployment-artifact'}
                status, published = request(public + '/artifacts', 'POST', {'file_id': uploaded['file_id']},
                    token=login['token'], headers=artifact_headers)
                assert status == 201 and published['file_id'] == uploaded['file_id'], (status, published)
                assert request(public + '/artifacts', 'POST', {'file_id': uploaded['file_id']},
                    token=login['token'], headers=artifact_headers)[1] == published
                assert request(public + '/artifacts', 'POST', {'file_id': image_file['file_id']},
                    token=login['token'], headers=artifact_headers)[0] == 409
                assert request(public + '/artifacts', 'POST', {'name': 'bad', 'uri': 'http://example.invalid/bad'},
                    token=login['token'], headers={**artifact_headers, 'Idempotency-Key': 'bad-uri'})[0] == 400
                assert request(public + '/artifacts/' + published['artifact_id'], token=login['token'], headers=scoped)[1]['data']['data']['file_id'] == uploaded['file_id']
                assert request(public + '/artifacts/' + published['artifact_id'] + '/content',
                    token=login['token'], headers=scoped, raw_response=True) == (200, binary)
                status, artifact_snapshot = request(public + '/snapshot?limit=20', token=login['token'], headers=scoped)
                assert status == 200 and len(artifact_snapshot['resources']['artifacts']['data']) == 1, (status, artifact_snapshot)
                snapshot_id = artifact_snapshot['snapshot_id']
                assert request(public + '/artifacts/' + published['artifact_id'], 'DELETE', token=login['token'], headers=scoped)[0] == 204
                assert request(public + '/artifacts/' + published['artifact_id'], 'DELETE', token=login['token'], headers=scoped)[0] == 204
                assert request(public + '/artifacts/' + published['artifact_id'], token=login['token'], headers=scoped)[0] == 404
                status, retained_artifact = request(public + '/artifacts/' + published['artifact_id'] + '?snapshot_id=' + snapshot_id,
                    token=login['token'], headers=scoped)
                assert status == 200 and retained_artifact['data']['data']['file_id'] == uploaded['file_id'], (
                    status, retained_artifact, policy_diagnostic(session['id']))
                assert request(public + '/artifacts', 'POST', {'file_id': uploaded['file_id']},
                    token=login['token'], headers=artifact_headers)[1] == published
                assert len(request(public + '/artifacts?limit=20', token=login['token'], headers=scoped)[1]['data']) == 0
                status, other = request(base, 'POST', create, token=login['token'],
                    headers={**scoped, 'Idempotency-Key': 'deployment-file-isolation'})
                assert status in (200, 201), (status, other)
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    status, ready = request(base + '/' + other['id'], token=login['token'], headers=scoped)
                    if status == 200 and ready.get('state') == 'ready':
                        break
                    time.sleep(0.2)
                assert status == 200 and ready.get('state') == 'ready', (status, ready)
                assert request(base + '/' + other['id'] + '/files/' + uploaded['file_id'], token=login['token'], headers=scoped)[0] == 404
                turn_input = {'input': [{'role': 'user', 'content': [
                    {'type': 'text', 'text': 'Read this local fixture image'},
                    {'type': 'file', 'file_id': image_file['file_id']}]}]}
            headers = {**scoped, 'Idempotency-Key': 'deployment-managed-turn'}
            status, turn = request(public + '/turns', 'POST', turn_input,
                token=login['token'], headers=headers)
            assert status == 202 and turn.get('id'), (status, turn)
            assert request(public + '/turns', 'POST', turn_input,
                token=login['token'], headers=headers)[1]['id'] == turn['id']
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                status, terminal = request(public + '/turns/' + turn['id'], token=login['token'], headers=scoped)
                if status == 200 and terminal['status'] in ('completed', 'failed', 'interrupted', 'cancelled'):
                    break
                time.sleep(0.2)
            assert status == 200 and terminal['status'] == 'completed', (status, terminal)
            status, events = request(public + '/events?limit=200', token=login['token'], headers=scoped)
            assert status == 200 and not events['has_more'], (status, events)
            kinds = [event['type'] for event in events['data']]
            assert kinds.count('turn.accepted') == 1 and kinds.count('turn.completed') == 1, kinds
            assert model_calls and kinds.count('usage.recorded') == len(model_calls), (kinds, len(model_calls))
            assert kinds.count('item.finalized') == 1 and kinds.index('item.finalized') < kinds.index('turn.completed'), kinds
            if options.managed_files:
                assert 'data:image/png;base64,' + base64.b64encode(image).decode() in json.dumps(model_calls[0]), model_calls[0]
                print('Managed file Gateway/Agent Service flow passed: binary >2 MiB, dedup/conflict, list/metadata/download, '
                    'size limit, Session isolation, artifact publication/deletion/snapshot/idempotency, '
                    'and referenced image delivered to the local fake model.')
                assert kinds.count('artifact.published') == 1 and kinds.count('artifact.deleted') == 1, kinds
            status, snapshot = request(public + '/snapshot?limit=200', token=login['token'], headers=scoped)
            assert status == 200 and snapshot['usage']['model_calls'] == len(model_calls), (status, snapshot)
            assert snapshot['usage']['totals']['inputTokens'] == len(model_calls) * 7, snapshot['usage']
            assert snapshot['usage']['totals']['outputTokens'] == len(model_calls) * 3, snapshot['usage']
            final_items = [value for value in snapshot['resources']['items']['data'] if value['data'].get('final_output')]
            assert len(final_items) == 1 and final_items[0]['type'] == 'item.finalized', final_items
            cursor = events['data'][0]['cursor']
            replay = read_sse(public + '/events/stream', {**scoped, 'Authorization': 'Bearer ' + login['token'],
                'Last-Event-ID': cursor}, len(events['data']) - 1)
            assert replay == events['data'][1:], 'Gateway SSE replay changed the public event sequence'
            print('Managed deployment flow passed: real control-plane account/resources, Gateway, Agent Service, '
                  'dataplane, DML-only accounts, command deduplication, terminal/events/usage; '
                  + str(len(model_calls)) + ' local fake model calls, ' + str(len(kinds)) + ' public events.')
        if options.webhooks:
            deadline = time.monotonic() + 20
            deliveries_url = public + '/webhooks/' + hook['id'] + '/deliveries'
            receipts = None
            while time.monotonic() < deadline:
                status, receipts = request(deliveries_url, token=login['token'], headers=scoped)
                if status == 200 and receipts['data'] and receipts['data'][0]['status'] == 'retrying':
                    break
                time.sleep(0.1)
            assert receipts and receipts['data'][0]['status'] == 'retrying', receipts
            delivery_id = receipts['data'][0]['id']
            # Stop the sender before Agent Service; resume both from the same role-scoped databases.
            for component in ('control', 'portal'):
                old = launches[component][3]
                old.terminate()
                old.wait(timeout=40)
            args, extra, health, _ = launches['portal']
            start('portal-restart', args, extra, health)
            webhook_resume.set()
            args, extra, health, _ = launches['control']
            start('control-restart', args, extra, health)
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                status, receipts = request(deliveries_url, token=login['token'], headers=scoped)
                if status == 200 and receipts['data'] and receipts['data'][0]['status'] == 'delivered':
                    break
                time.sleep(0.2)
            assert receipts['data'][0]['status'] == 'delivered' and receipts['data'][0]['id'] == delivery_id, receipts
            assert len(webhook_calls) >= 2, webhook_calls
            for callback in webhook_calls:
                callback_headers = {key.lower(): value for key, value in callback['headers'].items()}
                payload = json.loads(callback['raw'])
                assert set(payload) == {'id', 'type', 'session_id', 'cursor', 'created_at', 'event_url'}, payload
                assert payload['type'] == 'turn.completed' and payload['session_id'] == session['id'], payload
                assert callback_headers['x-managed-agents-delivery'] == delivery_id
                signed = callback_headers['x-managed-agents-timestamp'].encode() + b'.' + callback['raw']
                assert hmac.compare_digest(callback_headers['x-managed-agents-signature'], 'v1=' + hmac.new(hook['signing_secret'].encode(), signed, hashlib.sha256).hexdigest())
                assert not {'authorization', 'x-api-key', 'x-managed-agents-internal-token'} & callback_headers.keys(), callback_headers
                assert request(urls['gateway'] + payload['event_url'], token=login['token'], headers=scoped)[0] == 200
            status, listed = request(public + '/webhooks?limit=1', token=login['token'], headers=scoped)
            assert status == 200 and listed['data'][0]['id'] == hook['id'] and 'signing_secret' not in json.dumps(listed), (status, listed)
            assert request(public + '/webhooks/' + hook['id'], 'DELETE', token=login['token'], headers=scoped)[0] == 200
            assert sql("SELECT secret FROM portal.builder_portal_webhook WHERE id='" + hook['id'] + "'") == ''
            print('Webhook Gateway/Agent Service/Scheduler flow passed: actual control-plane permissions, four DML-only roles, '
                  'stable failed/retried delivery and signing secret across Agent Service/Scheduler restart, HMAC metadata only, '
                  'authenticated event read, private route isolation, secret-free listing and deletion.')
        if options.managed_maintenance:
            def read_public(url):
                status, value = request(url, token=login['token'], headers=scoped)
                if status != 200 and '/checkpoints?' in url:
                    private_status, private_value = request(urls['data'] + '/api/internal/runtime/managed/sessions/'
                        + session['id'] + '/checkpoints?limit=100', headers={
                            'X-Managed-Agents-Internal-Token': common['MANAGED_AGENTS_INTERNAL_TOKEN'],
                            'X-Managed-Agents-Internal-User': session['ownerId']})
                    raise AssertionError(('Public checkpoint read failed', status, value,
                        'private response', private_status, private_value, 'policy', policy_diagnostic(session['id'])))
                assert status == 200, (status, value)
                return value

            def wait_command(command_id):
                deadline = time.monotonic() + 35
                while time.monotonic() < deadline:
                    receipt = read_public(public + '/commands/' + command_id)
                    if receipt['command_status'] in ('completed', 'rejected'):
                        assert receipt['command_status'] == 'completed', receipt
                        return receipt
                    time.sleep(0.2)
                raise AssertionError(('Checkpoint command did not settle', receipt))

            def run_next(url, key):
                status, accepted = request(url + '/turns', 'POST', {'message': 'Continue the deployment fixture'},
                    token=login['token'], headers={**scoped, 'Idempotency-Key': key})
                assert status in (200, 202), (status, accepted)
                deadline = time.monotonic() + 35
                while time.monotonic() < deadline:
                    turn = read_public(url + '/turns/' + accepted['id'])
                    if turn['status'] in ('completed', 'failed', 'interrupted', 'cancelled', 'requires_action'):
                        assert turn['status'] == 'completed', turn
                        return turn
                    time.sleep(0.2)
                raise AssertionError(('Continued Turn did not settle', turn))

            before = read_public(public + '/budget')
            limit = {'max_model_calls': len(model_calls) + 20}
            status, saved = request(public + '/budget', 'PUT', limit, token=login['token'],
                headers={**scoped, 'If-Match': str(before['revision'])})
            assert status == 202 and saved['limits'] == limit, (status, saved)
            assert request(public + '/budget', 'PUT', limit, token=login['token'],
                headers={**scoped, 'If-Match': str(before['revision'])})[0] == 412
            page = read_public(public + '/checkpoints?limit=100')
            checkpoints = list(page['data'])
            watermark = page['as_of']
            for _ in range(32):
                if not page['has_more']:
                    break
                query = urlencode({'limit': 100, 'after': page['next_cursor'], 'through': watermark})
                page = read_public(public + '/checkpoints?' + query)
                assert page['as_of'] == watermark, page
                checkpoints.extend(page['data'])
            assert checkpoints and not page['has_more'], page
            checkpoint = checkpoints[-1]['checkpoint_id']
            restore_body = {'checkpoint_id': checkpoint, 'reason': 'Actual control-plane restore smoke'}
            recovery_headers = {**scoped, 'Idempotency-Key': 'deployment-checkpoint-restore'}
            status, restored = request(public + '/checkpoints/restore', 'POST', restore_body,
                token=login['token'], headers=recovery_headers)
            assert status == 202, (status, restored)
            wait_command(restored['command_id'])
            status, repeated = request(public + '/checkpoints/restore', 'POST', restore_body,
                token=login['token'], headers=recovery_headers)
            assert status == 202 and repeated['command_id'] == restored['command_id'], (status, repeated)
            run_next(public, 'deployment-after-restore')
            assert read_public(public + '/budget')['native_limits'] == limit
            status, target = request(base, 'POST', create, token=login['token'],
                headers={**scoped, 'Idempotency-Key': 'deployment-empty-fork-target'})
            assert status in (200, 201) and target.get('id'), (status, target)
            target_url = base + '/' + target['id']
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                target = read_public(target_url)
                if target.get('state') == 'ready':
                    break
                time.sleep(0.2)
            assert target.get('state') == 'ready', target
            status, forked = request(public + '/fork', 'POST', {'checkpoint_id': checkpoint,
                'reason': 'Actual control-plane fork smoke', 'target_session_id': target['id']},
                token=login['token'], headers={**scoped, 'Idempotency-Key': 'deployment-checkpoint-fork'})
            assert status == 202, (status, forked)
            wait_command(forked['command_id'])
            assert read_public(target_url + '/snapshot?limit=20')['usage']['model_calls'] == 0
            assert read_public(target_url + '/budget')['limits'] != limit
            run_next(target_url, 'deployment-after-fork')
            print('Managed maintenance Gateway/Control/Agent Service/Data flow passed: revisioned budget, stale revision rejection, '
                'fixed checkpoint pages, stable restore receipt, real frozen Agent fork, independent target usage/budget, '
                'and resumed source/target Turns; ' + str(len(model_calls)) + ' total local fake model calls.')
        assert sql('SELECT count(*) FROM dp.builder_user') == '0', 'Execution must use the frozen owner without local account initialization'
        passed = True
        print('Deployment process smoke passed: explicit schema jobs, four DML-only roles, '
              'one Go control plane plus Agent Service/Dataplane/Gateway and optional Tool Worker, Gateway login/private-route isolation, Go readiness, '
              'and scoped machine enrollment/registration/renewal through Gateway + Agent Service. No external model calls.')
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()
        if webhook_server:
            webhook_server.shutdown()
            webhook_server.server_close()
        if model_server:
            model_server.shutdown()
            model_server.server_close()
        sql('DROP DATABASE ' + database + ' WITH (FORCE);', 'postgres')
        for role, _ in roles.values():
            sql('DROP ROLE IF EXISTS ' + role + ';', 'postgres')
        if passed:
            shutil.rmtree(folder)
        else:
            print('Deployment fixture logs retained at ' + str(folder))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='2.1.0-SNAPSHOT')
    parser.add_argument('--postgres-container', required=True)
    parser.add_argument('--owner', default='runtime_fixture')
    parser.add_argument('--port', type=int, default=60910)
    parser.add_argument('--schema-only', action='store_true')
    parser.add_argument('--managed-turn', action='store_true', help='Run a Managed Turn with the real control plane and a local fake model')
    parser.add_argument('--managed-files', action='store_true', help='Verify Managed Session file APIs and referenced input through Gateway')
    parser.add_argument('--webhooks', action='store_true', help='Verify durable Agent Service subscription and integrated Go sending across restart')
    parser.add_argument('--managed-maintenance', action='store_true', help='Verify budget/checkpoint/restore/fork with actual control-plane resources')
    run(parser.parse_args())
