#!/usr/bin/env python3
"""Real Agent Service registration and Turn delivery to an attached Go runtime.

The runtime may use PostgreSQL or memory. Native task execution is deliberately
reported separately; this check verifies configuration/inbox commit and restart.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
from urllib.error import URLError

from orchestration_runtime_host_smoke import ROOT, SERVICE_SECRET, SIGNING_SECRET, port, request
from agent_service_postgres_fixture import agent_service_arguments


def digest(value):
    def string(text):
        output = '\"'
        for ch in text:
            code = ord(ch)
            if ch in ('\"', '\\'):
                output += '\\' + ch
            elif ch in ('\b', '\f', '\n', '\r', '\t'):
                output += {'\b': r'\b', '\f': r'\f', '\n': r'\n', '\r': r'\r', '\t': r'\t'}[ch]
            elif code < 32:
                output += r'\u%04X' % code
            elif code > 0xFFFF:
                code -= 0x10000
                output += r'\u%04X\u%04X' % (0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF))
            else:
                output += ch
        return output + '\"'

    def encode(item):
        if isinstance(item, dict):
            return '{' + ','.join(string(key) + ':' + encode(item[key]) for key in sorted(item, key=lambda key: key.encode('utf-16-be'))) + '}'
        if isinstance(item, list):
            return '[' + ','.join(encode(part) for part in item) + ']'
        if isinstance(item, str):
            return string(item)
        return json.dumps(item, separators=(',', ':'))
    return hashlib.sha256(encode(value).encode()).hexdigest()


def user_token():
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=')
    value = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode({'sub': 'fixture-user', 'roles': ['user'], 'exp': int(time.time()) + 600})
    signed = base64.urlsafe_b64encode(hmac.new(SIGNING_SECRET.encode(), value, hashlib.sha256).digest()).rstrip(b'=')
    return (value + b'.' + signed).decode()


def run(version, runtime_url, fixture_dsn=None):
    folder = Path(tempfile.mkdtemp(prefix='managed-agents-orchestration-portal-smoke-'))
    processes, handles = [], []
    passed = False
    agent_service_port = port()
    agent_service_url = 'http://127.0.0.1:' + str(agent_service_port)
    environment = {'PATH': os.environ['PATH'], 'TZ': 'UTC', 'MANAGED_AGENTS_INTERNAL_TOKEN': SERVICE_SECRET, 'MANAGED_AGENTS_JWT_SECRET': SIGNING_SECRET}
    if os.environ.get('JAVA_HOME'):
        environment['JAVA_HOME'] = os.environ['JAVA_HOME']

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    agent_service_storage_arguments = agent_service_arguments(version, fixture_dsn or os.environ.get('MANAGED_AGENTS_TEST_POSTGRES_DSN'), folder, environment)

    def start_agent_service():
        handle = (folder / 'portal.log').open('a')
        handles.append(handle)
        jar = ROOT / 'agent-service/target' / ('managed-agents-agent-service-' + version + '.jar')
        process = subprocess.Popen([shutil.which('java'), '-jar', str(jar),
             '--server.address=127.0.0.1', '--server.port=' + str(agent_service_port),
             '--spring.profiles.active=test', '--managed-agents.local-dev=false',
             '--managed-agents.jwt.secret=' + SIGNING_SECRET, '--managed-agents.vault.master-key=portal-inbox-smoke-only',
             '--managed-agents.orchestration-runtime-url=' + runtime_url, '--managed-agents.control-plane-url=http://127.0.0.1:1',
             '--managed-agents.agent-service.turn-delivery-poll-ms=200'] + agent_service_storage_arguments, env=environment, stdout=handle, stderr=subprocess.STDOUT)
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
        session_id, target_id = str(uuid.uuid4()), str(uuid.uuid4())
        now = int(time.time() * 1000)
        configuration = {'session': {'id': session_id, 'ownerId': 'fixture-user'},
            'contract': {'public_session_id': session_id, 'endpoint': {'id': session_id, 'tenant': 'fixture-tenant',
                'namespace': 'fixture-space', 'targetType': 'team', 'targetRef': target_id}},
            'unicode': '🧰 世界', 'number': 9007199254740993, 'optional': None}
        context = {'version': 1, 'tenant': 'fixture-tenant', 'namespace': 'fixture-space', 'applicationId': None,
            'sessionId': session_id, 'turnId': None, 'commandId': 'bootstrap:' + session_id, 'traceId': str(uuid.uuid4()),
            'actor': {'user': 'fixture-user', 'key_hash': '', 'internal': False}, 'policyVersion': 'initial', 'permissionExpiresAt': now + 90000}
        bootstrap = {'context': context, 'runtimeKind': 'team', 'configurationRevision': digest(configuration), 'configuration': configuration}
        policy = {'tenant': 'fixture-tenant', 'namespace': 'fixture-space',
            'humans': {'fixture-user': ['read', 'invoke', 'interact', 'cancel', 'approve']}, 'credentials': [],
            'application': None, 'enabled': True, 'valid_until': now + 90000}
        admission = {'bootstrap': bootstrap, 'policy': policy,
            'publicView': {'id': session_id, 'status': 'active', 'runtimeKind': 'team', 'target': {'type': 'team', 'id': target_id}}}
        service = {'X-Managed-Agents-Internal-Token': SERVICE_SECRET}
        caller = {'Authorization': 'Bearer ' + user_token(), 'Idempotency-Key': 'portal-inbox-turn'}
        agentService = start_agent_service()
        status, registry = request(agent_service_url + '/api/internal/runtime/sessions/provision', 'POST', admission, service)
        assert status == 200 and registry['state'] == 'ready', (status, registry)
        public = agent_service_url + '/api/v1/agent-sessions/' + session_id
        assert request(public, headers=caller)[0] == 200
        assert request(public, headers=service)[0] == 401
        status, accepted = request(public + '/turns', 'POST', {'message': '你好 🧰', 'number': 9007199254740993}, caller)
        assert status == 202, (status, accepted)
        turn_id = accepted['id']
        receipt_url = runtime_url + '/api/internal/runtime/commands/turn/' + session_id + '/' + turn_id
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            status, receipt = request(receipt_url, headers=service)
            if status == 200:
                break
            time.sleep(0.2)
        assert status == 200 and receipt['commandId'] == turn_id and receipt['admissionSequence'] == 1, (status, receipt)
        assert request(receipt_url.replace(session_id, str(uuid.uuid4())), headers=service)[0] == 404
        assert request(receipt_url)[0] == 401
        duplicate = request(public + '/turns', 'POST', {'message': '你好 🧰', 'number': 9007199254740993}, caller)
        assert duplicate[0] == 202 and duplicate[1]['id'] == turn_id, duplicate
        stop(agentService)
        agentService = start_agent_service()
        assert request(public, headers=caller)[0] == 200
        duplicate = request(public + '/turns', 'POST', {'message': '你好 🧰', 'number': 9007199254740993}, caller)
        assert duplicate[0] == 202 and duplicate[1]['id'] == turn_id, duplicate
        assert request(receipt_url, headers=service)[1] == receipt
        bootstrap['context']['permissionExpiresAt'] = 1
        assert request(runtime_url + '/api/internal/runtime/sessions/bootstrap', 'POST', bootstrap, service)[0] == 200
        changed = {**bootstrap, 'runtimeKind': 'workflow'}
        assert request(runtime_url + '/api/internal/runtime/sessions/bootstrap', 'POST', changed, service)[0] == 409
        passed = True
        print(json.dumps({'result': 'passed', 'session_id': session_id, 'turn_id': turn_id,
            'go_configuration_and_turn_inbox_confirmed': True, 'unicode_and_large_integer_digest_checked': True,
            'portal_restart': True, 'control_plane_started': False, 'native_task_execution_tested': False}))
    finally:
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
    parser.add_argument('--runtime-url', required=True, help='Already running local Go runtime fixture URL.')
    parser.add_argument('--fixture-dsn', help='Dedicated PostgreSQL database for Agent Service schemas.')
    options = parser.parse_args()
    run(options.version, options.runtime_url, options.fixture_dsn)
