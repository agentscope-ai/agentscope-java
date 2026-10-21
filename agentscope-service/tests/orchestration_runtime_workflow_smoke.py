#!/usr/bin/env python3
"""Public Workflow approval flow against real Portal, Go and isolated PostgreSQL.

Control-plane CRUD seeds a revision and then stops. Configuration admission uses
an isolated HTTP fixture; this does not verify real account/Namespace RBAC.
No model/provider calls are made. Use only a dedicated local test database.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from threading import Thread
import time
import uuid
from urllib.error import URLError
from urllib.parse import urlparse

from orchestration_runtime_host_smoke import ROOT, SERVICE_SECRET, SIGNING_SECRET, credential, port, request
from orchestration_runtime_portal_smoke import digest, user_token
from portal_postgres_fixture import portal_arguments
from orchestration_runtime_native_smoke import seed
from session_smoke_http import read_sse


def run(version, dsn, deny=False, parallel=False, signal=False, nested=False, agent_signal=False):
    signal = signal or agent_signal
    if urlparse(dsn).hostname not in ('127.0.0.1', 'localhost'):
        raise ValueError('Use a dedicated loopback PostgreSQL fixture')
    folder = Path(tempfile.mkdtemp(prefix='agentscope-workflow-runtime-smoke-'))
    environment = {'PATH': os.environ['PATH'], 'TZ': 'UTC', 'BUILDER_INTERNAL_TOKEN': SERVICE_SECRET,
                   'BUILDER_JWT_SECRET': SIGNING_SECRET,
                   'BUILDER_VAULT_MASTER_KEY': 'workflow-smoke-runtime-encryption-key-only'}
    if os.environ.get('JAVA_HOME'):
        environment['JAVA_HOME'] = os.environ['JAVA_HOME']
    processes, handles, configuration_source, passed = [], [], None, False
    portal_port, runtime_port, control_port = port(), port(), port()
    portal_url, runtime_url, control_url = ['http://127.0.0.1:' + str(value)
                                          for value in (portal_port, runtime_port, control_port)]
    scope = {'tenant': 'fixture-tenant', 'namespace': 'fixture-space'}
    portal_storage_arguments = portal_arguments(version, dsn, folder, environment)

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start(name, arguments, health):
        handle = (folder / (name + '.log')).open('a')
        handles.append(handle)
        process = subprocess.Popen(arguments, env=environment, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + ' exited; logs: ' + str(folder))
            try:
                if request(health)[0] == 200:
                    return process
            except (URLError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError(name + ' did not become ready; logs: ' + str(folder))

    def await_value(address, predicate, headers, timeout=30):
        deadline = time.monotonic() + timeout
        value = None
        while time.monotonic() < deadline:
            status, value = request(address, headers=headers)
            if status == 200 and predicate(value):
                return value
            time.sleep(0.2)
        raise AssertionError((address, value))

    try:
        with (folder / 'migration.log').open('w') as migration_log:
            subprocess.run([str(ROOT / 'service-controlplane/bin/service-controlplane'),
                '--storage-driver=postgres', '--storage-dsn=' + dsn, '--migrate-only'], env=environment,
                stdout=migration_log, stderr=subprocess.STDOUT, check=True, timeout=90)
        control = start('seed-control', [str(ROOT / 'service-controlplane/bin/service-controlplane'),
            '--enable-kubernetes=false', '--enable-product=false', '--enable-asdp=false',
            '--local-dev=true', '--scope-mode=single', '--default-tenant=fixture-tenant',
            '--default-namespace=fixture-space', '--enable-runtime-workers=false',
            '--storage-driver=postgres', '--storage-dsn=' + dsn,
            '--http-bind-address=127.0.0.1:' + str(control_port),
            '--metrics-bind-address=127.0.0.1:' + str(port()),
            '--health-probe-bind-address=127.0.0.1:' + str(port()),
            '--artifact-root=' + str(folder / 'artifacts')],
            control_url + '/api/v1/orchestration-definitions?tenant=fixture-tenant&namespace=fixture-space')
        agent_fixture = None
        if agent_signal:
            assert not nested and not parallel and not deny
            seeded = folder / 'agent-fixture.json'
            seed(control_url, seeded, 'job')
            agent_fixture = json.loads(seeded.read_text())
        nodes = [{'key': 'gate', 'type': 'approval', 'input': {'value': 'run.input.value', 'apiKey': 'run.input.apiKey'}, 'approval': {'approverRef': 'fixture-user', 'prompt': 'Ship?'}},
                 {'key': 'after', 'type': 'condition', 'input': {'answer': 'nodes.gate.output.allow'}}]
        if signal:
            nodes[0] = {'key': 'gate', 'type': 'signal', 'signalName': 'ready'}
            nodes[1]['input'] = {'answer': 'nodes.gate.output.value'}
        if agent_signal:
            nodes.append({'key': 'sender', 'type': 'agent', 'issueMode': 'child', 'agentId': agent_fixture['agent_id']})
        revisions = {}
        for depth in range(3 if nested else 1):
            if parallel and depth == (2 if nested else 0):
                nodes.append({'key': 'independent', 'type': 'timer', 'timer': {'durationSeconds': 3600}})
            status, result = request(control_url + '/api/v1/orchestration-definitions', 'POST',
                {**scope, 'name': 'portal-workflow-' + uuid.uuid4().hex[:10],
                 'draftSpec': {'nodes': nodes, 'edges': [{'from': 'gate', 'to': 'after'}]}})
            assert status == 201, (status, result)
            definition = result['definition']
            status, result = request(control_url + '/api/v1/orchestration-definitions/' + definition['id'] + '/publish', 'POST', {})
            assert status == 201, (status, result)
            revision = result['revision']
            revisions[revision['id']] = revision
            nodes = [{'key': 'gate', 'type': 'subrun', 'definitionRevisionId': revision['id'],
                      'input': {'value': 'run.input.value', 'apiKey': 'run.input.apiKey'}},
                     {'key': 'after', 'type': 'condition', 'input': {'answer': 'nodes.gate.output.after.answer'}}]
        stop(control)
        session_id = str(uuid.uuid4())
        configuration = {'session': {'id': session_id, 'ownerId': 'fixture-user'}, 'contract': {
            'public_session_id': session_id, 'revisions': revisions,
            'endpoint': {'id': session_id, **scope, 'targetType': 'orchestration_revision',
                         'targetRef': revision['id'], 'invocationMode': 'job'}}}
        if agent_signal:
            for name in ('agents', 'policies', 'runtime_profiles', 'runtime_pools'):
                configuration['contract'][name] = agent_fixture['configuration']['contract'][name]
        actor = {'user': 'fixture-user', 'key_hash': '', 'internal': False}
        policy = {**scope, 'humans': {'fixture-user': ['read', 'invoke', 'interact', 'cancel', 'approve']},
            'credentials': [], 'application': None, 'enabled': True, 'valid_until': int(time.time()*1000)+180000,
            'scope_id': digest({'fixture_policy': session_id})}
        view = {'id': session_id, 'tenant': scope['tenant'], 'namespace': scope['namespace'],
                'status': 'active', 'runtimeKind': 'workflow',
                'target': {'type': 'workflow', 'id': definition['id'], 'revisionId': revision['id']}}
        caller = {'Authorization': 'Bearer ' + user_token(), 'Idempotency-Key': 'create:' + session_id}

        class ConfigurationFixture(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send(self, value):
                raw = json.dumps(value).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                assert body['target']['id'] == definition['id']
                assert self.headers.get('Authorization') == caller['Authorization']
                assert self.headers.get('X-Builder-Internal-Token') == SERVICE_SECRET
                self.send({**view, 'admissionActor': actor})

            def do_GET(self):
                assert self.headers.get('X-Builder-Internal-Token') == SERVICE_SECRET
                self.send({'managed': False, 'runtimeKind': 'workflow', 'view': view, 'policy': policy, 'resolved': configuration})

        configuration_source = ThreadingHTTPServer(('127.0.0.1', 0), ConfigurationFixture)
        Thread(target=configuration_source.serve_forever, daemon=True).start()
        config_url = 'http://127.0.0.1:' + str(configuration_source.server_port)
        runtime = start('runtime', [str(ROOT / 'service-controlplane/bin/service-controlplane'),
            '--enable-kubernetes=false', '--enable-asdp=false', '--scope-mode=multi', '--enable-runtime-workers=true',
            '--http-bind-address=127.0.0.1:' + str(runtime_port), '--storage-driver=postgres',
            '--storage-dsn=' + dsn, '--session-portal-url=' + portal_url], runtime_url + '/readyz')

        def start_portal():
            return start('portal', [shutil.which('java'), '-jar', str(ROOT / 'service-session-portal/target' / ('service-session-portal-' + version + '.jar')),
                '--server.address=127.0.0.1', '--server.port=' + str(portal_port), '--spring.profiles.active=test',
                '--builder.local-dev=false', '--builder.jwt.secret=' + SIGNING_SECRET,
                '--builder.vault.master-key=workflow-smoke-only', '--builder.orchestration-runtime-url=' + runtime_url,
                '--builder.control-plane-url=' + config_url, '--builder.portal.turn-delivery-poll-ms=200'] + portal_storage_arguments, portal_url + '/actuator/health')

        portal = start_portal()
        body = {'target': view['target']}
        status, created = request(portal_url + '/api/v1/agent-sessions', 'POST', body, caller)
        assert status == 200 and created['state'] == 'ready', (status, created)
        assert request(portal_url + '/api/v1/agent-sessions', 'POST', body, caller)[1]['id'] == session_id
        configuration_source.shutdown()
        configuration_source.server_close()
        configuration_source = None
        public = portal_url + '/api/v1/agent-sessions/' + session_id
        caller['Idempotency-Key'] = 'workflow-turn'
        status, accepted = request(public + '/turns', 'POST', {'value': 42, 'apiKey': 'fixture-secret'}, caller)
        assert status == 202, (status, accepted)
        turn_id = accepted['id']
        turn_url = public + '/turns/' + turn_id
        # Fresh resource pages reserve fixed snapshots. Poll durable events for
        # readiness so the fixture does not exhaust the 16 active snapshot cap.
        await_value(public + '/events?limit=1000', lambda value: any(event['type'] == 'required_action.created' for event in value['data']), caller)
        status, pending = request(public + '/resources/required_actions', headers=caller)
        assert status == 200 and len(pending['data']) == 1, (status, pending)
        approval_id = pending['data'][0]['data']['request_id']
        assert 'fixture-secret' not in json.dumps(pending)
        await_value(turn_url, lambda value: value['status'] == ('running' if parallel or agent_signal else 'requires_action'), caller)
        assert 'actions' in request(turn_url + '/capabilities', headers=caller)[1]['available_commands']
        stop(portal)
        stop(runtime)
        runtime = start('runtime', [str(ROOT / 'service-controlplane/bin/service-controlplane'),
            '--enable-kubernetes=false', '--enable-asdp=false', '--scope-mode=multi', '--enable-runtime-workers=true',
            '--http-bind-address=127.0.0.1:' + str(runtime_port), '--storage-driver=postgres',
            '--storage-dsn=' + dsn, '--session-portal-url=' + portal_url], runtime_url + '/readyz')
        portal = start_portal()
        if agent_signal:
            host_headers = {'Authorization': 'Bearer ' + credential()}
            status, registered = request(portal_url + '/api/v1/runtime-hosts/register', 'POST',
                {'hostKey': 'fixture-host', **scope, 'poolName': agent_fixture['pool_name'],
                 'capacity': 1, 'capabilities': {'providers': {'codex': 'fixture/1'}}}, host_headers)
            assert status == 200, (status, registered)
            host = registered['host']
            host_base = portal_url + '/api/v1/runtime-hosts/' + host['id'] + '/execution-attempts'
            lease = {**scope, 'runtimePoolName': agent_fixture['pool_name'], 'generation': host['leaseGeneration'],
                     'leaseOwner': 'fixture-signal-host', 'leaseToken': uuid.uuid4().hex, 'leaseSeconds': 60}
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                status, work = request(host_base + '/claim', 'POST', lease, host_headers)
                if status == 200:
                    break
                assert status == 204, (status, work)
                time.sleep(0.2)
            assert status == 200, (status, work)
            attempt_url = host_base + '/' + work['attempt']['id']
            proof = {'leaseToken': lease['leaseToken'], 'fencingToken': work['attempt']['fencingToken']}
            attempt_headers = {**host_headers, 'X-Execution-Attempt-Token': work['attemptToken']}
            for operation in ('preparing', 'running'):
                assert request(attempt_url + '/' + operation, 'POST', proof, attempt_headers)[0] == 200
            task_headers = {'Authorization': 'Bearer ' + work['taskToken']}
            def send_signal(value, request_id=approval_id, key='agent-workflow-answer'):
                return request(portal_url + '/mcp/collaboration', 'POST',
                    {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {
                        'name': 'run.signal', 'arguments': {'name': 'ready', 'requestId': request_id,
                         'idempotencyKey': key, 'payload': {'value': value}}}}, task_headers)
            # Scope and pending identity are checked before a durable Portal command exists.
            status, rejected = send_signal(1, str(uuid.uuid4()), 'foreign-node')
            assert status == 200 and rejected['result']['isError'], (status, rejected)
            status, result = send_signal(9007199254740993)
            assert status == 200 and not result.get('error') and not result['result'].get('isError'), (status, result)
            receipt = result['result']['structuredContent']
            assert receipt['kind'] == 'signal' and receipt['status'] in ('queued', 'runtime_accepted'), receipt
            status, duplicate = send_signal(9007199254740993)
            assert status == 200 and not duplicate['result'].get('isError'), (status, duplicate)
            assert duplicate['result']['structuredContent']['command_id'] == receipt['command_id']
            status, changed = send_signal(9007199254740994)
            assert status == 200 and changed['result']['isError'], (status, changed)
            await_value(public + '/commands/' + receipt['command_id'], lambda value: value['command_status'] == 'applied', caller)
            payload_file = folder / 'signal.json'
            payload_file.write_text(json.dumps({'value': 9007199254740993}))
            cli = subprocess.run([str(ROOT / 'service-controlplane/bin/as'), '--api-endpoint=' + portal_url,
                'task', 'run', 'signal', 'ready', work['task']['id'], '--request-id=' + approval_id,
                '--idempotency-key=agent-workflow-answer', '--file=' + str(payload_file)],
                env={**environment, 'AGENTSCOPE_TASK_TOKEN': work['taskToken']}, capture_output=True,
                text=True, timeout=15)
            assert cli.returncode == 0, cli.stderr
            assert json.loads(cli.stdout)['command_id'] == receipt['command_id'], cli.stdout
            result = {'output': 'agent signal delivered', 'usage': {'inputTokens': 3, 'outputTokens': 2}}
            assert request(attempt_url + '/complete', 'POST', {**proof, 'result': result}, attempt_headers)[0] == 200
            assert request(attempt_url + '/physical-stop', 'POST', proof, attempt_headers)[0] == 200
        else:
            caller['Idempotency-Key'] = 'workflow-answer'
            answer = {'answers': [{'request_id': approval_id, 'value': {'value': 9007199254740993}}]} if signal else {'answers': [{'request_id': approval_id, 'allow': not deny}]}
            status, receipt = request(turn_url + '/actions', 'POST', answer, caller)
            assert status == 202, (status, receipt)
            assert request(turn_url + '/actions', 'POST', answer, caller)[1]['command_id'] == receipt['command_id']
            changed = {'answers': [{'request_id': approval_id, 'value': {'value': 9007199254740994}}]} if signal else {'answers': [{'request_id': approval_id, 'allow': deny}]}
            assert request(turn_url + '/actions', 'POST', changed, caller)[0] == 409
            await_value(public + '/commands/' + receipt['command_id'], lambda value: value['command_status'] == 'applied', caller)
        if parallel:
            await_value(turn_url, lambda value: value['status'] == 'running', caller)
            caller['Idempotency-Key'] = 'workflow-cancel'
            status, _ = request(turn_url + '/cancel', 'POST', headers=caller)
            assert status == 202, status
        outcome = 'cancelled' if parallel else 'failed' if deny else 'completed'
        await_value(turn_url, lambda value: value['status'] == outcome, caller)
        status, page = request(public + '/events?limit=1000', headers=caller)
        assert status == 200 and not page['has_more'], (status, page)
        events = page['data']
        assert all(name not in json.dumps(events) for name in ['portal_fence', 'control_stop', 'native_stopped', 'activation_command_id'])
        if signal:
            items = [event['data']['item'] for event in events if event['type'] == 'item.completed' and event['data'].get('final_output')]
            assert len(items) == 1, items
            result = json.loads(items[0]['content'][0]['text'])
            assert result.get('after', {}).get('answer') == 9007199254740993, result
        resolved = [event for event in events if event['type'] == 'required_action.resolved']
        assert len(resolved) == 1 and resolved[0]['data']['resolution_status'] == ('signalled' if signal else 'rejected' if deny else 'approved'), resolved
        assert events[-1]['type'] == 'turn.' + outcome
        if nested:
            steps = [event['data'] for event in events if event['type'] == 'step.updated']
            graphs = {step['graph_id']: step for step in steps if step.get('kind') == 'graph'}
            gates = {step['step_id']: step for step in steps if step.get('name') == 'gate'}
            assert len(graphs) == 3 and len(gates) == 3, (graphs, gates)
            assert all(step['parent_step_id'] == step['graph_id'] for step in gates.values()), gates
            child_graphs = [step for step in graphs.values() if step.get('parent_graph_id')]
            assert len(child_graphs) == 2 and all(step['parent_step_id'] in gates for step in child_graphs), child_graphs
            created = [event['data'] for event in events if event['type'] == 'required_action.created']
            assert len(created) == 1 and created[0]['graph_id'] in graphs, created
            if outcome == 'completed':
                assert all(step['status'] == 'succeeded' for step in graphs.values()), graphs
        assert read_sse(public + '/events/stream', {**caller, 'Last-Event-ID': events[0]['cursor']}, len(events)-1) == events[1:]
        status, snapshot = request(turn_url + '/snapshot', headers=caller)
        assert status == 200 and snapshot['turn_id'] == turn_id and snapshot['as_of'] == events[-1]['cursor'], (status, snapshot)
        assert snapshot['resources']['required_actions']['data'] == []
        snapshot_steps = {event['data']['step_id']: event['data'] for event in snapshot['resources']['steps']['data']}
        event_steps = {event['data']['step_id']: event['data'] for event in events if event['type'] == 'step.updated'}
        assert snapshot_steps == event_steps, (snapshot_steps, event_steps)
        status, remaining = request(public + '/resources/required_actions', headers=caller)
        assert status == 200 and remaining['data'] == [], (status, remaining)
        print(json.dumps({'result': 'passed', 'scenario': 'signal' if signal else 'parallel' if parallel else 'deny' if deny else 'allow',
            'public_session_creation': True, 'fake_configuration_source': True, 'real_postgres': True,
            'resource_seed_process_stopped_before_execution': True, 'portal_and_runtime_restart': True,
            'applied_receipt': True, 'sse_replay': True, 'snapshot': True,
            'nested_graphs': nested, 'agent_signal': agent_signal, 'task_cli_replay': agent_signal, 'events': len(events)}))
        passed = True
    finally:
        if configuration_source:
            configuration_source.shutdown()
            configuration_source.server_close()
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
    parser.add_argument('--fixture-dsn', required=True, help='Dedicated local test PostgreSQL DSN')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--deny', action='store_true')
    modes.add_argument('--parallel', action='store_true')
    modes.add_argument('--signal', action='store_true')
    modes.add_argument('--agent-signal', action='store_true')
    parser.add_argument('--nested', action='store_true', help='Exercise two nested subgraphs with repeated node keys')
    args = parser.parse_args()
    run(args.version, args.fixture_dsn, args.deny, args.parallel, args.signal, args.nested, args.agent_signal)
