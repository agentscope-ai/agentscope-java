#!/usr/bin/env python3
"""Real Agent Service/Go/PostgreSQL native execution with an authenticated fake Host.

Seed only an isolated control-plane fixture, stop that process, then attach the
runtime configured with --agent-service-url matching --agent-service-port. The fake
Host uses real machine and attempt credentials. --exercise-daemon runs the real
Host binary with a local stdio Codex fixture and no external model calls.
"""

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from threading import Thread
import uuid
from urllib.error import URLError

from orchestration_runtime_host_smoke import ROOT, SERVICE_SECRET, SIGNING_SECRET, credential, request
from orchestration_runtime_agent_service_smoke import digest, user_token
from agent_service_postgres_fixture import agent_service_arguments
from session_smoke_http import read_sse


def seed(control_url, destination, mode, team=False):
    scope = {'tenant': 'fixture-tenant', 'namespace': 'fixture-space'}
    name = 'native-' + uuid.uuid4().hex[:10]
    status, result = request(control_url + '/api/v1/runtime-profiles', 'POST',
        {**scope, 'name': name, 'provider': 'codex', 'configuration': {'model': 'fixture-model'}})
    assert status == 200, (status, result)
    profile = result['profile']
    status, result = request(control_url + '/api/v1/runtime-pools', 'POST', {**scope, 'name': name})
    assert status == 200, (status, result)
    pool = result['pool']
    status, result = request(control_url + '/api/v1/agents', 'POST', {**scope,
        'agentKey': name, 'displayName': 'Native fixture', 'binding': {'kind': 'hosted-runtime',
        'configuration': {'runtimeProfileId': profile['id'], 'runtimePoolId': pool['id']}}})
    assert status == 201, (status, result)
    agent, policy = result['agent'], result['policy']
    frozen_teams = {}
    target_type, target_ref = 'agent', agent['id']
    if team:
        assert mode == 'job', 'Team fixture uses Job mode'
        status, result = request(control_url + '/api/v1/teams', 'POST',
            {**scope, 'name': name, 'leaderAgentId': agent['id'], 'status': 'active'})
        assert status == 201, (status, result)
        roster = result['team']
        frozen_teams = {roster['id']: roster}
        target_type, target_ref = 'team', roster['id']
    session_id = str(uuid.uuid4())
    configuration = {'session': {'id': session_id, 'ownerId': 'fixture-user'},
        'contract': {'public_session_id': session_id, 'agents': [agent['id']],
            'teams': frozen_teams, 'policies': {agent['id']: policy}, 'runtime_profiles': {profile['id']: profile},
            'runtime_pools': {pool['id']: pool}, 'endpoint': {'id': session_id, **scope,
            'targetType': target_type, 'targetRef': target_ref, 'invocationMode': mode}},
        'unicode': '🧰 世界', 'number': 9007199254740993, 'optional': None}
    destination.write_text(json.dumps({'configuration': configuration, 'pool_name': pool['name'],
        'agent_id': agent['id'], 'mode': mode, 'runtime_kind': 'team' if team else 'hosted-runtime'}), encoding='utf-8')
    print(json.dumps({'resource_fixture_seeded': True, 'mode': mode, 'fixture_file': str(destination)}))


def run(version, runtime_url, agent_service_port, fixture_file, exercise_cancel=False, exercise_daemon=False, approval=None, public_create=False, agent_service_dsn=None):
    fixture = json.loads(fixture_file.read_text(encoding='utf-8'))
    configuration = fixture['configuration']
    runtime_kind = fixture.get('runtime_kind', 'hosted-runtime')
    target = configuration['contract']['endpoint']
    if runtime_kind == 'team' and (exercise_daemon or approval):
        raise ValueError('Team fixture currently uses the authenticated manual Host path')
    session_id = configuration['session']['id']
    folder = Path(tempfile.mkdtemp(prefix='managed-agents-native-runtime-smoke-'))
    agent_service_url = 'http://127.0.0.1:' + str(agent_service_port)
    environment = {'PATH': os.environ['PATH'], 'TZ': 'UTC', 'MANAGED_AGENTS_INTERNAL_TOKEN': SERVICE_SECRET,
        'MANAGED_AGENTS_JWT_SECRET': SIGNING_SECRET}
    if os.environ.get('JAVA_HOME'):
        environment['JAVA_HOME'] = os.environ['JAVA_HOME']
    processes, handles, passed = [], [], False
    configuration_control_url = 'http://127.0.0.1:1'
    configuration_control = None
    agent_service_storage_arguments = agent_service_arguments(version, agent_service_dsn or os.environ.get('MANAGED_AGENTS_TEST_POSTGRES_DSN'), folder, environment)

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start_agent_service():
        handle = (folder / 'portal.log').open('a')
        handles.append(handle)
        jar = ROOT / 'agent-service/target' / ('managed-agents-agent-service-' + version + '.jar')
        process = subprocess.Popen([shutil.which('java'), '-jar', str(jar),
            '--server.address=127.0.0.1', '--server.port=' + str(agent_service_port),
            '--spring.profiles.active=test', '--managed-agents.local-dev=false',
            '--managed-agents.jwt.secret=' + SIGNING_SECRET, '--managed-agents.vault.master-key=native-runtime-smoke-only',
            '--managed-agents.orchestration-runtime-url=' + runtime_url, '--managed-agents.control-plane-url=' + configuration_control_url,
            '--managed-agents.agent-service.turn-delivery-poll-ms=200'] + agent_service_storage_arguments,
            env=environment, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Agent Service exited; inspect ' + str(handle.name))
            try:
                if request(agent_service_url + '/actuator/health')[0] == 200:
                    return process
            except (URLError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError('Agent Service did not become healthy')

    try:
        assert request(runtime_url + '/readyz')[0] == 200
        now = int(time.time() * 1000)
        context = {'version': 1, 'tenant': 'fixture-tenant', 'namespace': 'fixture-space',
            'applicationId': None, 'sessionId': session_id, 'turnId': None,
            'commandId': 'bootstrap:' + session_id, 'traceId': str(uuid.uuid4()),
            'actor': {'user': 'fixture-user', 'key_hash': '', 'internal': False},
            'policyVersion': 'fixture', 'permissionExpiresAt': now + 120000}
        bootstrap = {'context': context, 'runtimeKind': runtime_kind,
            'configurationRevision': digest(configuration), 'configuration': configuration}
        policy = {'tenant': context['tenant'], 'namespace': context['namespace'],
            'humans': {'fixture-user': ['read', 'invoke', 'interact', 'cancel', 'approve']},
            'credentials': [], 'application': None, 'enabled': True, 'valid_until': now + 120000}
        service = {'X-Managed-Agents-Internal-Token': SERVICE_SECRET}
        view = {'id': session_id, 'status': 'active', 'runtimeKind': runtime_kind,
            'target': {'type': target['targetType'], 'id': target['targetRef']}}
        creation_headers = {'Authorization': 'Bearer ' + user_token(), 'Idempotency-Key': 'create:' + session_id}
        if public_create:
            policy['scope_id'] = digest({'fixture_policy': session_id})
            class ConfigurationFixture(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def send(self, status, body):
                    raw = json.dumps(body).encode()
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)

                def do_POST(self):
                    body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
                    assert body['target']['id'] == target['targetRef']
                    assert self.headers.get('Authorization') == creation_headers['Authorization']
                    assert self.headers.get('X-Managed-Agents-Internal-Token') == SERVICE_SECRET
                    assert self.path == '/api/internal/runtime-admissions/sessions'
                    self.send(200, {**view, 'tenant': context['tenant'], 'admissionActor': context['actor']})

                def do_GET(self):
                    assert self.headers.get('X-Managed-Agents-Internal-Token') == SERVICE_SECRET
                    assert self.path.startswith('/api/internal/runtime-sessions/' + session_id + '/projection')
                    self.send(200, {'managed': False, 'runtimeKind': runtime_kind,
                        'view': view, 'policy': policy, 'resolved': configuration})
            configuration_control = ThreadingHTTPServer(('127.0.0.1', 0), ConfigurationFixture)
            Thread(target=configuration_control.serve_forever, daemon=True).start()
            configuration_control_url = 'http://127.0.0.1:' + str(configuration_control.server_port)
        agentService = start_agent_service()
        if public_create:
            body = {'target': {'type': target['targetType'], 'id': target['targetRef']}}
            status, ready = request(agent_service_url + '/api/v1/agent-sessions', 'POST', body, creation_headers)
            assert status == 200 and ready['state'] == 'ready', (status, ready)
            assert request(agent_service_url + '/api/v1/agent-sessions', 'POST', body, creation_headers)[1]['id'] == session_id
            configuration_control.shutdown()
            configuration_control.server_close()
            configuration_control = None
        else:
            status, ready = request(agent_service_url + '/api/internal/runtime/sessions/provision', 'POST',
                {'bootstrap': bootstrap, 'policy': policy, 'publicView': view}, service)
        assert status == 200 and ready['state'] == 'ready', (status, ready)
        if exercise_daemon:
            host_key = 'daemon-fixture-' + uuid.uuid4().hex[:12]
            binary = folder / 'fixture-codex'
            shutil.copyfile(ROOT / 'tests/fixtures/codex_app_server.py', binary)
            binary.chmod(0o750)
            ready_file = folder / 'host-ready.json'
            host_log = (folder / 'host.log').open('a')
            handles.append(host_log)
            host_environment = {**environment, 'FAKE_CODEX_REQUEST_LOG': str(folder / 'provider-requests.jsonl'),
                'FAKE_CODEX_WAIT_FOR_CANCEL': 'true' if exercise_cancel else 'false',
                'FAKE_CODEX_APPROVAL': approval or ''}
            host = subprocess.Popen([str(ROOT / 'control-plane/bin/managed-agents-runtime-host'),
                '--control-plane=' + agent_service_url, '--internal-token=' + credential(host_key),
                '--tenant=' + context['tenant'], '--namespace=' + context['namespace'],
                '--host-key=' + host_key, '--pool=' + fixture['pool_name'], '--capacity=1',
                '--workspace-root=' + str(folder / 'workspace'), '--state-root=' + str(folder / 'state'),
                '--codex-binary=' + str(binary), '--providers=codex', '--ready-file=' + str(ready_file),
                '--poll-interval=200ms', '--heartbeat-interval=1s', '--lease-ttl=' + ('30s' if approval else '6s')],
                env=host_environment,
                stdout=host_log, stderr=subprocess.STDOUT)
            processes.append(host)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and not ready_file.exists():
                assert host.poll() is None, 'Host daemon exited; inspect ' + str(folder)
                time.sleep(0.2)
            assert ready_file.exists(), 'Host registration timed out'
            credential_cache = folder / 'state/host-credential.json'
            assert credential_cache.exists(), 'Host did not persist its machine renewal'
            cache_record = json.loads(credential_cache.read_text())
            encoded_claims = cache_record['runtimeToken'][5:].split('.')[0]
            renewed_claims = json.loads(base64.urlsafe_b64decode(encoded_claims + '=' * (-len(encoded_claims) % 4)))
            assert renewed_claims['hostKey'] == host_key and renewed_claims['expiresAt'] > time.time() + 24*3600
            ready_before_duplicate = ready_file.read_bytes()
            duplicate = subprocess.run(host.args, env=environment, capture_output=True,
                text=True, timeout=10)
            assert duplicate.returncode == 2 and 'state directory is already in use' in duplicate.stderr, \
                'Duplicate daemon was not rejected before registration'
            assert ready_file.read_bytes() == ready_before_duplicate, 'Duplicate daemon changed Host registration'
        else:
            host_headers = {'Authorization': 'Bearer ' + credential()}
            status, registered = request(agent_service_url + '/api/v1/runtime-hosts/register', 'POST',
                {'hostKey': 'fixture-host', 'tenant': context['tenant'], 'namespace': context['namespace'],
                'poolName': fixture['pool_name'], 'capacity': 1, 'capabilities': {'providers': {'codex': 'fixture/1'}}}, host_headers)
            assert status == 200, (status, registered)
            host = registered['host']
        public = agent_service_url + '/api/v1/agent-sessions/' + session_id
        caller = {'Authorization': 'Bearer ' + user_token(), 'Idempotency-Key': 'native-fixture-turn'}
        artifact_record, artifact_bytes = None, None
        input_value = {'message': '你好 🧰', 'number': 9007199254740993}
        status, turn = request(public + '/turns', 'POST', input_value, caller)
        assert status == 202, (status, turn)
        turn_id = turn['id']
        assert len(turn_id) == 64
        capabilities_url = public + '/turns/' + turn_id + '/capabilities'
        status, capabilities = request(capabilities_url, headers=caller)
        assert status == 200 and capabilities['runtime_kind'] == runtime_kind, (status, capabilities)
        assert capabilities['supported_commands']['cancel'] and not capabilities['supported_commands']['steer']
        assert not capabilities['supported_commands']['inject'] and not capabilities['supported_commands']['resume']
        assert request(public + '/turns', 'POST', input_value, caller)[1]['id'] == turn_id
        cancel_receipt = None
        approval_receipt = None
        if approval:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                state = request(public + '/turns/' + turn_id, headers=caller)[1]
                pending = [event for event in request(public + '/events?limit=1000', headers=caller)[1]['data']
                    if event['type'] == 'required_action.created']
                if state.get('status') == 'requires_action' and pending:
                    break
                time.sleep(0.2)
            assert state.get('status') == 'requires_action' and len(pending) == 1, (state, pending)
            approval_id = pending[0]['data']['request_id']
            assert 'actions' in request(capabilities_url, headers=caller)[1]['available_commands']
            snapshot = request(public + '/turns/' + turn_id + '/snapshot?limit=100', headers=caller)[1]
            actions = snapshot['resources']['required_actions']['data']
            assert len(actions) == 1 and actions[0]['data']['request_id'] == approval_id, actions
            # Cross a physical lease interval and restart Agent Service while provider waits.
            time.sleep(31)
            assert host.poll() is None and request(public + '/turns/' + turn_id, headers=caller)[1]['status'] == 'requires_action'
            stop(agentService)
            agentService = start_agent_service()
            approval_headers = {**caller, 'Idempotency-Key': 'native-fixture-approval'}
            action = {'answers': [{'request_id': approval_id, 'allow': approval == 'approve'}]}
            status, approval_receipt = request(public + '/turns/' + turn_id + '/actions', 'POST', action, approval_headers)
            assert status == 202, (status, approval_receipt)
            assert request(public + '/turns/' + turn_id + '/actions', 'POST', action, approval_headers)[1]['command_id'] == approval_receipt['command_id']
            opposite = {'answers': [{'request_id': approval_id, 'allow': approval != 'approve'}]}
            assert request(public + '/turns/' + turn_id + '/actions', 'POST', opposite, approval_headers)[0] == 409
        if exercise_daemon and exercise_cancel:
            deadline = time.monotonic() + 30
            provider_log = folder / 'provider-requests.jsonl'
            while time.monotonic() < deadline:
                if provider_log.exists() and 'fixture/stream_ready' in provider_log.read_text():
                    break
                assert host.poll() is None, 'Host exited before provider execution'
                time.sleep(0.2)
            assert provider_log.exists() and 'fixture/stream_ready' in provider_log.read_text(), 'Provider did not start'
            cancel_headers = {**caller, 'Idempotency-Key': 'native-fixture-cancel'}
            status, cancel_receipt = request(public + '/turns/' + turn_id + '/cancel', 'POST', headers=cancel_headers)
            assert status == 202, (status, cancel_receipt)
            assert request(public + '/turns/' + turn_id + '/cancel', 'POST', headers=cancel_headers)[1]['command_id'] == cancel_receipt['command_id']
        if not exercise_daemon:
            host_base = agent_service_url + '/api/v1/runtime-hosts/' + host['id'] + '/execution-attempts'
            lease = {'tenant': context['tenant'], 'namespace': context['namespace'],
                'runtimePoolName': fixture['pool_name'], 'generation': host['leaseGeneration'],
                'leaseOwner': 'fixture-native-host', 'leaseToken': uuid.uuid4().hex, 'leaseSeconds': 60}
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                status, work = request(host_base + '/claim', 'POST', lease, host_headers)
                if status == 200:
                    break
                assert status == 204, (status, work)
                time.sleep(0.2)
            assert status == 200, (status, work)
            attempt = work['attempt']
            assert work['runtimeProfile']['configuration']['model'] == 'fixture-model'
            assert work['task']['originator']['ref'] == 'fixture-user'
            attempt_headers = {**host_headers, 'X-Execution-Attempt-Token': work['attemptToken']}
            attempt_url = host_base + '/' + attempt['id']
            proof = {'leaseToken': lease['leaseToken'], 'fencingToken': attempt['fencingToken']}
            for operation in ('preparing', 'running'):
                assert request(attempt_url + '/' + operation, 'POST', proof, attempt_headers)[0] == 200
            cancel_receipt = None
            if exercise_cancel:
                cancel_headers = {**caller, 'Idempotency-Key': 'native-fixture-cancel'}
                status, cancel_receipt = request(public + '/turns/' + turn_id + '/cancel', 'POST', headers=cancel_headers)
                assert status == 202, (status, cancel_receipt)
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    status, pending = request(public + '/commands/' + cancel_receipt['command_id'], headers=caller)
                    if pending.get('command_status') == 'runtime_accepted':
                        break
                    time.sleep(0.2)
                assert status == 200 and pending['command_status'] == 'runtime_accepted', (status, pending)
                assert request(public + '/turns/' + turn_id + '/cancel', 'POST', headers=cancel_headers)[1]['command_id'] == cancel_receipt['command_id']
                assert request(attempt_url + '/renew', 'POST', {**proof, 'leaseSeconds': 60}, attempt_headers)[0] == 403
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    status, cancelled = request(attempt_url + '/cancelled', 'POST', proof, attempt_headers)
                    if status == 200:
                        break
                    assert status == 409, (status, cancelled)
                    time.sleep(0.2)
                assert status == 200, (status, cancelled)
            else:
                artifact_bytes = bytes(range(256)) * 2048
                status, uploaded = request(agent_service_url + '/mcp/collaboration', 'POST',
                    {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call', 'params':
                        {'name': 'artifact.upload', 'arguments': {'filename': 'native-result.bin',
                            'contentType': 'application/octet-stream',
                            'contentBase64': base64.b64encode(artifact_bytes).decode()}}},
                    {'Authorization': 'Bearer ' + work['taskToken']})
                assert status == 200 and not uploaded.get('error') and not uploaded['result'].get('isError'), (status, uploaded)
                artifact_record = uploaded['result']['structuredContent']['artifact']
                assert artifact_record['sizeBytes'] == len(artifact_bytes) and 'portalCommandId' not in artifact_record
                result = {'output': 'fixture native answer', 'usage': {'inputTokens': 3, 'outputTokens': 2}}
                if runtime_kind == 'team':
                    status, semantic = request(agent_service_url + '/mcp/collaboration', 'POST',
                        {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params':
                            {'name': 'run.node.complete', 'arguments': {'output': result}}},
                        {'Authorization': 'Bearer ' + work['taskToken']})
                    assert status == 200 and not semantic.get('error') and not semantic['result'].get('isError'), (status, semantic)
                assert request(attempt_url + '/complete', 'POST', {**proof, 'result': result}, attempt_headers)[0] == 200
            time.sleep(1.5)
            status, waiting = request(public + '/turns/' + turn_id, headers=caller)
            assert status == 200 and waiting['status'] == ('cancel_requested' if exercise_cancel else 'running'), (status, waiting)
            stop(agentService)
            agentService = start_agent_service()
            assert request(attempt_url + '/physical-stop', 'POST', {**proof, 'fencingToken': proof['fencingToken'] + 1}, attempt_headers)[0] == 409
            for _ in range(2):
                assert request(attempt_url + '/physical-stop', 'POST', proof, attempt_headers)[0] == 200
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status, completed = request(public + '/turns/' + turn_id, headers=caller)
            if completed.get('status') == ('cancelled' if exercise_cancel else 'completed'):
                break
            time.sleep(0.2)
        assert status == 200 and completed['status'] == ('cancelled' if exercise_cancel else 'completed'), (status, completed)
        assert request(capabilities_url, headers=caller)[1]['available_commands'] == []
        status, newest = request(public + '/turns?order=desc&limit=1', headers=caller)
        assert status == 200 and newest['data'][0]['id'] == turn_id and not newest['has_more'], (status, newest)
        status, active = request(public + '/turns?order=desc&status=running&limit=1', headers=caller)
        assert status == 200 and active['data'] == [], (status, active)
        if exercise_daemon:
            stop(agentService)
            agentService = start_agent_service()
            assert request(public + '/turns/' + turn_id, headers=caller)[1]['status'] == ('cancelled' if exercise_cancel else 'completed')
        status, page = request(public + '/events?limit=1000', headers=caller)
        assert status == 200 and not page['has_more'], (status, page)
        events = page['data']
        positions = [int(base64.urlsafe_b64decode(event['cursor'] + '=' * (-len(event['cursor']) % 4)).decode().split('\n')[-1]) for event in events]
        assert positions == list(range(1, len(events) + 1))
        replay = read_sse(public + '/events/stream', {**caller, 'Last-Event-ID': events[0]['cursor']}, len(events) - 1)
        assert replay == events[1:]
        usage = [event for event in events if event['type'] == 'usage.recorded']
        tokens = 0 if exercise_cancel and not exercise_daemon else 5
        assert sum(event['data']['usage']['inputTokens'] + event['data']['usage']['outputTokens'] for event in usage) == tokens
        assert events[-1]['type'] == ('turn.cancelled' if exercise_cancel else 'turn.completed')
        if artifact_record:
            published = [event for event in events if event['type'] == 'artifact.published']
            assert len(published) == 1 and published[0]['data']['artifact_id'] == artifact_record['id'], published
            download = published[0]['data']['download_url']
            assert download == public.removeprefix(agent_service_url) + '/turns/' + turn_id + '/artifacts/' + artifact_record['id'] + '/content'
            assert request(agent_service_url + download, headers=caller, raw_response=True) == (200, artifact_bytes)
            status, artifacts = request(public + '/turns/' + turn_id + '/artifacts?limit=10', headers=caller)
            assert status == 200 and len(artifacts['data']) == 1 and artifacts['data'][0]['data']['artifact_id'] == artifact_record['id'], (status, artifacts)
            assert request(public + '/turns/' + turn_id + '/artifacts/' + artifact_record['id'], headers=caller)[1]['data']['data']['artifact_id'] == artifact_record['id']
            sdk = importlib.util.spec_from_file_location('fixture_public_service_sdk', ROOT / 'control-plane/sdk/python/managed_agents/service.py')
            sdk_module = importlib.util.module_from_spec(sdk)
            sdk.loader.exec_module(sdk_module)
            client = sdk_module.ServiceClient(agent_service_url, api_token=user_token())
            assert client.artifact(session_id, turn_id, artifact_record['id']) == artifact_bytes
            assert client.artifact_metadata(session_id, artifact_record['id'], turn_id=turn_id)['data']['data']['artifact_id'] == artifact_record['id']
            assert len(client.artifacts(session_id, turn_id, limit=10)['data']) == 1
            assert client.capabilities(session_id)['capabilities']['artifacts'] is True
            sdk_snapshot = client.snapshot(session_id, turn_id, limit=10)
            assert sdk_snapshot['resources']['turns']['data'][0]['data']['status'] == 'completed'
            assert client.usage(session_id, turn_id)['totals'] == {'inputTokens': 3, 'outputTokens': 2}
            assert len(client.turns(session_id, limit=10, order='desc')['data']) == 1
            assert len(client.actions(session_id, turn_id, limit=10)['data']) == 0
            subprocess.run([shutil.which('node'), str(ROOT / 'tests/service_client_agent_service_smoke.mjs'), agent_service_url, session_id, turn_id, artifact_record['id'], hashlib.sha256(artifact_bytes).hexdigest()], env={'PATH': os.environ['PATH'], 'MANAGED_AGENTS_TEST_TOKEN': user_token()}, check=True, timeout=30)
            java_module = ROOT / 'java-client'
            classpath = java_module / 'target/client-classpath.txt'
            java_jar = java_module / 'target' / ('managed-agents-client-' + version + '.jar')
            if not classpath.is_file() or not java_jar.is_file():
                raise RuntimeError('Build java-client and dependency:build-classpath before this fixture')
            sdk_environment = {'PATH': os.environ['PATH'], 'MANAGED_AGENTS_TEST_TOKEN': user_token()}
            if os.environ.get('JAVA_HOME'):
                sdk_environment['JAVA_HOME'] = os.environ['JAVA_HOME']
            subprocess.run([shutil.which('java'), '--class-path', str(java_jar) + os.pathsep + classpath.read_text().strip(), '--source', '17', str(ROOT / 'tests/ServiceClientAgentServiceSmoke.java'), agent_service_url, session_id, turn_id, artifact_record['id'], hashlib.sha256(artifact_bytes).hexdigest()], env=sdk_environment, check=True, timeout=30)
            print('Native task artifact and actual Python/DSH/Java ServiceClient flows passed after Agent Service restart.')
        if approval:
            status, applied = request(public + '/commands/' + approval_receipt['command_id'], headers=caller)
            assert status == 200 and applied['command_status'] == 'applied', (status, applied)
            resolved = [event for event in events if event['type'] == 'required_action.resolved']
            assert len(resolved) == 1 and resolved[0]['data']['request_id'] == approval_id
            assert resolved[0]['data']['resolution_status'] == ('approved' if approval == 'approve' else 'rejected'), resolved
        if exercise_cancel:
            status, applied = request(public + '/commands/' + cancel_receipt['command_id'], headers=caller)
            assert status == 200 and applied['command_status'] == 'applied', (status, applied)
            assert request(public + '/turns/' + turn_id + '/cancel', 'POST', headers=cancel_headers)[1]['command_id'] == cancel_receipt['command_id']
        else:
            assert any(event['type'] == 'item.completed' for event in events)
        if exercise_daemon:
            types = [event['type'] for event in events]
            for expected in ('item.started', 'item.delta', 'tool.requested', 'tool.delta', 'tool.completed', 'usage.recorded'):
                assert expected in types, (expected, types)
            assert len(usage) == 1, usage
            assert any(block.get('text') == '你好 🧰' for event in events if event['type'] == 'item.delta' for block in event['data'].get('content', []))
            status, snapshot = request(public + '/turns/' + turn_id + '/snapshot?limit=100', headers=caller)
            assert status == 200 and snapshot['usage']['totals'] == {'inputTokens': 3, 'outputTokens': 2}, (status, snapshot)
            assert usage[-1]['data']['session_usage']['totals'] == snapshot['usage']['totals'], usage
            assert usage[-1]['data']['turn_usage']['totals'] == snapshot['usage']['totals'], usage
            messages = snapshot['resources']['items']['data']
            assert any(message['data']['item_id'].endswith(':message-1') and
                message['data']['item']['content'][0]['text'] == '你好 🧰' for message in messages), messages
            tools = snapshot['resources']['tools']['data']
            assert any(tool['data']['tool_call_id'].endswith(':tool-1') and
                tool['data']['status'] == 'completed' and tool['data']['output'] == 'fixture result' for tool in tools), tools
            methods = [json.loads(line)['method'] for line in (folder / 'provider-requests.jsonl').read_text().splitlines()]
            assert methods.count('turn/start') == 1 and methods.count('thread/start') == 1, methods
            if approval:
                assert methods.count('fixture/approval_response') == 1, methods
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and list((folder / 'state/execution-attempts').glob('*.json')):
                time.sleep(0.2)
            assert not list((folder / 'state/execution-attempts').glob('*.json')), 'Host final outbox not drained'
            cache_before_restart = credential_cache.read_bytes()
            cache_time_before_restart = credential_cache.stat().st_mtime_ns
            host_arguments = host.args
            stop(host)
            ready_file.unlink(missing_ok=True)
            host = subprocess.Popen(host_arguments, env=host_environment, stdout=host_log, stderr=subprocess.STDOUT)
            processes.append(host)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and not ready_file.exists():
                assert host.poll() is None, 'Host restart failed; inspect ' + str(folder)
                time.sleep(0.2)
            assert ready_file.exists(), 'Host restart did not register'
            assert credential_cache.read_bytes() == cache_before_restart
            assert credential_cache.stat().st_mtime_ns == cache_time_before_restart, 'Restart renewed instead of reusing persisted credential'

        assert request(public + '/turns', 'POST', input_value, caller)[1]['id'] == turn_id
        for operation, lifecycle in (('archive', 'archived'), ('restore', 'active')):
            lifecycle_headers = {**caller, 'Idempotency-Key': 'native-fixture-' + operation}
            status, lifecycle_command = request(public + '/' + operation, 'POST', headers=lifecycle_headers)
            assert status == 200 and lifecycle_command['status'] == lifecycle, (status, lifecycle_command)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                status, mirror = request(public + '/lifecycle-commands/' + lifecycle_command['command_id'], headers=caller)
                if mirror.get('command_status') == 'applied':
                    break
                time.sleep(0.2)
            assert status == 200 and mirror['command_status'] == 'applied', (status, mirror)
            if lifecycle == 'archived':
                assert request(public + '/turns', 'POST', input_value, {**caller, 'Idempotency-Key': 'new-archived-turn'})[0] == 409
            assert request(public + '/' + operation, 'POST', headers=lifecycle_headers)[1]['command_id'] == lifecycle_command['command_id']
        passed = True
        print(json.dumps({'result': 'passed', 'mode': fixture['mode'], 'runtime_kind': runtime_kind, 'session_id': session_id,
            'turn_id': turn_id, 'native_task_execution_tested': True, 'provider_cli_invoked': exercise_daemon, 'external_model_called': False,
            'collaboration_mcp_tested': exercise_daemon or runtime_kind == 'team' and not exercise_cancel, 'state_root_exclusion_tested': exercise_daemon,
            'separate_resource_control_process_needed': False, 'portal_restart_before_physical_stop': not exercise_daemon, 'real_host_daemon': exercise_daemon,
            'old_stop_fence_rejected': not exercise_daemon, 'sse_resume_tested': True, 'cancellation_tested': exercise_cancel,
            'human_approval_tested': approval, 'portal_restart_while_waiting': bool(approval),
            'machine_credential_renewal_and_daemon_restart': exercise_daemon,
            'public_session_creation_tested': public_create, 'fake_configuration_source': public_create,
            'lifecycle_mirror_tested': True, 'reported_tokens': tokens, 'public_events': len(events)}))
    finally:
        if configuration_control:
            configuration_control.shutdown()
            configuration_control.server_close()
        for process in reversed(processes):
            stop(process)
        for handle in handles:
            handle.close()
        if passed:
            shutil.rmtree(folder)
        else:
            print('Failure logs retained: ' + str(folder))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='2.1.0-SNAPSHOT')
    parser.add_argument('--runtime-url')
    parser.add_argument('--agent-service-port', type=int)
    parser.add_argument('--fixture-file', type=Path, required=True)
    parser.add_argument('--seed-control-url')
    parser.add_argument('--mode', choices=('job', 'conversation'), default='job')
    parser.add_argument('--team', action='store_true', help='Seed a Hosted Team Job fixture')
    parser.add_argument('--public-create', action='store_true', help='Use the public Session API with an isolated fake configuration admission source')
    execution = parser.add_mutually_exclusive_group()
    execution.add_argument('--exercise-cancel', action='store_true')
    execution.add_argument('--exercise-daemon', action='store_true')
    execution.add_argument('--daemon-cancel', action='store_true')
    execution.add_argument('--daemon-approve', action='store_true')
    execution.add_argument('--daemon-deny', action='store_true')
    args = parser.parse_args()
    if args.seed_control_url:
        seed(args.seed_control_url, args.fixture_file, args.mode, args.team)
    else:
        if not args.runtime_url or not args.agent_service_port:
            parser.error('--runtime-url and --agent-service-port are required to run')
        run(args.version, args.runtime_url.rstrip('/'), args.agent_service_port, args.fixture_file,
            args.exercise_cancel or args.daemon_cancel,
            args.exercise_daemon or args.daemon_cancel or args.daemon_approve or args.daemon_deny,
            'approve' if args.daemon_approve else 'deny' if args.daemon_deny else None, args.public_create)
