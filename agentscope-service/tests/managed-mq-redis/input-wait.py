#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Persistent native Input waits against owned real infrastructure and deterministic fixtures."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid

import replicas
import importlib.util


spec = importlib.util.spec_from_file_location('tool_source_fixture', Path(__file__).with_name('tool-stream.py'))
tool_source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool_source)


def events(base):
    return tool_source.all_events(base)


def prepared(state, external=False):
    environment, agent, identity = replicas.fixture_agent(max_iters=6)
    name = 'ask_user' if external else 'execute'
    definition = {'name': 'Persistent input fixture', 'version': 1,
                  'system': 'Use the explicit deterministic fixture model.',
                  'defaultEnvironmentId': environment['id'], 'maxIters': 6,
                  'tools': [{'type': 'agent_toolset', 'defaultConfig': {'enabled': False},
                             'configs': [{'name': name, 'enabled': True,
                                          'permissionPolicy': {'type': 'always_allow' if external else 'always_ask'}}]}]}
    request = urllib.request.Request(f"http://127.0.0.1:19081/api/v1/agents/{agent['id']}/definition",
                                    json.dumps(definition).encode(), {'Content-Type': 'application/json'}, method='PATCH')
    with urllib.request.urlopen(request, timeout=30) as response:
        assert response.status == 200
    return environment, agent, identity


def waiting(state, session, turn, timeout=180):
    base = '/v1/sessions/' + session
    deadline = time.monotonic() + timeout
    while True:
        fact = json.loads(replicas.fact(state, """
            SELECT json_build_object('scope',scope_key,'status',status,'epoch',execution_epoch,
                'token',execution_token,'suspension',suspension_id) FROM ma.turn WHERE turn_id=:'turn';
            """, {'turn': turn}))
        if fact['status'] == 'waiting_input' and fact['token'] is None:
            lease = replicas.run(replicas.COMPOSE + ['exec', '-T', 'redis', 'redis-cli', '--raw',
                                  'EXISTS', 'ma:{' + fact['scope'] + '}:execution'], capture_output=True).stdout.strip()
            if lease == b'0':
                break
        if fact['status'] in {'succeeded', 'failed', 'cancelled'} or time.monotonic() > deadline:
            raise AssertionError(('No durable input wait with a released execution token', fact))
        time.sleep(.25)
    snapshot = replicas.request(19084, base + '/turns/' + turn + '/snapshot')
    actions = snapshot['resources']['required_actions']['data']
    assert len(actions) == 1, actions
    fact['action'] = actions[0]['data']['request_id']
    fact['kind'] = actions[0]['data']['kind']
    assert replicas.request(19184, base + '/turns/' + turn)['status'] == 'waiting'
    return fact


def run(state, scenario, seconds, continue_record=None):
    manifest = json.loads((state / 'manifest.json').read_text())
    record = {'passed': False, 'scenario': scenario, 'model': 'explicit deterministic fixture',
              'sandbox': 'actual local process; schema-only ask_user is test-injected for external replies',
              'mq_profile': manifest['mq_profile'], 'mq_test_only_derivative': manifest['mq_test_only_derivative']}
    if continue_record:
        continue_record = continue_record.resolve()
        if scenario != 'capacity' or continue_record.parent != state:
            raise ValueError('Continue only a capacity experiment in this fixture')
        record = json.loads(continue_record.read_text())
        assert record['scenario'] == 'capacity' and not record['passed']
        record.update(continued_from=str(continue_record), previous_error=record.pop('error', None))
    suffix = '-continuation' if continue_record else ''
    output = state / f'input-wait-{scenario}{suffix}-{time.time_ns()}.json'

    def save():
        output.write_text(json.dumps(record, indent=2) + '\n')

    save()
    try:
        if scenario == 'capacity':
            capacity(state, record, save)
        else:
            external = scenario in {'external', 'external-error'}
            environment, agent, identity = prepared(state, external)
            session = replicas.request(19084, '/v1/sessions', {'agent': agent['id']}, uuid.uuid4().hex)['id']
            base = '/v1/sessions/' + session
            label = 'input-' + identity
            message = f'fixture:{scenario}:' + label if external else f'fixture:execute:{seconds}:{label}'
            first = replicas.request(19084, base + '/turns', {'input': {'message': message}}, uuid.uuid4().hex)
            second = replicas.request(19184, base + '/turns',
                                      {'input': {'message': 'fixture:echo:after-' + label}}, uuid.uuid4().hex)
            record.update(session=session, turns=[first['id'], second['id']], label=label,
                          environment=environment['id'], agent=agent['id'])
            save()
            initial = waiting(state, session, first['id'])
            assert initial['kind'] == ('external_execution' if external else 'confirmation'), initial
            assert replicas.request(19084, base + '/turns/' + second['id'])['status'] == 'accepted'
            assert not [path for path in (state / 'sandbox').glob('*/side-effects.txt') if label in path.read_text()]
            record['waiting'] = initial
            save()
            if scenario in {'confirmation', 'external', 'external-error'}:
                record['old_brain_pids'] = tool_source.kill(state, ['brain-a', 'brain-b'])
                body = {'type': 'reply', 'action_id': initial['action'], 'payload':
                        {'content': [{'type': 'text', 'text': 'external-' + label}], 'is_error': scenario == 'external-error'}
                        if external else {'allow': True}}
            elif scenario == 'cancel':
                body = {'type': 'cancel', 'payload': {}}
            else:
                raise ValueError('Unknown scenario')
            endpoint = base + '/turns/' + first['id'] + '/inputs'
            key = uuid.uuid4().hex
            accepted = replicas.request(19184, endpoint, body, key)
            duplicate = replicas.request(19084, endpoint, body, key)
            assert accepted['id'] == duplicate['id'] and accepted['sequence'] == duplicate['sequence']
            record['input'] = accepted
            save()
            if scenario in {'confirmation', 'external', 'external-error'}:
                assert all(tool_source.exited(pid) for pid in record['old_brain_pids'].values())
                assert replicas.request(19084, endpoint + '/' + accepted['id'])['status'] == 'accepted'
                resumes = json.loads(replicas.fact(state, """
                    SELECT json_build_object('count',count(*),'suspensions',count(DISTINCT suspension_id))
                    FROM ma.turn_resume WHERE turn_id=:'turn' AND suspension_id=:'suspension';
                    """, {'turn': first['id'], 'suspension': initial['suspension']}))
                assert resumes == {'count': 1, 'suspensions': 1}, resumes
                record['resume_while_brain_absent'] = resumes
                record['new_brain_pids'] = {name: replicas.restart_replica(state, name)
                                           for name in ['brain-a', 'brain-b']}
                save()
            terminal = 'cancelled' if scenario == 'cancel' else 'completed'
            replicas.wait_turn(base, first['id'], {terminal}, timeout=600)
            replicas.wait_turn(base, second['id'], {'completed'}, timeout=300)
            receipt = replicas.request(19184, endpoint + '/' + accepted['id'])
            assert receipt['status'] == 'applied', receipt
            history = events(base)
            endings = [(e['type'], e['data'].get('turn_id')) for e in history
                       if e['type'] in {'turn.completed', 'turn.cancelled', 'turn.failed'}]
            assert endings == [('turn.' + terminal, first['id']), ('turn.completed', second['id'])], endings
            if scenario == 'confirmation':
                matching = [path for path in (state / 'sandbox').glob('*/side-effects.txt') if label in path.read_text()]
                assert len(matching) == 1 and matching[0].read_text().splitlines() == ['start-' + label, 'done-' + label], matching
                assert 'brain-confirmed-hands-' + label in json.dumps(history), history
            elif external:
                assert f'brain-confirmed-{scenario}-' + label in json.dumps(history), history
                assert 'external-' + label in json.dumps(history), history
                tools = [event['data'] for event in history if event['type'] == 'tool.completed'
                         and event['data'].get('tool_call_id') == 'replica-external-' + label]
                assert len(tools) == 1, tools
                assert tools[0]['status'] == ('failed' if scenario == 'external-error' else 'completed'), tools
                assert tools[0]['result']['state'] == ('error' if scenario == 'external-error' else 'success'), tools
            else:
                assert not [path for path in (state / 'sandbox').glob('*/side-effects.txt') if label in path.read_text()]
            snapshots = replicas.request(19084, base + '/turns/' + first['id'] + '/snapshot')
            assert snapshots['resources']['required_actions']['data'] == [], snapshots
            record.update(receipt=receipt, events=history,
                          checkpoints=replicas.checkpoint_evidence(state, session))
        record['passed'] = True
        print(f'PASSED {scenario} persistent input wait: {output}', flush=True)
    except BaseException:
        import traceback
        record['error'] = traceback.format_exc()
        raise
    finally:
        save()


def capacity(state, record, save):
    manifest = json.loads((state / 'manifest.json').read_text())
    assert manifest['concurrency'] == 1 and manifest['turn_callbacks'] >= 8, manifest
    _, agent, identity = prepared(state)
    roots = record.setdefault('roots', [])
    for root in roots:
        root['waiting'] = waiting(state, root['session'], root['turn'])
    while True:
        owners = sorted(set(replicas.execution_owners(state, [root['turn'] for root in roots]).values()))
        if len(owners) == 2:
            break
        assert len(roots) < 6, ('Could not establish waiters on both replicas', owners, roots)
        index = len(roots)
        session = replicas.request(19084, '/v1/sessions', {'agent': agent['id']}, uuid.uuid4().hex)['id']
        turn = replicas.request(19084, '/v1/sessions/' + session + '/turns',
                                {'input': {'message': f'fixture:execute:1:capacity-{identity}-{index}'}}, uuid.uuid4().hex)
        root = {'session': session, 'turn': turn['id']}
        roots.append(root)
        save()
        root['waiting'] = waiting(state, root['session'], root['turn'])
    record['waiting_execution_owners'] = owners
    # Blocked Harness workers on both replicas would prevent this independent root from completing.
    session = replicas.request(19184, '/v1/sessions', {'agent': agent['id']}, uuid.uuid4().hex)['id']
    third = replicas.request(19184, '/v1/sessions/' + session + '/turns',
                             {'input': {'message': 'fixture:echo:free-worker-' + identity}}, uuid.uuid4().hex)
    record['third'] = {'session': session, 'turn': third['id']}
    save()
    replicas.wait_turn('/v1/sessions/' + session, third['id'], {'completed'}, timeout=240)
    for root in roots:
        base = '/v1/sessions/' + root['session']
        assert replicas.request(19084, base + '/turns/' + root['turn'])['status'] == 'waiting'
        replicas.request(19184, base + '/turns/' + root['turn'] + '/inputs',
                         {'type': 'cancel', 'payload': {}}, uuid.uuid4().hex)
    for root in roots:
        replicas.wait_turn('/v1/sessions/' + root['session'], root['turn'], {'cancelled'}, timeout=180)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--scenario', choices=['confirmation', 'external', 'external-error', 'cancel', 'capacity'], required=True)
    parser.add_argument('--seconds', type=int, default=45)
    parser.add_argument('--continue-record', type=Path,
                        help='Continue existing capacity waiters and add only bounded ownership probes')
    args = parser.parse_args()
    if not 20 <= args.seconds <= 120:
        parser.error('--seconds must be 20..120')
    run(args.state.resolve(), args.scenario, args.seconds, args.continue_record)
